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

// Runs one script and throws the whole world away afterwards.
//
// A runtime per call rather than a reused one. Reuse would be faster and would also mean a
// script could leave something behind for the next one, which is the kind of state nobody
// reasons about correctly. These run for a few milliseconds; the setup is not the cost.
//
// Nothing here reaches a file, a socket or a clock beyond the deadline it is given, because
// none of those functions are linked into the binary. The only globals a script sees are
// the ones built below.

#include <jni.h>

#include <chrono>
#include <string>

extern "C" {
#include "quickjs.h"
}

namespace {

/** What a run is allowed to spend, and what it has produced so far. */
struct Run {
    std::chrono::steady_clock::time_point deadline;
    std::string output;
    size_t outputLimit;
};

/**
 * Asked by QuickJS between operations, and the only thing that stops a loop with no end.
 *
 * A memory limit does not catch `while (true) {}`: that allocates nothing. Returning
 * non-zero here unwinds the interpreter, which is the one mechanism that does.
 */
int interrupted(JSRuntime *runtime, void *opaque) {
    (void) runtime;
    auto *run = static_cast<Run *>(opaque);
    return std::chrono::steady_clock::now() >= run->deadline ? 1 : 0;
}

/** Appends to the run's output, stopping at the cap rather than growing without end. */
void append(Run *run, const std::string &text) {
    if (run->output.size() >= run->outputLimit) {
        return;
    }
    const size_t room = run->outputLimit - run->output.size();
    run->output.append(text, 0, room);
}

/**
 * `console.log`, because a model writes it whether or not anything defines it.
 *
 * Without this, the first thing most generated scripts do is throw "console is not
 * defined", which teaches the model nothing about its actual mistake. It writes into the
 * run's buffer and nowhere else: there is no stdout to reach from here.
 */
JSValue consoleLog(JSContext *context, JSValueConst self, int argc, JSValueConst *argv) {
    (void) self;
    auto *run = static_cast<Run *>(JS_GetContextOpaque(context));
    std::string line;
    for (int i = 0; i < argc; i++) {
        const char *piece = JS_ToCString(context, argv[i]);
        if (piece == nullptr) {
            continue;
        }
        if (i > 0) {
            line += ' ';
        }
        line += piece;
        JS_FreeCString(context, piece);
    }
    line += '\n';
    append(run, line);
    return JS_UNDEFINED;
}

/** The value a script ended on, as text a model can read. */
std::string resultOf(JSContext *context, JSValue value) {
    if (JS_IsUndefined(value)) {
        return "";
    }
    // JSON first, so an object comes back as its contents rather than [object Object].
    JSValue json = JS_JSONStringify(context, value, JS_UNDEFINED, JS_UNDEFINED);
    const char *text = JS_IsException(json) ? nullptr : JS_ToCString(context, json);
    std::string out;
    if (text != nullptr) {
        out = text;
        JS_FreeCString(context, text);
    } else {
        const char *plain = JS_ToCString(context, value);
        if (plain != nullptr) {
            out = plain;
            JS_FreeCString(context, plain);
        }
    }
    JS_FreeValue(context, json);
    return out;
}

/** Whatever went wrong, phrased the way the model will read it back. */
std::string failureOf(JSContext *context) {
    JSValue error = JS_GetException(context);
    std::string message;
    const char *text = JS_ToCString(context, error);
    if (text != nullptr) {
        message = text;
        JS_FreeCString(context, text);
    }
    JSValue stack = JS_GetPropertyStr(context, error, "stack");
    if (!JS_IsUndefined(stack) && !JS_IsException(stack)) {
        const char *trace = JS_ToCString(context, stack);
        if (trace != nullptr && *trace != '\0') {
            message += "\n";
            message += trace;
        }
        if (trace != nullptr) {
            JS_FreeCString(context, trace);
        }
    }
    JS_FreeValue(context, stack);
    JS_FreeValue(context, error);
    return message.empty() ? "the script failed without saying why" : message;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_alpharomercoma_openweights_core_sandbox_QuickJs_nativeRun(
    JNIEnv *env,
    jobject,
    jstring sourceIn,
    jstring inputsJsonIn,
    jlong memoryBytes,
    jlong stackBytes,
    jlong millis,
    jint outputLimit,
    jbooleanArray failedOut) {
    const char *source = env->GetStringUTFChars(sourceIn, nullptr);
    const char *inputsJson = env->GetStringUTFChars(inputsJsonIn, nullptr);

    Run run{
        std::chrono::steady_clock::now() + std::chrono::milliseconds(millis),
        std::string(),
        static_cast<size_t>(outputLimit),
    };

    JSRuntime *runtime = JS_NewRuntime();
    JS_SetMemoryLimit(runtime, static_cast<size_t>(memoryBytes));
    JS_SetMaxStackSize(runtime, static_cast<size_t>(stackBytes));
    JS_SetInterruptHandler(runtime, interrupted, &run);

    JSContext *context = JS_NewContext(runtime);
    JS_SetContextOpaque(context, &run);

    JSValue global = JS_GetGlobalObject(context);
    JSValue console = JS_NewObject(context);
    JS_SetPropertyStr(context, console, "log",
                      JS_NewCFunction(context, consoleLog, "log", 1));
    JS_SetPropertyStr(context, global, "console", console);

    // The named files the app chose to hand over, already read and bounded on the Kotlin
    // side. Parsed rather than concatenated into the source, so a file whose contents look
    // like code stays data.
    JSValue inputs = JS_ParseJSON(context, inputsJson, strlen(inputsJson), "<inputs>");
    JS_SetPropertyStr(context, global, "inputs",
                      JS_IsException(inputs) ? JS_NewObject(context) : inputs);
    JS_FreeValue(context, global);

    JSValue value = JS_Eval(context, source, strlen(source), "<script>", JS_EVAL_TYPE_GLOBAL);

    jboolean failed = JS_IsException(value) ? JNI_TRUE : JNI_FALSE;
    std::string report = failed ? failureOf(context) : resultOf(context, value);
    if (failed == JNI_FALSE && !run.output.empty()) {
        report = report.empty() ? run.output : run.output + report;
    }

    JS_FreeValue(context, value);
    JS_FreeContext(context);
    JS_FreeRuntime(runtime);

    env->ReleaseStringUTFChars(sourceIn, source);
    env->ReleaseStringUTFChars(inputsJsonIn, inputsJson);
    env->SetBooleanArrayRegion(failedOut, 0, 1, &failed);
    return env->NewStringUTF(report.c_str());
}
