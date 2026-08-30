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

package io.github.alpharomercoma.openweights.engine

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.engine.LlamaCppEngine
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/**
 * What happens when a speculative-decoding draft head is loaded as if it were a model.
 *
 * `LFM2.5-1.2B-Instruct-DSpark` is not a chat model. Its GGUF says `general.architecture =
 * dflash`, `size_label = 296M`, `tags = [speculative-decoding, dspark]`, and it carries
 * neither `token_embd` nor `output.weight` — it borrows both from the target model it drafts
 * for. llama.cpp guards exactly this in `llama-context.cpp`: an `eagle3` or `dflash` model
 * missing those tensors throws unless `ctx_other`, a context for the target, is supplied.
 *
 * The app reported that as "context length may be too large for this device", which is a
 * wrong answer given confidently: nothing about the context length was the problem, and a
 * user following that advice lowers it and fails again. This records what the engine really
 * says so the message can be written from evidence rather than from a guess.
 */
class DraftModelOnDeviceTest {
    @Test
    fun whatLoadingADraftHeadActuallySays() {
        runBlocking {
            val models = File(
                InstrumentationRegistry.getInstrumentation().targetContext
                    .getExternalFilesDir(null),
                "models",
            )
            val draft = models.listFiles()
                ?.firstOrNull { it.name.contains("DSpark") && it.extension == "gguf" }
            if (draft == null) {
                Log.w(TAG, "SKIPPED no DSpark draft model in ${models.absolutePath}")
                return@runBlocking
            }

            val engine = LlamaCppEngine()
            val outcome = runCatching {
                engine.load(draft, ModelLoadParams(contextLength = 4096))
            }
            Log.i(TAG, "load(${draft.name}) -> $outcome")
            outcome.exceptionOrNull()?.let { Log.i(TAG, "message: ${it.message}") }
            runCatching { engine.close() }
        }
    }

    private companion object {
        const val TAG = "OpenWeightsDraft"
    }
}
