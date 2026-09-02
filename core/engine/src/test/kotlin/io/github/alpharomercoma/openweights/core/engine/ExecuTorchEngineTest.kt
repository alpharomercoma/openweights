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
import io.github.alpharomercoma.openweights.core.common.model.ToolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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

        engine.chat(listOf(user("What is the capital of Japan?"))).completed()

        assertThat(bridge.lastPrompt).isEqualTo(
            "<|im_start|>user\nWhat is the capital of Japan?<|im_end|>\n<|im_start|>assistant\n",
        )
    }

    @Test
    fun `closes the thinking block in the opener when reasoning is switched off`() = runTest {
        engine.load(installed(MODEL), PARAMS)

        engine.chat(listOf(user("Hi")), SamplerParams(thinking = false)).completed()

        assertThat(bridge.lastPrompt).endsWith("<|im_start|>assistant\n<think>\n\n</think>\n\n")
    }

    @Test
    fun `splits reasoning from the answer`() = runTest {
        bridge.reply = "<think>\nTokyo is the capital.\n</think>\n\nTokyo."
        engine.load(installed(MODEL), PARAMS)

        val done = engine.chat(listOf(user("Capital of Japan?"))).completed()

        assertThat(done.reasoning).isEqualTo("Tokyo is the capital.")
        assertThat(done.content).isEqualTo("Tokyo.")
    }

    @Test
    fun `keeps a closer with no opener as text`() = runTest {
        bridge.reply = "The block ends with </think> and nothing more."
        engine.load(installed(MODEL), PARAMS)

        val done = engine.chat(listOf(user("Which tag closes it?"))).completed()

        assertThat(done.content).isEqualTo("The block ends with </think> and nothing more.")
        assertThat(done.reasoning).isEmpty()
    }

    @Test
    fun `honours a stop that lands before the runtime starts`() = runTest {
        // Between rendering the prompt and asking for tokens there is a window, and a Stop
        // in it used to be wiped by the flag reset that preceded generation. The runtime
        // clears its own stop when its loop starts, so the flag was the only record.
        bridge.reply = "One two three four five six seven eight nine ten."
        bridge.onResetContext = { engine.cancel() }
        engine.load(installed(MODEL), PARAMS)

        val done = engine.chat(listOf(user("Count."))).completed()

        assertThat(done.reason).isEqualTo(StopReason.CANCELLED)
        assertThat(done.content.length).isLessThan(bridge.reply.length)
    }

    @Test
    fun `lifts a tool call out of the reply`() = runTest {
        bridge.reply = "<tool_call>\n{\"name\": \"web_search\", " +
            "\"arguments\": {\"query\": \"Manila weather\"}}\n</tool_call>"
        engine.load(installed(MODEL), PARAMS)

        val done = engine.chat(listOf(user("Weather in Manila?"))).completed()

        assertThat(done.toolCalls).hasSize(1)
        assertThat(done.toolCalls.first().name).isEqualTo("web_search")
        // The envelope must not survive into what the user reads.
        assertThat(done.content).doesNotContain("tool_call")
    }

    @Test
    fun `feeds only the new turn when the conversation merely grew`() = runTest {
        bridge.reply = "Hello.<|im_end|>"
        engine.load(installed(MODEL), PARAMS)

        val first = listOf(user("Hi"))
        engine.chat(first).toList()
        val second = first + assistant("Hello.") + user("Again")
        engine.chat(second).toList()

        // One reset, for the first turn. The second extends what the runtime already holds,
        // so it must not be cleared and must not be re-sent: this runtime appends rather
        // than matching a prefix, so re-sending is a second copy, not a cache hit.
        assertThat(bridge.contextResets).isEqualTo(1)
        assertThat(bridge.prompts).hasSize(2)
        assertThat(bridge.prompts[1]).doesNotContain("<|im_start|>user\nHi<|im_end|>")
        assertThat(bridge.prompts[1]).contains("Again")
    }

    @Test
    fun `switching reasoning off costs the cache`() = runTest {
        // Counterintuitive and measured rather than reasoned: it is *disabling* reasoning
        // that breaks the cache, not enabling it. Qwen3 switches thinking off by closing an
        // empty <think> block in the assistant opener, so that text is fed and sits in the
        // cache — and the template never reproduces it when the same turn becomes history.
        // The next prompt is therefore not an extension, and the turn starts over.
        bridge.reply = "Hello.<|im_end|>"
        engine.load(installed(MODEL), PARAMS)

        val first = listOf(user("Hi"))
        engine.chat(first, NO_THINKING).toList()
        engine.chat(first + assistant("Hello.") + user("Again"), NO_THINKING).toList()

        assertThat(bridge.contextResets).isEqualTo(2)
    }

    @Test
    fun `keeps the cache across a reply that reasoned`() = runTest {
        // Upstream's template drops a reply's reasoning once a newer question arrives, which
        // would describe a conversation the runtime is not holding — its cache contains what
        // was actually generated, reasoning and all. This engine therefore renders history
        // verbatim, and this is the case that pays for that divergence: without it a model
        // that thinks can never reuse anything.
        bridge.reply = "<think>\nBecause.\n</think>\n\nHello.<|im_end|>"
        engine.load(installed(MODEL), PARAMS)

        val first = listOf(user("Hi"))
        engine.chat(first).toList()
        val stored = "<think>\nBecause.\n</think>\n\nHello."
        engine.chat(first + assistant(stored) + user("Again")).toList()

        assertThat(bridge.contextResets).isEqualTo(1)
        assertThat(bridge.prompts[1]).doesNotContain("Because.")
    }

    @Test
    fun `starts over when the tools on offer change`() = runTest {
        // The tool list lives in the system block at the very front of the prompt, and this
        // app withdraws tools when a turn's budget is spent. Everything after that moves, so
        // nothing already fed can be reused and the runtime has to be cleared.
        bridge.reply = "Hello.<|im_end|>"
        engine.load(installed(MODEL), PARAMS)

        val first = listOf(user("Hi"))
        engine.chat(first, tools = TOOLS).toList()
        engine.chat(first + assistant("Hello.") + user("Again"), tools = emptyList()).toList()

        assertThat(bridge.contextResets).isEqualTo(2)
    }

    @Test
    fun `starts over when an earlier message is edited`() = runTest {
        bridge.reply = "Hello.<|im_end|>"
        engine.load(installed(MODEL), PARAMS)

        engine.chat(listOf(user("Hi"))).toList()
        // Not an extension of anything: the conversation it was holding no longer exists.
        engine.chat(listOf(user("Hello there")) + assistant("Hello.") + user("Again")).toList()

        assertThat(bridge.contextResets).isEqualTo(2)
    }

    @Test
    fun `does not claim the runtime holds the token that ended generation`() = runTest {
        bridge.reply = "Hello.<|im_end|>"
        engine.load(installed(MODEL), PARAMS)

        val first = listOf(user("Hi"))
        engine.chat(first).toList()
        engine.chat(first + assistant("Hello.") + user("Again")).toList()

        // A sampled token only enters the KV cache when it is fed back in to produce the
        // next one, so the marker that ended generation never got there. Recording it would
        // make this turn skip feeding it, and the two turns would run together with nothing
        // between them — so the suffix must still carry the end-of-turn marker.
        assertThat(bridge.prompts[1]).startsWith("<|im_end|>")
    }

    @Test
    fun `gives up reuse when a reply ran out of budget instead of ending`() = runTest {
        // No marker means the last sampled token is ordinary text, and nothing here can say
        // which characters it was. Guessing would leave the record one token ahead of the
        // cache forever, so the next turn starts over instead.
        bridge.reply = "Hello, and I was still talking when"
        engine.load(installed(MODEL), PARAMS)

        val first = listOf(user("Hi"))
        engine.chat(first).toList()
        engine.chat(first + assistant("Hello, and I was still talking when") + user("Again"))
            .toList()

        assertThat(bridge.contextResets).isEqualTo(2)
    }

    @Test
    fun `flushes text it was holding back in case it became a marker`() = runTest {
        // Ends mid-marker, so the tail was withheld from the stream. If it is never flushed
        // the app stores less than was fed, and its history stops matching the cache.
        bridge.reply = "Careful <|im_"
        engine.load(installed(MODEL), PARAMS)

        val events = engine.chat(listOf(user("Hi"))).toList()

        val streamed = events.filterIsInstance<GenerationEvent.Token>().joinToString("") { it.text }
        assertThat(streamed).isEqualTo("Careful <|im_")
    }

    @Test
    fun `drops the cache when a generation fails partway`() = runTest {
        bridge.reply = "Hello.<|im_end|>"
        engine.load(installed(MODEL), PARAMS)
        val first = listOf(user("Hi"))
        engine.chat(first).toList()

        bridge.failsDuringGeneration = "runtime exploded"
        runCatching { engine.chat(first + assistant("Hello.") + user("Again")).toList() }

        // The runtime moved on by whatever it managed to prefill, so what was recorded no
        // longer describes it. A retry that trusted the old record would send the same text
        // at an already-advanced position and duplicate it.
        bridge.failsDuringGeneration = null
        val before = bridge.contextResets
        engine.chat(first + assistant("Hello.") + user("Again")).toList()
        assertThat(bridge.contextResets).isEqualTo(before + 1)
    }

    @Test
    fun `a stop mid-reply reaches the runtime and is reported as a cancellation`() = runTest {
        // The runtime clears its own stop flag when the token loop starts, so a Stop that
        // lands before the first token is erased; the engine re-issues it from inside the
        // callback. And the runtime always says "end of turn", so the reason has to be
        // decided here: a call parsed out of a stopped reply must not run.
        bridge.reply = "Let me think about that for a moment and then <tool_call>"
        engine.load(installed(MODEL), PARAMS)
        var fragments = 0
        bridge.beforeFragment = { if (++fragments == 2) engine.cancel() }

        val events = engine.chat(listOf(user("Hi")), NO_THINKING).toList()

        val done = events.filterIsInstance<GenerationEvent.Completed>().single()
        assertThat(done.reason).isEqualTo(StopReason.CANCELLED)
        assertThat(bridge.stopped).isTrue()
        assertThat(fragments).isLessThan(bridge.reply.length)
    }

    @Test
    fun `a warm waits for the turn that is running`() = runBlocking {
        // One thing at a time on the runtime. The app overlaps a fold's summary turn with
        // the warm queued behind the load; on this runtime two writers to the record and
        // the runtime's position at once fed text into the cache twice.
        bridge.reply = "one two three four five six seven eight nine ten"
        engine.load(installed(MODEL), PARAMS)
        val timeline = java.util.Collections.synchronizedList(mutableListOf<String>())
        val firstToken = java.util.concurrent.CountDownLatch(1)
        bridge.beforeFragment = {
            timeline += "token"
            firstToken.countDown()
            Thread.sleep(SLOW_FRAGMENT_MS)
        }
        bridge.onPrefill = { timeline += "prefill" }

        val turn = async(Dispatchers.Default) {
            engine.chat(listOf(user("Hi")), NO_THINKING).toList()
        }
        firstToken.await()
        val warm = async(Dispatchers.Default) {
            val head = ChatMessage.text(ChatRole.SYSTEM, "A".repeat(LONG_HEAD_CHARS))
            engine.warm(listOf(head), emptyList(), NO_THINKING)
        }
        turn.await()
        warm.await()

        assertThat(timeline).isNotEmpty()
        assertThat(timeline.contains("prefill")).isTrue()
        // Every token before any prefill: the warm did not start until the turn was over.
        assertThat(timeline.indexOf("prefill")).isEqualTo(timeline.lastIndexOf("token") + 1)
    }

    @Test
    fun `a reply cut for budget is reported as such, not as a finished turn`() = runTest {
        bridge.reply = "one two three four five six seven eight nine ten eleven twelve"
        engine.load(installed(MODEL), PARAMS)

        val events = engine.chat(listOf(user("Count")), NO_THINKING.copy(maxTokens = 3)).toList()

        val done = events.filterIsInstance<GenerationEvent.Completed>().single()
        assertThat(done.reason).isEqualTo(StopReason.MAX_TOKENS)
        assertThat(bridge.stopped).isTrue()
    }

    @Test
    fun `stops decoding at the end-of-turn marker`() = runTest {
        bridge.reply = "Tokyo.<|im_end|>and then some rambling"
        engine.load(installed(MODEL), PARAMS)

        val events = engine.chat(listOf(user("Capital?")), NO_THINKING).toList()

        // Nothing past the marker reaches the user, and the runtime was asked to stop:
        // every token after it is also written into the KV cache and would sit between
        // this turn and the next one for the rest of the conversation.
        val done = events.filterIsInstance<GenerationEvent.Completed>().single()
        assertThat(done.content).isEqualTo("Tokyo.")
        assertThat(bridge.stopped).isTrue()
        assertThat(events.filterIsInstance<GenerationEvent.Token>().joinToString("") { it.text })
            .isEqualTo("Tokyo.")
    }

    @Test
    fun `streams the reply rather than delivering it whole`() = runTest {
        bridge.reply = "One two three.<|im_end|>"
        engine.load(installed(MODEL), PARAMS)

        val events = engine.chat(listOf(user("Count")), NO_THINKING).toList()

        // The screen shows a reply as it arrives. Emitting only the terminal event left it
        // blank for the whole generation, which on a 1.7B model is several seconds.
        assertThat(events.filterIsInstance<GenerationEvent.Token>()).isNotEmpty()
        assertThat(events.last()).isInstanceOf(GenerationEvent.Completed::class.java)
    }

    @Test
    fun `reports no cached tokens, because nothing is carried between turns`() = runTest {
        bridge.outcome = ExecuTorchOutcome(StopReason.END_OF_TURN, promptTokens = 120)
        engine.load(installed(MODEL), PARAMS)

        val done = engine.chat(listOf(user("Hi"))).completed()

        // Zero here is the truth rather than a missing number: ExecuTorch re-prefills the
        // whole conversation every turn, so a follow-up saves nothing.
        assertThat(done.stats.cachedTokens).isEqualTo(0)
        assertThat(done.stats.cacheHitRate).isEqualTo(0.0)
    }

    @Test
    fun `asks for the whole window when the caller set no limit`() = runTest {
        engine.load(installed(MODEL), ModelLoadParams(contextLength = 4096))

        engine.chat(listOf(user("Hi")), SamplerParams(maxTokens = 0)).completed()

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
    @Test
    fun `a warm feeds the head in pieces and the first turn extends it`() = runTest {
        engine.load(installed(MODEL), PARAMS)
        val head = ChatMessage.text(ChatRole.SYSTEM, LONG_RULES)

        val warm = engine.warm(listOf(head), params = NO_THINKING)

        assertThat(warm).isNotNull()
        assertThat(warm!!.warmedTokens).isGreaterThan(0)
        // Long text goes in pieces, because a prefill call cannot be stopped and the
        // piece is the interrupt latency.
        assertThat(bridge.prefills.size).isGreaterThan(1)

        bridge.reply = "Hello."
        engine.chat(
            listOf(head, ChatMessage.text(ChatRole.USER, "hi")),
            NO_THINKING,
        ).toList()
        // The turn fed only what the warm had not: its own tail, never the rules again.
        assertThat(bridge.prompts.last()).contains("hi")
        assertThat(bridge.prompts.last()).doesNotContain("Rule 0:")
    }

    @Test
    fun `a warm equal to what is held reads nothing`() = runTest {
        engine.load(installed(MODEL), PARAMS)
        val head = ChatMessage.text(ChatRole.SYSTEM, LONG_RULES)
        engine.warm(listOf(head), params = NO_THINKING)
        bridge.prefills.clear()
        val resets = bridge.contextResets

        val again = engine.warm(listOf(head), params = NO_THINKING)

        assertThat(again).isNotNull()
        assertThat(again!!.warmedTokens).isEqualTo(0)
        assertThat(again.reusedTokens).isGreaterThan(0)
        assertThat(bridge.prefills).isEmpty()
        assertThat(bridge.contextResets).isEqualTo(resets)
    }

    @Test
    fun `a warm shorter than the cache resets, because this runtime cannot roll back`() = runTest {
        engine.load(installed(MODEL), PARAMS)
        val head = ChatMessage.text(ChatRole.SYSTEM, LONG_RULES)
        engine.warm(listOf(head), params = NO_THINKING)
        bridge.reply = "Hello."
        engine.chat(
            listOf(head, ChatMessage.text(ChatRole.USER, "hi")),
            NO_THINKING,
        ).toList()
        bridge.prefills.clear()
        val resets = bridge.contextResets

        val warm = engine.warm(listOf(head), params = NO_THINKING)

        assertThat(warm).isNotNull()
        assertThat(bridge.contextResets).isEqualTo(resets + 1)
        assertThat(bridge.prefills.joinToString("")).contains("Rule 0:")
    }

    @Test
    fun `a cancelled warm keeps its fed pieces and the next warm extends them`() = runTest {
        engine.load(installed(MODEL), PARAMS)
        val head = ChatMessage.text(ChatRole.SYSTEM, LONG_RULES)
        bridge.onPrefill = { if (bridge.prefills.size == 1) engine.cancel() }

        val interrupted = engine.warm(listOf(head), params = NO_THINKING)

        assertThat(interrupted).isNotNull()
        assertThat(bridge.prefills).hasSize(1)

        bridge.onPrefill = null
        val fedSoFar = bridge.prefills.single()
        bridge.prefills.clear()
        val resumed = engine.warm(listOf(head), params = NO_THINKING)

        // Nothing fed twice: the second warm begins exactly where the first stopped.
        assertThat(resumed).isNotNull()
        assertThat(resumed!!.reusedTokens).isGreaterThan(0)
        assertThat(bridge.prefills.joinToString("")).doesNotContain(fedSoFar.take(40))
    }

    @Test
    fun `a failed prefill concedes the record rather than guessing`() = runTest {
        engine.load(installed(MODEL), PARAMS)
        val head = ChatMessage.text(ChatRole.SYSTEM, LONG_RULES)
        bridge.failsDuringPrefill = "the runtime said no"
        val resets = bridge.contextResets

        val warm = engine.warm(listOf(head), params = NO_THINKING)

        assertThat(warm).isNull()
        assertThat(bridge.contextResets).isGreaterThan(resets)

        // The next turn starts over from nothing, which is slow and correct.
        bridge.failsDuringPrefill = null
        bridge.reply = "Hello."
        engine.chat(
            listOf(head, ChatMessage.text(ChatRole.USER, "hi")),
            NO_THINKING,
        ).toList()
        assertThat(bridge.prompts.last()).contains("Rule 0:")
    }

    private fun installed(name: String): File {
        val model = folder.newFile(name)
        folder.newFile(model.nameWithoutExtension + ".tokenizer.json")
        return model
    }

    /**
     * The terminal event, which is no longer the first one: replies stream now, so the
     * flow yields tokens and ends with the completion.
     */
    private suspend fun Flow<GenerationEvent>.completed(): GenerationEvent.Completed =
        toList().filterIsInstance<GenerationEvent.Completed>().single()

    private fun user(text: String) = ChatMessage.text(ChatRole.USER, text)
    private fun assistant(text: String) = ChatMessage.text(ChatRole.ASSISTANT, text)

    private companion object {
        const val MODEL = "Qwen3-1.7B.pte"

        /** Long enough per fragment for a warm to be waiting while the turn still runs. */
        const val SLOW_FRAGMENT_MS = 20L
        const val LONG_HEAD_CHARS = 4_000
        val PARAMS = ModelLoadParams(contextLength = 4096)

        /** One tool, so a turn that offers them renders a different system block. */
        val TOOLS = listOf(
            ToolDefinition(
                name = "web_search",
                description = "Search the web.",
                parametersJson = "{\"type\": \"object\", \"properties\": {}}",
            ),
        )

        /** Reasoning off, so history renders the same way twice and the cache can hold. */
        val NO_THINKING = SamplerParams(thinking = false)

        /** A head long enough to need several warm pieces. */
        val LONG_RULES = buildString {
            append("You are a careful assistant.\n")
            repeat(60) { index ->
                append("Rule ").append(index)
                    .append(": prefer the shortest correct answer, cite nothing, and ")
                    .append("keep lists to three items.\n")
            }
        }
    }
}
