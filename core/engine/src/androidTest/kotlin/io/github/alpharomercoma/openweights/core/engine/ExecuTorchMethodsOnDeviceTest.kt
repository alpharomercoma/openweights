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

package io.github.alpharomercoma.openweights.core.engine

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.pytorch.executorch.Module
import org.pytorch.executorch.extension.llm.LlmModule
import java.io.File

/**
 * Prints what a compiled file says about itself: its method names and what [ExecuTorchBridge.probe]
 * makes of them. Point it at a file with `-e pte <path>`; the answer is in logcat under
 * `OpenWeightsMethods`. This is how a new publisher's export is judged feedable or not before
 * any template is written for it.
 */
@RunWith(AndroidJUnit4::class)
class ExecuTorchMethodsOnDeviceTest {
    @Test
    fun listsTheMethods() {
        val path = InstrumentationRegistry.getArguments().getString("pte")
        assumeTrue("pass -e pte <path>", path != null && File(path).isFile)
        val program = Module.load(path, Module.LOAD_MODE_MMAP)
        val methods = program.getMethods().toList()
        Log.i(TAG, "methods $methods")
        Log.i(TAG, "probe ${NativeExecuTorchBridge().probe(path!!)}")
        program.destroy()

        // With `-e tokenizer <path>` as well, the multimodal runner is asked to open it: the
        // runner looks methods up by fixed names, so an export from an older exporter that
        // named them differently is refused here, whatever its method list says.
        val tokenizer = InstrumentationRegistry.getArguments().getString("tokenizer") ?: return
        val module = LlmModule(LlmModule.MODEL_TYPE_MULTIMODAL, path, tokenizer, 0.1f)
        val loaded = runCatching { module.load() }
        Log.i(TAG, "multimodal load ok=${loaded.isSuccess} ${loaded.exceptionOrNull()?.message}")
        val prefilled = runCatching { module.prefillPrompt("Hi") }
        Log.i(TAG, "prefill ok=${prefilled.isSuccess} ${prefilled.exceptionOrNull()?.message}")
        module.resetNative()
    }

    private companion object {
        const val TAG = "OpenWeightsMethods"
    }
}
