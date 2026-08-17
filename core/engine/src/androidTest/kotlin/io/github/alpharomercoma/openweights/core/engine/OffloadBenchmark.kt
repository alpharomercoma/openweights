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
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What offloading to the GPU is worth, and where the two processors change places.
 *
 * The published figures for this chip come from llama-bench, which measures reading and
 * writing separately. A turn does both, in a ratio that depends entirely on what the user
 * asked for: a chat turn reads a short question and writes a long answer, and a turn that
 * searched reads a conversation plus a page of results and writes a paragraph. The second
 * is the one every published benchmark leaves out and the one this app is for.
 *
 * Both are measured here, through the engine that ships. What this prints that a table of
 * wall clocks does not is the **crossover**, the prompt-to-answer ratio at which the two
 * processors cost the same wall time. `Offload.AUTO` is a threshold on exactly that number,
 * so the benchmark should produce it rather than leave it to be solved by hand off a table.
 *
 * For a turn of P prompt tokens and G generated tokens the wall time is `P/pp + G/tg`, so
 * the GPU is worth it when
 *
 * ```
 * P/G  >  (1/tg_gpu - 1/tg_cpu) / (1/pp_cpu - 1/pp_gpu)
 * ```
 *
 * and that right-hand side is the crossover. It also prints the **break-even turn count**,
 * which the ratio on its own hides: committing to the GPU costs a slower load plus a one-off
 * OpenCL warm-up on the first prefill, and a turn shape that is only just past the crossover
 * saves so little per turn that the session ends before that is repaid.
 *
 * Every model in `/data/local/tmp/openweights` ending `.gguf` is measured, because the
 * crossover is a property of the model rather than of the phone and one number for the
 * device would be the mistake this exists to catch.
 */
@RunWith(AndroidJUnit4::class)
class OffloadBenchmark {
    private val modelDir = File("/data/local/tmp/openweights")

    @Test
    fun solvesTheCrossoverForEveryModelPushed() = runBlocking {
        val models = modelDir.listFiles { file -> file.name.endsWith(".gguf") }
            ?.filterNot { it.name.contains("mmproj") }
            ?.sortedBy { it.name }
            .orEmpty()
        assumeTrue("no .gguf in $modelDir", models.isNotEmpty())
        val gpuPresent = LlamaCppEngine().use { engine ->
            engine.computeDevices().any { it.kind == ComputeDeviceKind.GPU }
        }
        assumeTrue("no GPU backend on this device", gpuPresent)

        for (model in models) {
            val cpu = measure(model, layers = 0)
            val gpu = measure(model, layers = ALL_LAYERS)
            report(model.name, cpu, gpu)
        }
    }

    /**
     * One backend, measured warm.
     *
     * The warm-up turn is the whole reason this is not two `chat` calls: the first prefill
     * after a GPU load carries about two seconds of OpenCL kernel building, which lands
     * inside `prefillMs` and makes the prompt rate read several times too slow. Charging it
     * to a throwaway turn leaves the two measured turns describing throughput, and the cost
     * is not lost, it is reported separately as the warm-up it is.
     *
     * Prefill rate comes from the agent turn because a sixteen-token prompt is too short to
     * divide by, and decode rate from the chat turn because that is the one that writes
     * enough tokens for the rate to settle.
     */
    private suspend fun measure(model: File, layers: Int): Backend {
        val where = if (layers == 0) "cpu" else "gpu"
        return LlamaCppEngine().use { engine ->
            // Timed, because this is what switching processors costs: llama.cpp assigns
            // layers when the weights are mapped, so changing the choice means paying this
            // again. The GPU pays for kernel setup here as well.
            val startedAt = System.currentTimeMillis()
            engine.load(model, ModelLoadParams(contextLength = CONTEXT, gpuLayers = layers))
            val loadMs = System.currentTimeMillis() - startedAt

            val cold = engine.turn(WARM_UP_PROMPT, WARM_UP_ANSWER)
            engine.resetContext()

            val chat = engine.turn("Explain what a KV cache is.", LONG_ANSWER)
            engine.resetContext()

            // A conversation and a page of search results to re-read, then a paragraph. The
            // prompt is what a second round actually looks like.
            val agent = engine.turn(agentPrompt(), SHORT_ANSWER)
            engine.resetContext()

            // The same prompt as the throwaway turn, against the same empty cache, so the
            // only thing separating the two figures is how much of the backend was already
            // built the second time.
            val warm = engine.turn(WARM_UP_PROMPT, WARM_UP_ANSWER)

            Backend(
                name = where,
                loadMs = loadMs,
                // What the first prefill paid over the same prompt read warm. On the CPU
                // this is close to zero; on the GPU it is the kernel build.
                warmUpMs = (cold.prefillMs - warm.prefillMs).coerceAtLeast(0),
                promptPerSecond = agent.promptTokens * MILLIS /
                    agent.prefillMs.coerceAtLeast(1).toDouble(),
                generatePerSecond = chat.generatedTokens * MILLIS /
                    chat.decodeMs.coerceAtLeast(1).toDouble(),
                chatWallMs = chat.prefillMs + chat.decodeMs,
                agentWallMs = agent.prefillMs + agent.decodeMs,
            ).also { Log.i(TAG, "${model.name} $it") }
        }
    }

