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

// The whole surface between Kotlin and MNN's diffusion engine.
//
// Deliberately narrow, and narrow in the same way the sandbox's AIDL is: everything crossing
// here is a primitive or a string this app chose, one handle stands for one loaded model,
// and nothing on the other side can ask for more. A wider surface would mean marshalling
// MNN's own types, which changes between releases of a library that is vendored rather than
// depended on.

#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <memory>
#include <string>
#include <cstdint>
#include <cstdio>
#include <vector>
#include <diffusion/diffusion.hpp>
#include <supertonic/mnn_supertonic_tts_impl.hpp>

#define LOG_TAG "OpenWeightsGen"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#include <mutex>
#include <unordered_map>

namespace {

/**
 * Thrown out of the progress callback to stop a run between steps.
 *
 * MNN's Stable Diffusion loop has no cancellation hook: `run` takes a `std::function<void(int)>`
 * that returns nothing, so there is no value a callback can return that means stop. Throwing
 * from inside it is the only place a caller can interrupt the loop at all.
 *
 * That is safe here and would not be safe everywhere. The denoising loop holds its state in
 * `VARP`, which is a reference-counted handle, and in `std::shared_ptr` members of the
 * Diffusion object, so unwinding through it releases what it holds. It is caught at this
 * boundary and never crosses back into Java as an exception.
 *
 * What it does not do is stop the step already running. A stop lands at the next boundary,
 * which on a phone is the length of one denoising step.
 */
struct Cancelled {};

/**
 * One loaded model, and the flag that can stop it.
 *
 * Not called Session. `diffusion.hpp` puts `using namespace MNN;` at global scope, and MNN
 * has a `Session` of its own, so the obvious name is ambiguous at every use.
 *
 * The flag lives with the session rather than on the object being cancelled, because MNN's
 * Diffusion has no idea it is being used from a language with a stop button. `std::atomic`
 * because it is set from whichever thread the user tapped on and read on the one generating.
 */
struct GenerationSession {
    std::unique_ptr<MNN::DIFFUSION::Diffusion> diffusion;
    std::atomic<bool> cancelled{false};
    std::string backend;
};

struct SpeechSession {
    std::unique_ptr<MNNSupertonicTTSImpl> tts;
    int sampleRate = 0;
};

std::mutex gSessionMutex;
std::unordered_map<jlong, std::shared_ptr<GenerationSession>> gActiveSessions;
std::unordered_map<jlong, std::shared_ptr<SpeechSession>> gActiveSpeechSessions;

std::shared_ptr<GenerationSession> getSession(jlong handle) {
    if (handle == 0) return nullptr;
    std::lock_guard<std::mutex> guard(gSessionMutex);
    auto it = gActiveSessions.find(handle);
    return it != gActiveSessions.end() ? it->second : nullptr;
}

std::shared_ptr<SpeechSession> getSpeechSession(jlong handle) {
    if (handle == 0) return nullptr;
    std::lock_guard<std::mutex> guard(gSessionMutex);
    auto it = gActiveSpeechSessions.find(handle);
    return it != gActiveSpeechSessions.end() ? it->second : nullptr;
}

void appendUtf8(std::string & out, uint32_t code) {
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

std::string to_utf8(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const jsize length = env->GetStringLength(value);
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result;
    result.reserve(static_cast<size_t>(length) * 2);
    for (jsize i = 0; i < length; ++i) {
        uint32_t code = chars[i];
        if (code >= 0xD800 && code <= 0xDBFF) {
            if (i + 1 < length && chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF) {
                code = 0x10000 + ((code - 0xD800) << 10) + (chars[i + 1] - 0xDC00);
                if (code > 0x10FFFF) code = 0xFFFD;
                ++i;
            } else {
                code = 0xFFFD;
            }
        } else if (code >= 0xDC00 && code <= 0xDFFF) {
            code = 0xFFFD;
        }
        appendUtf8(result, code);
    }
    env->ReleaseStringChars(value, chars);
    return result;
}

/**
 * Writes 16-bit mono PCM as a RIFF/WAVE file.
 *
 * Written here rather than through MNN's `wavfile.hpp`, because the length in samples is
 * needed on the way past: a gallery that sorts by duration cannot ask a file how long it is
 * without decoding it, and the one moment the answer is free is while the samples are in
 * hand. Little-endian throughout, which is what WAVE is and what every device this runs on
 * already is.
 */
bool writeWav(const std::string& path, const std::vector<int16_t>& samples, int sampleRate) {
    FILE* file = std::fopen(path.c_str(), "wb");
    if (file == nullptr) return false;

    const uint32_t dataBytes = static_cast<uint32_t>(samples.size() * sizeof(int16_t));
    const uint32_t riffSize = 36 + dataBytes;
    const uint16_t channels = 1;
    const uint16_t bitsPerSample = 16;
    const uint32_t byteRate = static_cast<uint32_t>(sampleRate) * channels * bitsPerSample / 8;
    const uint16_t blockAlign = channels * bitsPerSample / 8;
    const uint16_t pcm = 1;
    const uint32_t fmtSize = 16;

    bool ok = std::fwrite("RIFF", 1, 4, file) == 4;
    ok = ok && std::fwrite(&riffSize, 4, 1, file) == 1;
    ok = ok && std::fwrite("WAVEfmt ", 1, 8, file) == 8;
    ok = ok && std::fwrite(&fmtSize, 4, 1, file) == 1;
    ok = ok && std::fwrite(&pcm, 2, 1, file) == 1;
    ok = ok && std::fwrite(&channels, 2, 1, file) == 1;
    ok = ok && std::fwrite(&sampleRate, 4, 1, file) == 1;
    ok = ok && std::fwrite(&byteRate, 4, 1, file) == 1;
    ok = ok && std::fwrite(&blockAlign, 2, 1, file) == 1;
    ok = ok && std::fwrite(&bitsPerSample, 2, 1, file) == 1;
    ok = ok && std::fwrite("data", 1, 4, file) == 4;
    ok = ok && std::fwrite(&dataBytes, 4, 1, file) == 1;
    if (ok && dataBytes > 0) {
        ok = std::fwrite(samples.data(), 1, dataBytes, file) == dataBytes;
    }
    if (std::ferror(file) != 0 || std::fclose(file) != 0) ok = false;
    if (!ok) std::remove(path.c_str());
    return ok;
}

} // namespace

extern "C" {

/**
 * Loads a bundle and returns a handle, or 0.
 *
 * `backendType` is what was asked for. What actually ran is reported separately, because on
 * a phone those are different questions: a request for OpenCL on a device whose driver will
 * not create a context falls back to the CPU, and an app that reported the request as the
 * answer would be publishing a number for a backend that never ran.
 */
JNIEXPORT jlong JNICALL
Java_io_github_alpharomercoma_openweights_core_generation_mnn_NativeMnn_nativeLoad(
    JNIEnv* env, jobject, jstring modelPath, jint modelType, jint backendType, jint memoryMode) {
    const std::string path = to_utf8(env, modelPath);
    if (path.empty()) {
        LOGE("refusing to load a bundle with no path");
        return 0;
    }

    try {
        auto session = std::make_shared<GenerationSession>();
        session->diffusion.reset(MNN::DIFFUSION::Diffusion::createDiffusion(
            path,
            static_cast<MNN::DIFFUSION::DiffusionModelType>(modelType),
            static_cast<MNNForwardType>(backendType),
            memoryMode));
        if (!session->diffusion) {
            LOGE("MNN would not create a diffusion for %s", path.c_str());
            return 0;
        }
        if (!session->diffusion->load()) {
            LOGE("MNN would not load the bundle at %s", path.c_str());
            return 0;
        }
        session->backend = backendType == MNN_FORWARD_OPENCL ? "OpenCL" : "CPU";
        LOGI("loaded a diffusion bundle from %s", path.c_str());
        jlong handle = reinterpret_cast<jlong>(session.get());
        {
            std::lock_guard<std::mutex> guard(gSessionMutex);
            gActiveSessions[handle] = session;
        }
        return handle;
    } catch (const std::exception& failure) {
        LOGE("MNN failed to initialize diffusion bundle: %s", failure.what());
        return 0;
    } catch (...) {
        LOGE("MNN failed to initialize diffusion bundle with unknown error");
        return 0;
    }
}

/**
 * Runs one generation, calling back per step, and says whether a file was written.
 *
 * Returns 0 on success, 1 when it was cancelled, and 2 when the runtime refused. Three
 * outcomes rather than a boolean, because "stopped by the user" and "did not work" are
 * different things to everything above this: one is reported and one is not, and only one
 * of them is worth showing an error for.
 */
JNIEXPORT jint JNICALL
Java_io_github_alpharomercoma_openweights_core_generation_mnn_NativeMnn_nativeGenerate(
    JNIEnv* env, jobject self, jlong handle, jstring prompt, jstring outputPath,
    jint steps, jint seed) {
    if (steps < 1 || steps > 100) return 2;
    std::shared_ptr<GenerationSession> session = getSession(handle);
    if (!session || !session->diffusion) return 2;

    session->cancelled.store(false);

    jclass cls = env->GetObjectClass(self);
    jmethodID onStep = cls != nullptr ? env->GetMethodID(cls, "onNativeStep", "(I)V") : nullptr;
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (cls != nullptr) env->DeleteLocalRef(cls);

    const std::string promptText = to_utf8(env, prompt);
    const std::string output = to_utf8(env, outputPath);

    try {
        const bool wrote = session->diffusion->run(
            promptText, output, steps, seed,
            [&](int step) {
                if (session->cancelled.load()) throw Cancelled{};
                if (onStep != nullptr) {
                    if (env->PushLocalFrame(32) == 0) {
                        env->CallVoidMethod(self, onStep, step);
                        if (env->ExceptionCheck()) env->ExceptionClear();
                        env->PopLocalFrame(nullptr);
                    }
                }
            });
        return wrote ? 0 : 2;
    } catch (const Cancelled&) {
        LOGI("a generation was stopped between steps");
        return 1;
    } catch (const std::exception& failure) {
        LOGE("a generation threw: %s", failure.what());
        return 2;
    } catch (...) {
        LOGE("a generation threw something with no message");
        return 2;
    }
}

/** Asks the run on this handle to stop at its next step boundary. */
JNIEXPORT void JNICALL
Java_io_github_alpharomercoma_openweights_core_generation_mnn_NativeMnn_nativeCancel(
    JNIEnv*, jobject, jlong handle) {
    std::shared_ptr<GenerationSession> session = getSession(handle);
    if (session) session->cancelled.store(true);
}

/** What actually ran, which is not always what was asked for. */
JNIEXPORT jstring JNICALL
Java_io_github_alpharomercoma_openweights_core_generation_mnn_NativeMnn_nativeBackend(
    JNIEnv* env, jobject, jlong handle) {
    std::shared_ptr<GenerationSession> session = getSession(handle);
    return env->NewStringUTF(session ? session->backend.c_str() : "");
}

/** Releases one handle. Safe to call twice; the caller clears its own copy. */
JNIEXPORT void JNICALL
Java_io_github_alpharomercoma_openweights_core_generation_mnn_NativeMnn_nativeRelease(
    JNIEnv*, jobject, jlong handle) {
    if (handle == 0) return;
    std::shared_ptr<GenerationSession> session;
    {
        std::lock_guard<std::mutex> guard(gSessionMutex);
        auto it = gActiveSessions.find(handle);
        if (it != gActiveSessions.end()) {
            session = it->second;
            gActiveSessions.erase(it);
        }
    }
    if (session) session->cancelled.store(true);
}

/**
 * Loads a voice bundle and returns a handle, or 0.
 *
 * Supertonic reads a directory: its four models, an indexer and the voice styles. Which
 * files are missing is checked in Kotlin before this is called, because the answer here to
 * anything wrong is a throw with a message written for a C++ developer.
 */
JNIEXPORT jlong JNICALL
Java_io_github_alpharomercoma_openweights_core_generation_mnn_NativeMnn_nativeLoadVoice(
    JNIEnv* env, jobject, jstring modelsDir, jstring speakerId) {
    const std::string dir = to_utf8(env, modelsDir);
    if (dir.empty()) return 0;

    try {
        auto session = std::make_shared<SpeechSession>();
        session->tts = std::make_unique<MNNSupertonicTTSImpl>(dir);
        const std::string speaker = to_utf8(env, speakerId);
        if (!speaker.empty()) session->tts->SetSpeakerId(speaker);
        jlong handle = reinterpret_cast<jlong>(session.get());
        {
            std::lock_guard<std::mutex> guard(gSessionMutex);
            gActiveSpeechSessions[handle] = session;
        }
        return handle;
    } catch (const std::exception& failure) {
        LOGE("a voice would not load: %s", failure.what());
        return 0;
    } catch (...) {
        LOGE("a voice would not load, with no message");
        return 0;
    }
}

/**
 * Speaks [text] into a WAV file and returns its length in samples, or a negative code.
 *
 * -1 for a runtime that refused, -2 for a file that could not be written. Samples rather
 * than a boolean because the duration is wanted above and this is the one place it is free.
 */
JNIEXPORT jint JNICALL
Java_io_github_alpharomercoma_openweights_core_generation_mnn_NativeMnn_nativeSpeak(
    JNIEnv* env, jobject, jlong handle, jstring text, jstring outputPath) {
    std::shared_ptr<SpeechSession> session = getSpeechSession(handle);
    if (!session || !session->tts) return -1;

    try {
        const auto [rate, samples] = session->tts->Process(to_utf8(env, text));
        if (samples.empty()) {
            LOGE("a voice produced no audio");
            return -1;
        }
        session->sampleRate = rate;
        if (!writeWav(to_utf8(env, outputPath), samples, rate)) return -2;
        return static_cast<jint>(samples.size());
    } catch (const std::exception& failure) {
        LOGE("speaking threw: %s", failure.what());
        return -1;
    } catch (...) {
        LOGE("speaking threw something with no message");
        return -1;
    }
}

/** The rate the last utterance came back at, which the file header also carries. */
JNIEXPORT jint JNICALL
Java_io_github_alpharomercoma_openweights_core_generation_mnn_NativeMnn_nativeSampleRate(
    JNIEnv*, jobject, jlong handle) {
    std::shared_ptr<SpeechSession> session = getSpeechSession(handle);
    return session ? session->sampleRate : 0;
}

/** Chooses among the voices the weights contain. */
JNIEXPORT void JNICALL
Java_io_github_alpharomercoma_openweights_core_generation_mnn_NativeMnn_nativeSetSpeaker(
    JNIEnv* env, jobject, jlong handle, jstring speakerId) {
    std::shared_ptr<SpeechSession> session = getSpeechSession(handle);
    if (!session || !session->tts) return;
    const std::string speaker = to_utf8(env, speakerId);
    if (!speaker.empty()) {
        try {
            session->tts->SetSpeakerId(speaker);
        } catch (const std::exception& failure) {
            LOGE("failed to set speaker %s: %s", speaker.c_str(), failure.what());
        } catch (...) {
            LOGE("failed to set speaker %s with unknown error", speaker.c_str());
        }
    }
}

/** Releases one voice handle. */
JNIEXPORT void JNICALL
Java_io_github_alpharomercoma_openweights_core_generation_mnn_NativeMnn_nativeReleaseVoice(
    JNIEnv*, jobject, jlong handle) {
    if (handle == 0) return;
    std::shared_ptr<SpeechSession> session;
    {
        std::lock_guard<std::mutex> guard(gSessionMutex);
        auto it = gActiveSpeechSessions.find(handle);
        if (it != gActiveSpeechSessions.end()) {
            session = it->second;
            gActiveSpeechSessions.erase(it);
        }
    }
}

} // extern "C"
