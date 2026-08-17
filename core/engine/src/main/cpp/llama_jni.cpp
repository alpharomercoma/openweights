/*
 * Copyright 2026 The OpenWeights Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <jni.h>

#include <string>
#include <vector>

#include "engine_session.h"

using openweights::ChatMessage;
using openweights::GenerationStats;
using openweights::ParsedReply;
using openweights::ToolDefinition;
using openweights::SamplerConfig;
using openweights::Session;
using openweights::StopReason;

namespace {

Session * as_session(jlong handle) { return reinterpret_cast<Session *>(handle); }

/** One code point, appended as the UTF-8 the rest of the world means by that name. */
void append_utf8(std::string & out, uint32_t code) {
    if (code < 0x80) {
        out.push_back(static_cast<char>(code));
    } else if (code < 0x800) {
        out.push_back(static_cast<char>(0xC0 | (code >> 6)));
        out.push_back(static_cast<char>(0x80 | (code & 0x3F)));
    } else if (code < 0x10000) {
        out.push_back(static_cast<char>(0xE0 | (code >> 12)));
        out.push_back(static_cast<char>(0x80 | ((code >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (code & 0x3F)));
    } else {
        out.push_back(static_cast<char>(0xF0 | (code >> 18)));
        out.push_back(static_cast<char>(0x80 | ((code >> 12) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | ((code >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (code & 0x3F)));
    }
}

/**
 * Real UTF-8 for a Java string.
 *
 * Not `GetStringUTFChars`, which despite the name returns *modified* UTF-8: a private
 * encoding in which anything above the basic plane comes back as a six byte surrogate
 * pair rather than the four bytes UTF-8 defines. Every emoji a user types is above the
 * basic plane, and handing those six bytes to a tokenizer that expects UTF-8 feeds it a
 * sequence that is not valid UTF-8 at all. Reading UTF-16 and encoding it here is the only
 * way to get the bytes the rest of the world means.
 */
std::string to_utf8(JNIEnv * env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const jsize length = env->GetStringLength(value);
    const jchar * chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }

    std::string result;
    result.reserve(static_cast<size_t>(length));
    for (jsize i = 0; i < length; ++i) {
        uint32_t code = chars[i];
        // A high surrogate followed by a low one is a single character split across two
        // UTF-16 units, which is how UTF-16 carries anything above the basic plane.
        if (code >= 0xD800 && code <= 0xDBFF && i + 1 < length &&
            chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF) {
            code = 0x10000 + ((code - 0xD800) << 10) + (chars[i + 1] - 0xDC00);
            ++i;
        }
        append_utf8(result, code);
    }

    env->ReleaseStringChars(value, chars);
    return result;
}

/** UTF-16 for validated UTF-8, which is what the JVM actually stores. */
std::vector<jchar> to_utf16(const std::string & text) {
    std::vector<jchar> out;
    out.reserve(text.size());

    size_t index = 0;
    while (index < text.size()) {
        const auto lead = static_cast<unsigned char>(text[index]);
        size_t width = 1;
        uint32_t code = lead;
        if ((lead & 0xF8) == 0xF0) {
            width = 4;
            code = lead & 0x07u;
        } else if ((lead & 0xF0) == 0xE0) {
            width = 3;
            code = lead & 0x0Fu;
        } else if ((lead & 0xE0) == 0xC0) {
            width = 2;
            code = lead & 0x1Fu;
        }
        if (index + width > text.size()) {
            break;
        }
        for (size_t k = 1; k < width; ++k) {
            code = (code << 6) | (static_cast<unsigned char>(text[index + k]) & 0x3Fu);
        }
        index += width;

        if (code < 0x10000) {
            out.push_back(static_cast<jchar>(code));
        } else {
            code -= 0x10000;
            out.push_back(static_cast<jchar>(0xD800 + (code >> 10)));
            out.push_back(static_cast<jchar>(0xDC00 + (code & 0x3FF)));
        }
    }
    return out;
}

/**
 * A Java string from bytes that may not be valid UTF-8.
 *
 * Two separate hazards, and only one of them used to be handled. Truncated or malformed
 * bytes are still cut back to their valid prefix, because model output, GGUF metadata and
 * error messages all originate outside this app.
 *
 * The other is that `NewStringUTF` does not take UTF-8. It takes modified UTF-8, which
 * encodes anything above the basic plane as a six byte surrogate pair, and a four byte
 * sequence is not something it is allowed to be given. Those four bytes are exactly what a
 * model emits for an emoji, and JNI does not reject bad input, it aborts the process:
 * SIGABRT, no catchable exception, on a reply containing a smiling face. Converting to
 * UTF-16 and calling `NewString` sidesteps the encoding entirely.
 */
jstring to_jstring(JNIEnv * env, const std::string & text) {
    const size_t valid = openweights::complete_utf8_prefix(text);
    const std::vector<jchar> utf16 = to_utf16(text.substr(0, valid));
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

void throw_engine_exception(JNIEnv * env, const std::string & message) {
    jclass clazz = env->FindClass("io/github/alpharomercoma/openweights/core/engine/LlamaException");
    if (clazz == nullptr) return;
    // Built through the String constructor rather than ThrowNew, which has the same
    // modified UTF-8 problem: an error message carries file names and model metadata, and
    // a model whose name contains an emoji would abort the process while reporting that it
    // failed to load. consumer-rules.pro keeps this constructor for exactly this call.
    jmethodID constructor = env->GetMethodID(clazz, "<init>", "(Ljava/lang/String;)V");
    if (constructor == nullptr) return;
    jstring text = to_jstring(env, message);
    auto error = static_cast<jthrowable>(env->NewObject(clazz, constructor, text));
    if (error != nullptr) {
        env->Throw(error);
    }
}

/** Maps the native stop reason onto the ordinal of Kotlin's StopReason enum. */
jint stop_reason_ordinal(StopReason reason) {
    switch (reason) {
        case StopReason::END_OF_TURN:  return 0;
        case StopReason::MAX_TOKENS:   return 1;
        case StopReason::CONTEXT_FULL: return 2;
        case StopReason::CANCELLED:    return 3;
        case StopReason::ERROR:        return 4;
    }
    return 4;
}

}  // namespace

extern "C" {

/*
 * Every export below is a function try block.
 *
 * A C++ exception must not cross a JNI frame: it unwinds into the runtime and aborts the
 * process, which no Kotlin runCatching can recover. Loading and generating were guarded
 * already, and the rest were not, though they all build std::string and std::vector, and
 * a phone with a model loaded is exactly a phone where the next allocation can fail.
 * Written as function try blocks so the bodies stay as they were.
 */
JNIEXPORT jstring JNICALL
Java_io_github_alpharomercoma_openweights_core_engine_LlamaBridge_nativeSystemInfo(
    JNIEnv * env, jobject /*thiz*/) try {
    openweights::init_backend();
    return to_jstring(env, openweights::system_info());
}
catch (const std::exception & failure) {
    throw_engine_exception(env, std::string("nativeSystemInfo failed: ") + failure.what());
    return nullptr;
} catch (...) {
    throw_engine_exception(env, "nativeSystemInfo failed for an unknown reason");
    return nullptr;
}

/**
 * Compute devices this phone offers, flattened as
 * `[id, description, type, totalMemoryBytes]` per device so Settings can list them
 * without a second JNI type.
 */
JNIEXPORT jobjectArray JNICALL
Java_io_github_alpharomercoma_openweights_core_engine_LlamaBridge_nativeComputeDevices(
    JNIEnv * env, jobject /*thiz*/) try {
    const auto devices = openweights::compute_devices();
    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray result =
        env->NewObjectArray(static_cast<jsize>(devices.size() * 4), string_class, nullptr);

    for (size_t i = 0; i < devices.size(); ++i) {
        const auto & device = devices[i];
        const std::string fields[4] = {
            device.id,
            device.description,
            std::to_string(device.type),
            std::to_string(device.total_memory),
        };
        for (jsize field = 0; field < 4; ++field) {
            jstring value = to_jstring(env, fields[field]);
            env->SetObjectArrayElement(result, static_cast<jsize>(i) * 4 + field, value);
            env->DeleteLocalRef(value);
        }
    }
    return result;
}
catch (const std::exception & failure) {
    throw_engine_exception(env, std::string("nativeComputeDevices failed: ") + failure.what());
    return nullptr;
} catch (...) {
    throw_engine_exception(env, "nativeComputeDevices failed for an unknown reason");
    return nullptr;
}

JNIEXPORT jlong JNICALL
Java_io_github_alpharomercoma_openweights_core_engine_LlamaBridge_nativeLoadModel(
    JNIEnv * env,
    jobject /*thiz*/,
    jstring model_path,
    jstring mmproj_path,
    jint context_length,
    jint thread_count,
    jint batch_thread_count,
    jint gpu_layers,
    jboolean use_mmap) try {
    std::string error;
    Session * session = Session::load(
        to_utf8(env, model_path),
        mmproj_path == nullptr ? std::string() : to_utf8(env, mmproj_path),
        context_length, thread_count, batch_thread_count,
        gpu_layers, use_mmap == JNI_TRUE, error);
    if (session == nullptr) {
        throw_engine_exception(env, error);
        return 0;
    }
    return reinterpret_cast<jlong>(session);
}
// A function try block, so the body below is untouched: C++ exceptions must not
// cross a JNI frame. An escaping std::bad_alloc, which is what a phone under memory
// pressure produces while a reply grows, unwinds into the runtime and aborts the
// process. Turned into an exception Kotlin already knows how to show, a low memory
// device fails loading the model and says so instead of vanishing.
catch (const std::exception & failure) {
    throw_engine_exception(env, std::string("loading the model failed: ") + failure.what());
    return 0;
} catch (...) {
    throw_engine_exception(env, "loading the model failed for an unknown reason");
    return 0;
}

JNIEXPORT void JNICALL
Java_io_github_alpharomercoma_openweights_core_engine_LlamaBridge_nativeFreeModel(
    JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    delete as_session(handle);
}

JNIEXPORT void JNICALL
Java_io_github_alpharomercoma_openweights_core_engine_LlamaBridge_nativeResetContext(
    JNIEnv * env, jobject /*thiz*/, jlong handle) try {
    as_session(handle)->reset();
}
catch (const std::exception & failure) {
    throw_engine_exception(env, std::string("nativeResetContext failed: ") + failure.what());
    return;
} catch (...) {
    throw_engine_exception(env, "nativeResetContext failed for an unknown reason");
    return;
}

JNIEXPORT void JNICALL
Java_io_github_alpharomercoma_openweights_core_engine_LlamaBridge_nativeSetThreads(
    JNIEnv * env, jobject /*thiz*/, jlong handle, jint threads, jint batch_threads) try {
    as_session(handle)->set_threads(threads, batch_threads);
}
catch (const std::exception & failure) {
    throw_engine_exception(env, std::string("nativeSetThreads failed: ") + failure.what());
    return;
} catch (...) {
    throw_engine_exception(env, "nativeSetThreads failed for an unknown reason");
    return;
}

JNIEXPORT void JNICALL
Java_io_github_alpharomercoma_openweights_core_engine_LlamaBridge_nativeCancel(
    JNIEnv * env, jobject /*thiz*/, jlong handle) {
    // Callable while nativeGenerate is running on another thread.
    as_session(handle)->cancel();
}

JNIEXPORT jlongArray JNICALL
Java_io_github_alpharomercoma_openweights_core_engine_LlamaBridge_nativeModelInfo(
    JNIEnv * env, jobject /*thiz*/, jlong handle) try {
    Session * session = as_session(handle);
    jlong values[6] = {
        static_cast<jlong>(session->parameter_count()),
        static_cast<jlong>(session->model_size_bytes()),
        static_cast<jlong>(session->context_size()),
        static_cast<jlong>(session->training_context_size()),
        static_cast<jlong>(session->layer_count()),
        static_cast<jlong>(session->context_used()),
    };
    jlongArray result = env->NewLongArray(6);
    env->SetLongArrayRegion(result, 0, 6, values);
    return result;
}
catch (const std::exception & failure) {
    throw_engine_exception(env, std::string("nativeModelInfo failed: ") + failure.what());
    return nullptr;
} catch (...) {
    throw_engine_exception(env, "nativeModelInfo failed for an unknown reason");
    return nullptr;
}

JNIEXPORT jstring JNICALL
Java_io_github_alpharomercoma_openweights_core_engine_LlamaBridge_nativeOffloadSummary(
    JNIEnv * env, jobject /*thiz*/, jlong handle) try {
    return to_jstring(env, as_session(handle)->offload_summary());
}
catch (const std::exception & failure) {
    throw_engine_exception(env, std::string("nativeOffloadSummary failed: ") + failure.what());
    return nullptr;
} catch (...) {
    throw_engine_exception(env, "nativeOffloadSummary failed for an unknown reason");
    return nullptr;
}

JNIEXPORT jstring JNICALL
Java_io_github_alpharomercoma_openweights_core_engine_LlamaBridge_nativeModelDescription(
    JNIEnv * env, jobject /*thiz*/, jlong handle) try {
    return to_jstring(env, as_session(handle)->model_description());
}
catch (const std::exception & failure) {
    throw_engine_exception(env, std::string("nativeModelDescription failed: ") + failure.what());
    return nullptr;
} catch (...) {
    throw_engine_exception(env, "nativeModelDescription failed for an unknown reason");
    return nullptr;
}

/** Returns [vision, audio]: what the loaded projector can accept. */
JNIEXPORT jbooleanArray JNICALL
Java_io_github_alpharomercoma_openweights_core_engine_LlamaBridge_nativeMediaSupport(
    JNIEnv * env, jobject /*thiz*/, jlong handle) try {
    const auto support = as_session(handle)->media_support();
    jboolean values[2] = {
        static_cast<jboolean>(support.vision),
        static_cast<jboolean>(support.audio),
    };
    jbooleanArray result = env->NewBooleanArray(2);
    env->SetBooleanArrayRegion(result, 0, 2, values);
    return result;
}
catch (const std::exception & failure) {
    throw_engine_exception(env, std::string("nativeMediaSupport failed: ") + failure.what());
    return nullptr;
} catch (...) {
    throw_engine_exception(env, "nativeMediaSupport failed for an unknown reason");
    return nullptr;
}

/** True when the loaded chat template understands being told whether to think. */
JNIEXPORT jboolean JNICALL
Java_io_github_alpharomercoma_openweights_core_engine_LlamaBridge_nativeSupportsThinking(
    JNIEnv * env, jobject /*thiz*/, jlong handle) try {
    return as_session(handle)->supports_thinking() ? JNI_TRUE : JNI_FALSE;
}
catch (const std::exception & failure) {
    throw_engine_exception(env, std::string("nativeSupportsThinking failed: ") + failure.what());
    return JNI_FALSE;
} catch (...) {
    throw_engine_exception(env, "nativeSupportsThinking failed for an unknown reason");
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_alpharomercoma_openweights_core_engine_LlamaBridge_nativeSupportsTools(
    JNIEnv * env, jobject /*thiz*/, jlong handle) try {
    return as_session(handle)->supports_tools() ? JNI_TRUE : JNI_FALSE;
}
catch (const std::exception & failure) {
    throw_engine_exception(env, std::string("nativeSupportsTools failed: ") + failure.what());
    return JNI_FALSE;
} catch (...) {
    throw_engine_exception(env, "nativeSupportsTools failed for an unknown reason");
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_alpharomercoma_openweights_core_engine_LlamaBridge_nativeSupportsToolResults(
    JNIEnv * env, jobject /*thiz*/, jlong handle) try {
    return as_session(handle)->supports_tool_results() ? JNI_TRUE : JNI_FALSE;
}
catch (const std::exception & failure) {
    throw_engine_exception(
        env, std::string("nativeSupportsToolResults failed: ") + failure.what());
    return JNI_FALSE;
} catch (...) {
    throw_engine_exception(env, "nativeSupportsToolResults failed for an unknown reason");
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_alpharomercoma_openweights_core_engine_LlamaBridge_nativeSupportsReasoningEffort(
    JNIEnv * env, jobject /*thiz*/, jlong handle) try {
    return as_session(handle)->supports_reasoning_effort() ? JNI_TRUE : JNI_FALSE;
}
catch (const std::exception & failure) {
    throw_engine_exception(env, std::string("nativeSupportsReasoningEffort failed: ") + failure.what());
    return JNI_FALSE;
} catch (...) {
    throw_engine_exception(env, "nativeSupportsReasoningEffort failed for an unknown reason");
    return JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_io_github_alpharomercoma_openweights_core_engine_LlamaBridge_nativeMediaMarker(
    JNIEnv * env, jobject /*thiz*/, jlong handle) try {
    return to_jstring(env, as_session(handle)->media_marker());
}
catch (const std::exception & failure) {
    throw_engine_exception(env, std::string("nativeMediaMarker failed: ") + failure.what());
    return nullptr;
} catch (...) {
    throw_engine_exception(env, "nativeMediaMarker failed for an unknown reason");
    return nullptr;
}

/**
 * Runs one generation, calling back into Kotlin for every token.
 *
 * Blocking by design: the caller runs it on a dedicated thread, so the JNIEnv handed to
 * this method stays valid for the callbacks and no thread attachment is needed.
 *
 * Returns [stopReasonOrdinal, promptTokens, generatedTokens, prefillMs, decodeMs,
 * timeToFirstTokenMs, contextUsed, contextSize].
 */
JNIEXPORT jlongArray JNICALL
Java_io_github_alpharomercoma_openweights_core_engine_LlamaBridge_nativeGenerate(
    JNIEnv * env,
    jobject /*thiz*/,
    jlong handle,
    jobjectArray roles,
    jobjectArray contents,
    jobjectArray tool_call_ids,
    jobjectArray media_paths,
    jintArray media_counts,
    jfloat temperature,
    jint top_k,
    jfloat top_p,
    jfloat min_p,
    jfloat repeat_penalty,
    jint repeat_last_n,
    jint seed,
    jint max_tokens,
    jobjectArray tool_names,
    jobjectArray tool_descriptions,
    jobjectArray tool_schemas,
    jboolean enable_thinking,
    jstring reasoning_effort,
    jobject token_sink,
    jobject reply_sink) try {
    Session * session = as_session(handle);

    const jsize message_count = env->GetArrayLength(roles);

    // Attachments arrive as one flat array plus a per-message count, which keeps the JNI
    // surface to plain arrays instead of an array of arrays.
    std::vector<jint> counts(message_count, 0);
    if (media_counts != nullptr) {
        env->GetIntArrayRegion(media_counts, 0, message_count, counts.data());
    }

    std::vector<ChatMessage> messages;
    messages.reserve(message_count);
    jsize media_cursor = 0;
    for (jsize i = 0; i < message_count; ++i) {
        auto role = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto content = static_cast<jstring>(env->GetObjectArrayElement(contents, i));

        std::vector<std::string> attachments;
        for (jint media = 0; media < counts[i]; ++media, ++media_cursor) {
            auto path = static_cast<jstring>(
                env->GetObjectArrayElement(media_paths, media_cursor));
            attachments.push_back(to_utf8(env, path));
            env->DeleteLocalRef(path);
        }

        auto call_id = static_cast<jstring>(env->GetObjectArrayElement(tool_call_ids, i));
        messages.push_back(
            {to_utf8(env, role), to_utf8(env, content), to_utf8(env, call_id), attachments});
        env->DeleteLocalRef(call_id);
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
    }

    const jsize tool_count = tool_names != nullptr ? env->GetArrayLength(tool_names) : 0;
    std::vector<ToolDefinition> tools;
    tools.reserve(tool_count);
    for (jsize i = 0; i < tool_count; ++i) {
        auto name = static_cast<jstring>(env->GetObjectArrayElement(tool_names, i));
        auto description = static_cast<jstring>(env->GetObjectArrayElement(tool_descriptions, i));
        auto schema = static_cast<jstring>(env->GetObjectArrayElement(tool_schemas, i));
        tools.push_back({to_utf8(env, name), to_utf8(env, description), to_utf8(env, schema)});
        env->DeleteLocalRef(name);
        env->DeleteLocalRef(description);
        env->DeleteLocalRef(schema);
    }

    SamplerConfig sampler;
    sampler.temperature    = temperature;
    sampler.top_k          = top_k;
    sampler.top_p          = top_p;
    sampler.min_p          = min_p;
    sampler.repeat_penalty = repeat_penalty;
    sampler.repeat_last_n  = repeat_last_n;
    sampler.seed           = static_cast<uint32_t>(seed);
    sampler.max_tokens     = max_tokens;

    jclass sink_class = env->GetObjectClass(token_sink);
    jmethodID on_token = env->GetMethodID(sink_class, "onToken", "(Ljava/lang/String;)Z");
    if (on_token == nullptr) {
        throw_engine_exception(env, "TokenSink.onToken not found");
        return nullptr;
    }

    jclass reply_class = env->GetObjectClass(reply_sink);
    jmethodID on_reply = env->GetMethodID(
        reply_class, "onReply", "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)V");
    if (on_reply == nullptr) {
        throw_engine_exception(env, "ReplySink.onReply not found");
        return nullptr;
    }

    GenerationStats stats;
    ParsedReply reply;
    std::string error;
    openweights::ReasoningConfig thinking;
    thinking.enabled = enable_thinking == JNI_TRUE;
    thinking.effort =
        reasoning_effort == nullptr ? std::string() : to_utf8(env, reasoning_effort);

    const StopReason reason = session->generate(
        messages, tools, sampler, thinking,
        [&](const char * piece) -> bool {
            jstring text = to_jstring(env, piece);
            const jboolean keep_going = env->CallBooleanMethod(token_sink, on_token, text);
            env->DeleteLocalRef(text);
            // A Kotlin-side exception must stop generation immediately.
            if (env->ExceptionCheck() == JNI_TRUE) {
                return false;
            }
            return keep_going == JNI_TRUE;
        },
        stats, reply, error);

    if (env->ExceptionCheck() == JNI_TRUE) {
        return nullptr;
    }

    // Tool calls flattened as [id, name, argumentsJson] triples, so the reply crosses JNI
    // without a bespoke object type on either side.
    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray calls = env->NewObjectArray(
        static_cast<jsize>(reply.tool_calls.size() * 3), string_class, nullptr);
    for (size_t i = 0; i < reply.tool_calls.size(); ++i) {
        const std::string fields[3] = {
            reply.tool_calls[i].id,
            reply.tool_calls[i].name,
            reply.tool_calls[i].arguments_json,
        };
        for (jsize field = 0; field < 3; ++field) {
            jstring value = to_jstring(env, fields[field]);
            env->SetObjectArrayElement(calls, static_cast<jsize>(i) * 3 + field, value);
            env->DeleteLocalRef(value);
        }
    }

    jstring content = to_jstring(env, reply.content);
    jstring reasoning = to_jstring(env, reply.reasoning);
    env->CallVoidMethod(reply_sink, on_reply, content, reasoning, calls);
    env->DeleteLocalRef(content);
    env->DeleteLocalRef(reasoning);
    env->DeleteLocalRef(calls);
    if (reason == StopReason::ERROR) {
        throw_engine_exception(env, error);
        return nullptr;
    }

    jlong values[8] = {
        stop_reason_ordinal(reason),
        stats.prompt_tokens,
        stats.generated_tokens,
        stats.prefill_ms,
        stats.decode_ms,
        stats.time_to_first_token_ms,
        stats.context_used,
        stats.context_size,
    };
    jlongArray result = env->NewLongArray(8);
    env->SetLongArrayRegion(result, 0, 8, values);
    return result;
}
// A function try block, so the body below is untouched: C++ exceptions must not
// cross a JNI frame. An escaping std::bad_alloc, which is what a phone under memory
// pressure produces while a reply grows, unwinds into the runtime and aborts the
// process. Turned into an exception Kotlin already knows how to show, a low memory
// device fails generation and says so instead of vanishing.
catch (const std::exception & failure) {
    throw_engine_exception(env, std::string("generation failed: ") + failure.what());
    return nullptr;
} catch (...) {
    throw_engine_exception(env, "generation failed for an unknown reason");
    return nullptr;
}

}  // extern "C"