    private fun report(model: String, cpu: Backend, gpu: Backend) {
        // Seconds per token, which is what the two costs are actually made of. Written this
        // way round because the difference of two rates is not a rate.
        val decodePenalty = 1 / gpu.generatePerSecond - 1 / cpu.generatePerSecond
        val prefillGain = 1 / cpu.promptPerSecond - 1 / gpu.promptPerSecond

        // Four cases, and only one of them is a threshold. The shipped constant assumes the
        // third, that the GPU trades reading for writing, which is true of the transformers
        // this was first measured on and not true of everything.
        val verdict = when {
            prefillGain <= 0 && decodePenalty >= 0 ->
                "GPU slower at both, never worth it"
            prefillGain > 0 && decodePenalty <= 0 ->
                "GPU faster at both, worth it on any shape once the load is repaid"
            prefillGain > 0 ->
                "prompt > %.2fx answer".format(decodePenalty / prefillGain)
            else ->
                "prompt < %.2fx answer".format(decodePenalty / prefillGain)
        }
        val switchMs = gpu.loadMs - cpu.loadMs + gpu.warmUpMs - cpu.warmUpMs

        // What each shape of turn saves, and therefore how many of them repay the switch.
        // Both are reported because a threshold that is right about the ratio can still be
        // wrong about whether the user ever gets the seconds back.
        val agentSaving = AGENT_PROMPT_TOKENS * prefillGain - SHORT_ANSWER * decodePenalty
        val chatSaving = CHAT_PROMPT_TOKENS * prefillGain - LONG_ANSWER * decodePenalty

        Log.i(
            TAG,
            (
                "%s CROSSOVER: %s | switch costs %d ms (load %+d, warm-up %+d) | " +
                    "agent turn %+.2f s, break-even %s | chat turn %+.2f s, break-even %s"
                )
                .format(
                    model,
                    verdict,
                    switchMs,
                    gpu.loadMs - cpu.loadMs,
                    gpu.warmUpMs - cpu.warmUpMs,
                    agentSaving,
                    breakEven(switchMs, agentSaving),
                    chatSaving,
                    breakEven(switchMs, chatSaving),
                ),
        )
        // A backend that failed to attach loads onto the CPU and still reports a rate, so
        // the guard is that both ends measured something rather than that either won.
        assertThat(cpu.promptPerSecond).isGreaterThan(0.0)
        assertThat(gpu.promptPerSecond).isGreaterThan(0.0)
    }

    /** How many turns of this shape repay the switch, or "never" when it does not pay. */
    private fun breakEven(switchMs: Long, savingSeconds: Double): String =
        if (savingSeconds > 0) "%.1f turns".format(switchMs / MILLIS / savingSeconds) else "never"

    private suspend fun InferenceEngine.turn(prompt: String, maxTokens: Int): GenerationStats {
        val completed = chat(
            messages = listOf(ChatMessage.text(ChatRole.USER, prompt)),
            params = SamplerParams(temperature = 0f, maxTokens = maxTokens, seed = 1),
        ).toList().filterIsInstance<GenerationEvent.Completed>().single()
        assertThat(completed.stats.promptTokens).isGreaterThan(0)
        return completed.stats
    }

    /** A conversation plus search results, which is what the second pass of a tool turn reads. */
    private fun agentPrompt(): String = buildString {
        append("Here is what the search returned. Answer the question from it.\n\n")
        repeat(RESULT_BLOCKS) { index ->
            append("[").append(index + 1).append("] Result ").append(index + 1).append('\n')
            append(
                "Ada Lovelace worked with Charles Babbage on the Analytical Engine and is " +
                    "credited with the first published algorithm intended for a machine. " +
                    "She saw that such a machine might act on things other than number. ",
            )
            append('\n')
        }
        append("\nQuestion: who was Ada Lovelace, in two sentences?")
    }

    /** One backend's throughput, warm, plus what getting it warm cost. */
    private data class Backend(
        val name: String,
        val loadMs: Long,
        val warmUpMs: Long,
        val promptPerSecond: Double,
        val generatePerSecond: Double,
        val chatWallMs: Long,
        val agentWallMs: Long,
    ) {
        override fun toString(): String =
            "%s: load=%dms warm-up=%dms %.1f pp/s %.1f tg/s chat=%dms agent=%dms".format(
                name,
                loadMs,
                warmUpMs,
                promptPerSecond,
                generatePerSecond,
                chatWallMs,
                agentWallMs,
            )
    }

    private companion object {
        const val TAG = "OpenWeightsOffload"
        const val CONTEXT = 4096
        const val ALL_LAYERS = 99
        const val LONG_ANSWER = 300
        const val SHORT_ANSWER = 120
        const val MILLIS = 1000.0

        /** Enough blocks to land near two thousand tokens, which is a real second pass. */
        const val RESULT_BLOCKS = 40

        /** Roughly what [RESULT_BLOCKS] tokenizes to, for the break-even arithmetic. */
        const val AGENT_PROMPT_TOKENS = 2000

        /** A question with a little conversation behind it, for the same arithmetic. */
        const val CHAT_PROMPT_TOKENS = 40

        const val WARM_UP_PROMPT = "Say hello."
        const val WARM_UP_ANSWER = 8
    }
}
