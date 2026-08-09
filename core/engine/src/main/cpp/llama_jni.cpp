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

std::string to_utf8(JNIEnv * env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char * chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars != nullptr ? chars : "");
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(value, chars);
    }
    return result;
}

void throw_engine_exception(JNIEnv * env, const std::string & message) {
    jclass clazz = env->FindClass("io/github/alpharomercoma/openweights/core/engine/LlamaException");
    if (clazz != nullptr) {
        env->ThrowNew(clazz, message.c_str());
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

JNIEXPORT jstring JNICALL
Java_io_github_alpharomercoma_openweights_core_engine_LlamaBridge_nativeSystemInfo(
    JNIEnv * env, jobject /*thiz*/) {
    openweights::init_backend();
    return env->NewStringUTF(openweights::system_info().c_str());
}

/**
 * Compute devices this phone offers, flattened as
 * `[id, description, type, totalMemoryBytes]` per device so Settings can list them
 * without a second JNI type.
 */
JNIEXPORT jobjectArray JNICALL
Java_io_github_alpharomercoma_openweights_core_engine_LlamaBridge_nativeComputeDevices(
    JNIEnv * env, jobject /*thiz*/) {
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
            jstring value = env->NewStringUTF(fields[field].c_str());
            env->SetObjectArrayElement(result, static_cast<jsize>(i) * 4 + field, value);
            env->DeleteLocalRef(value);
        }
    }
    return result;
}

JNIEXPORT jlong JNICALL
Java_io_github_alpharomercoma_openweights_core_engine_LlamaBridge_nativeLoadModel(
    JNIEnv * env,
    jobject /*thiz*/,
    jstring model_path,
    jint context_length,
    jint thread_count,
    jint batch_thread_count,
    jint gpu_layers,
    jboolean use_mmap) {
    std::string error;
    Session * session = Session::load(
        to_utf8(env, model_path), context_length, thread_count, batch_thread_count,
        gpu_layers, use_mmap == JNI_TRUE, error);
    if (session == nullptr) {
        throw_engine_exception(env, error);
        return 0;
    }
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL
Java_io_github_alpharomercoma_openweights_core_engine_LlamaBridge_nativeFreeModel(
    JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    delete as_session(handle);
}

JNIEXPORT void JNICALL
Java_io_github_alpharomercoma_openweights_core_engine_LlamaBridge_nativeResetContext(
    JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    as_session(handle)->reset();
}

JNIEXPORT void JNICALL
Java_io_github_alpharomercoma_openweights_core_engine_LlamaBridge_nativeCancel(
    JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    // Deliberately callable while nativeGenerate is running on another thread.
    as_session(handle)->cancel();
}

JNIEXPORT jlongArray JNICALL
Java_io_github_alpharomercoma_openweights_core_engine_LlamaBridge_nativeModelInfo(
    JNIEnv * env, jobject /*thiz*/, jlong handle) {
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

JNIEXPORT jstring JNICALL
Java_io_github_alpharomercoma_openweights_core_engine_LlamaBridge_nativeModelDescription(
    JNIEnv * env, jobject /*thiz*/, jlong handle) {
    return env->NewStringUTF(as_session(handle)->model_description().c_str());
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
    jobject token_sink,
    jobject reply_sink) {
    Session * session = as_session(handle);

    const jsize message_count = env->GetArrayLength(roles);
    std::vector<ChatMessage> messages;
    messages.reserve(message_count);
    for (jsize i = 0; i < message_count; ++i) {
        auto role = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto content = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
        messages.push_back({to_utf8(env, role), to_utf8(env, content), ""});
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
    const StopReason reason = session->generate(
        messages, tools, sampler,
        [&](const char * piece) -> bool {
            jstring text = env->NewStringUTF(piece);
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
            jstring value = env->NewStringUTF(fields[field].c_str());
            env->SetObjectArrayElement(calls, static_cast<jsize>(i) * 3 + field, value);
            env->DeleteLocalRef(value);
        }
    }

    jstring content = env->NewStringUTF(reply.content.c_str());
    jstring reasoning = env->NewStringUTF(reply.reasoning.c_str());
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

}  // extern "C"
