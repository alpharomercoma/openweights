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

import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Everything about [ExecuTorchEngine] that does not need the runtime.
 *
 * Which is most of it: building the prompt, splitting reasoning from the answer, lifting
 * tool calls out, and refusing to load when it cannot do those things correctly. The parts
 * that genuinely need a phone are the `.pte` opening and the arithmetic inside it.
 */
class ExecuTorchEngineTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val bridge = FakeExecuTorchBridge()
    private val engine = ExecuTorchEngine(bridge)

    @Test
    fun `refuses a model with no tokenizer beside it`() = runTest {
        val model = folder.newFile("Qwen3-1.7B.pte")

        val failure = runCatching { engine.load(model, PARAMS) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(LlamaException::class.java)
        assertThat(failure).hasMessageThat().contains("no tokenizer")
        // Nothing was opened, so nothing is holding memory.
        assertThat(bridge.loadedModelPath).isNull()
    }

    @Test
    fun `refuses a model family it cannot render a prompt for`() = runTest {
        val model = installed("Mystery-7B.pte")

        val failure = runCatching { engine.load(model, PARAMS) }.exceptionOrNull()

        // Loading it anyway would produce a model that answers slightly wrongly forever,
        // which is far harder to notice than a refusal at load.
        assertThat(failure).isInstanceOf(LlamaException::class.java)
        assertThat(failure).hasMessageThat().contains("No prompt template")
        assertThat(failure).hasMessageThat().contains("Qwen3")
    }

    @Test
    fun `surfaces a runtime that will not open the file`() = runTest {
        bridge.opens = false

        val failure = runCatching { engine.load(installed(MODEL), PARAMS) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(LlamaException::class.java)
        assertThat(engine.loadedModel).isNull()
    }

    @Test
    fun `pairs the model with the tokenizer exported beside it`() = runTest {
        val model = installed(MODEL)

        engine.load(model, PARAMS)

        assertThat(bridge.loadedModelPath).isEqualTo(model.absolutePath)
        assertThat(bridge.loadedTokenizerPath)
            .isEqualTo(File(model.parentFile, "Qwen3-1.7B.tokenizer.json").absolutePath)
    }

    @Test
    fun `builds the prompt with the model's own template`() = runTest {
        engine.load(installed(MODEL), PARAMS)

        engine.chat(listOf(user("What is the capital of Japan?"))).first()

        assertThat(bridge.lastPrompt).isEqualTo(
            "<|im_start|>user\nWhat is the capital of Japan?<|im_end|>\n<|im_start|>assistant\n",
        )
    }

    @Test
    fun `closes the thinking block in the opener when reasoning is switched off`() = runTest {
        engine.load(installed(MODEL), PARAMS)

        engine.chat(listOf(user("Hi")), SamplerParams(thinking = false)).first()

        assertThat(bridge.lastPrompt).endsWith("<|im_start|>assistant\n<think>\n\n</think>\n\n")
    }

    @Test
    fun `splits reasoning from the answer`() = runTest {
        bridge.reply = "<think>\nTokyo is the capital.\n</think>\n\nTokyo."
        engine.load(installed(MODEL), PARAMS)

        val done = engine.chat(listOf(user("Capital of Japan?"))).first()

        require(done is GenerationEvent.Completed)
        assertThat(done.reasoning).isEqualTo("Tokyo is the capital.")
        assertThat(done.content).isEqualTo("Tokyo.")
    }

    @Test
    fun `lifts a tool call out of the reply`() = runTest {
        bridge.reply = "<tool_call>\n{\"name\": \"web_search\", " +
            "\"arguments\": {\"query\": \"Manila weather\"}}\n</tool_call>"
        engine.load(installed(MODEL), PARAMS)

        val done = engine.chat(listOf(user("Weather in Manila?"))).first()

        require(done is GenerationEvent.Completed)
        assertThat(done.toolCalls).hasSize(1)
        assertThat(done.toolCalls.first().name).isEqualTo("web_search")
        // The envelope must not survive into what the user reads.
        assertThat(done.content).doesNotContain("tool_call")
    }

    @Test
    fun `clears the runtime's carried position before every turn`() = runTest {
        engine.load(installed(MODEL), PARAMS)

        engine.chat(listOf(user("Hi"))).first()
        engine.chat(listOf(user("Hi"), user("Again"))).first()

        // Two turns, two resets. Without them the runtime appends the conversation behind
        // the copy it already holds and runs out of window; measured on device, turn two
        // was refused for exceeding a 2048-token context with 2068 more tokens.
        assertThat(bridge.contextResets).isEqualTo(2)
    }

    @Test
    fun `reports no cached tokens, because nothing is carried between turns`() = runTest {
        bridge.outcome = ExecuTorchOutcome(StopReason.END_OF_TURN, promptTokens = 120)
        engine.load(installed(MODEL), PARAMS)

        val done = engine.chat(listOf(user("Hi"))).first()

        require(done is GenerationEvent.Completed)
        // Zero here is the truth rather than a missing number: ExecuTorch re-prefills the
        // whole conversation every turn, so a follow-up saves nothing.
        assertThat(done.stats.cachedTokens).isEqualTo(0)
        assertThat(done.stats.cacheHitRate).isEqualTo(0.0)
    }

    @Test
    fun `asks for the whole window when the caller set no limit`() = runTest {
        engine.load(installed(MODEL), ModelLoadParams(contextLength = 4096))

        engine.chat(listOf(user("Hi")), SamplerParams(maxTokens = 0)).first()

        // Zero means "no limit" to llama.cpp; passing it straight through would ask
        // ExecuTorch to generate nothing at all.
        assertThat(bridge.lastMaxNewTokens).isEqualTo(4096)
        // And the window itself reaches the runtime, which counts in total sequence length
        // and cannot work out a new-token allowance without it.
        assertThat(bridge.loadedContextLength).isEqualTo(4096)
    }

    @Test
    fun `releases the previous model before opening another`() = runTest {
        engine.load(installed(MODEL), PARAMS)
        engine.unload()

        assertThat(bridge.closed).isTrue()
        assertThat(engine.loadedModel).isNull()
    }

    /** A `.pte` with the tokenizer that was exported beside it. */
    private fun installed(name: String): File {
        val model = folder.newFile(name)
        folder.newFile(model.nameWithoutExtension + ".tokenizer.json")
        return model
    }

    private fun user(text: String) = ChatMessage.text(ChatRole.USER, text)

    private companion object {
        const val MODEL = "Qwen3-1.7B.pte"
        val PARAMS = ModelLoadParams(contextLength = 4096)
    }
}
