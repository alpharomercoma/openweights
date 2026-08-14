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

package io.github.alpharomercoma.openweights.core.sandbox

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * The far side of the boundary, running with nothing.
 *
 * Everything this class can do, it can do because the app handed it over in the binder call.
 * It has no permissions, no storage and no network of its own, which is what the manifest's
 * `isolatedProcess` buys and the reason a crash here is a crash and not an incident.
 *
 * There is no state between calls on purpose. A run gets a new runtime and the runtime is
 * destroyed with it, so the second script cannot see what the first one left, and a script
 * that wedges its interpreter takes nothing with it.
 */
class ScriptService : Service() {
    private val runner = object : IScriptRunner.Stub() {
        override fun run(
            source: String,
            inputsJson: String,
            memoryBytes: Long,
            millis: Long,
            outputLimit: Int,
        ): String {
            // Whatever happens in here, something has to come back across the binder. A
            // linkage failure or an interpreter that dies mid-parse would otherwise reach
            // the app as a DeadObjectException with nothing in it to tell the model.
            val result = runCatching {
                QuickJs().run(
                    source = source,
                    inputsJson = inputsJson,
                    memoryBytes = memoryBytes,
                    stackBytes = STACK_BYTES,
                    millis = millis,
                    outputLimit = outputLimit,
                )
            }.getOrElse { failure ->
                ScriptResult("the interpreter stopped: ${failure.message}", failed = true)
            }
            return result.encode()
        }
    }

    override fun onBind(intent: Intent?): IBinder = runner

    private companion object {
        /**
         * The interpreter's own stack, well under the thread's.
         *
         * Recursion is the ordinary way a generated script dies, and QuickJS unwinding on
         * its own limit produces a stack overflow the model can read, while running into
         * the real thread limit produces a native crash that takes the process with it.
         */
        const val STACK_BYTES = 512L * 1024
    }
}
