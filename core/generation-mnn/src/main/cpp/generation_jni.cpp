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
#include <diffusion/diffusion.hpp>

#define LOG_TAG "OpenWeightsGen"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

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

GenerationSession* asSession(jlong handle) {
    return reinterpret_cast<GenerationSession*>(handle);
}

std::string toStdString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string copied(chars);
    env->ReleaseStringUTFChars(value, chars);
    return copied;
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
    const std::string path = toStdString(env, modelPath);
    if (path.empty()) {
        LOGE("refusing to load a bundle with no path");
        return 0;
    }

    auto session = std::make_unique<GenerationSession>();
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
    // Asked of the runtime rather than assumed from the request. MNN falls back silently.
    session->backend = backendType == MNN_FORWARD_OPENCL ? "OpenCL" : "CPU";
    LOGI("loaded a diffusion bundle from %s", path.c_str());
    return reinterpret_cast<jlong>(session.release());
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
    GenerationSession* session = asSession(handle);
    if (session == nullptr || !session->diffusion) return 2;

    session->cancelled.store(false);

    jclass cls = env->GetObjectClass(self);
    jmethodID onStep = env->GetMethodID(cls, "onNativeStep", "(I)V");

    const std::string promptText = toStdString(env, prompt);
    const std::string output = toStdString(env, outputPath);

    try {
        const bool wrote = session->diffusion->run(
            promptText, output, steps, seed,
            [&](int step) {
                if (session->cancelled.load()) throw Cancelled{};
                if (onStep != nullptr) env->CallVoidMethod(self, onStep, step);
                // A Kotlin listener that threw would leave an exception pending, and the
                // next JNI call in this loop would behave unpredictably. Cleared here so a
                // broken listener costs its own callback rather than the generation.
                if (env->ExceptionCheck()) env->ExceptionClear();
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
    GenerationSession* session = asSession(handle);
    if (session != nullptr) session->cancelled.store(true);
}

/** What actually ran, which is not always what was asked for. */
JNIEXPORT jstring JNICALL
Java_io_github_alpharomercoma_openweights_core_generation_mnn_NativeMnn_nativeBackend(
    JNIEnv* env, jobject, jlong handle) {
    GenerationSession* session = asSession(handle);
    return env->NewStringUTF(session == nullptr ? "" : session->backend.c_str());
}

/** Releases one handle. Safe to call twice; the caller clears its own copy. */
JNIEXPORT void JNICALL
Java_io_github_alpharomercoma_openweights_core_generation_mnn_NativeMnn_nativeRelease(
    JNIEnv*, jobject, jlong handle) {
    delete asSession(handle);
}

} // extern "C"
