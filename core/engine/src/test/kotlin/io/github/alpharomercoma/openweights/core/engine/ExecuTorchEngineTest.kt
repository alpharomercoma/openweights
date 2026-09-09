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
import io.github.alpharomercoma.openweights.core.common.model.Fit
import io.github.alpharomercoma.openweights.core.common.model.MessagePart
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.common.model.ToolDefinition
import io.github.alpharomercoma.openweights.core.engine.LlamaException
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
@Suppress("LargeClass") // One fixture holds every runtime contract this engine keeps.
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
    fun `the window is the file's, and what is held is reported`() = runTest {
        // A .pte exported at 2048 was shown as the 4096 the user had asked for, and every
        // budget upstream believed it; the runtime then refused the first search result.
        bridge.exportedContextLength = 2048
        bridge.reply = "Hello.<|im_end|>"
        bridge.outcome =
            ExecuTorchOutcome(StopReason.END_OF_TURN, promptTokens = 40, generatedTokens = 3)
        engine.load(installed(MODEL), PARAMS.copy(contextLength = 4096))

        assertThat(engine.loadedModel?.contextSize).isEqualTo(2048)
        assertThat(engine.loadedModel?.trainingContextSize).isEqualTo(2048)
        assertThat(engine.loadedModel?.contextUsed).isEqualTo(0)

        val events = engine.chat(listOf(user("Hi"))).toList()

        val completed = events.filterIsInstance<GenerationEvent.Completed>().single()
        assertThat(completed.stats.contextSize).isEqualTo(2048)
        assertThat(completed.stats.contextUsed).isEqualTo(43)
        assertThat(engine.loadedModel?.contextUsed).isEqualTo(43)
    }

    @Test
    fun `a picture is fed between the text around it, in the export's brackets`() = runTest {
        // What Software Mansion's processor writes and their runner feeds: the text up to
        // the picture ending in <|image_start|>, the picture as embeddings, and the rest of
        // the prompt opening with <|image_end|>. Order is everything to the cache.
        bridge.hasVision = true
        bridge.exportedContextLength = EXPORTED_WINDOW
        bridge.reply = "A red square on blue.<|im_end|>"
        val pixelsRead = mutableListOf<String>()
        val engine =
            ExecuTorchEngine(bridge, reader = { path, side, fit ->
                pixelsRead += "$path@$side $fit"
                FloatArray(
                    3 * side * side,
                )
            })
        engine.load(installed(VISION_MODEL), PARAMS)
        assertThat(bridge.loadedMultimodal).isTrue()
        assertThat(engine.loadedModel?.mediaSupport?.vision).isTrue()

        val message = ChatMessage(
            ChatRole.USER,
            listOf(
                MessagePart.Text("Describe this."),
                MessagePart.File("/pictures/square.png", "image/png"),
            ),
        )
        val events = engine.chat(listOf(message)).toList()

        assertThat(pixelsRead).containsExactly("/pictures/square.png@512 LETTERBOX")
        assertThat(bridge.pictures).containsExactly(Triple(512, 512, 3))
        assertThat(bridge.fed).hasSize(2)
        // Exactly what the processor writes: the text, then the opening bracket, with no
        // whitespace added on either side of the picture (codex QA: a newline there is a
        // token the model was never shown at that spot, and it moves every later position).
        assertThat(bridge.fed[0]).endsWith("<|im_start|>user\nDescribe this.<|image_start|>")
        assertThat(bridge.fed[0]).doesNotContain("\u0000")
        assertThat(bridge.fed[1]).isEqualTo("<picture 512 x 512>")
        assertThat(bridge.lastPrompt).isEqualTo("<|image_end|><|im_end|>\n<|im_start|>assistant\n")
        val completed = events.filterIsInstance<GenerationEvent.Completed>().single()
        assertThat(completed.content).isEqualTo("A red square on blue.")
    }

    @Test
    fun `Gemma 3 gets its own brackets and its pixels centred`() = runTest {
        // The official Gemma 3 export wraps the SigLIP tower alone, so the picture arrives as
        // the processor would hand it: an 896 square, mean 0.5 and standard deviation 0.5,
        // between the processor's blank lines and image tokens. The 0..255 bytes the reader
        // yields must therefore leave the engine as -1..1.
        bridge.hasVision = true
        bridge.exportedContextLength = EXPORTED_WINDOW
        bridge.reply = "A red square on blue.<end_of_turn>"
        val fits = mutableListOf<Fit>()
        val engine = ExecuTorchEngine(bridge, reader = { _, side, fit ->
            fits += fit
            FloatArray(3 * side * side) { if (it % 2 == 0) 0f else 255f }
        })
        engine.load(installed("gemma-3-4b-it-HQQ-INT8-INT4.pte"), PARAMS)
        assertThat(bridge.loadedMultimodal).isTrue()

        // The composer puts attachments before the text (ChatViewModel), so this is the
        // order a real turn arrives in: the processor's two blank lines follow the header's
        // own newline, three in a row, which is what the HF processor produces too since
        // its replacement is literal and collapses nothing (codex QA).
        val message = ChatMessage(
            ChatRole.USER,
            listOf(
                MessagePart.File("/pictures/square.png", "image/png"),
                MessagePart.Text("Describe this."),
            ),
        )
        engine.chat(listOf(message)).toList()

        assertThat(bridge.pictures).containsExactly(Triple(896, 896, 3))
        assertThat(fits).containsExactly(Fit.STRETCH)
        assertThat(bridge.pixelRange).isEqualTo(-1f..1f)
        assertThat(bridge.fed[0]).endsWith("<start_of_turn>user\n\n\n<start_of_image>")
        assertThat(bridge.lastPrompt)
            .isEqualTo("<end_of_image>\n\nDescribe this.<end_of_turn>\n<start_of_turn>model\n")
    }

    @Test
    fun `a turn with a picture never extends the cache and is never extended`() = runTest {
        bridge.hasVision = true
        bridge.exportedContextLength = EXPORTED_WINDOW
        bridge.reply = "Hello.<|im_end|>"
        val engine =
            ExecuTorchEngine(bridge, reader = { _, side, _ -> FloatArray(3 * side * side) })
        engine.load(installed(VISION_MODEL), PARAMS)

        engine.chat(listOf(user("Hi"))).toList()
        val withPicture = listOf(
            user("Hi"),
            assistant("Hello."),
            ChatMessage(
                ChatRole.USER,
                listOf(MessagePart.File("/p.png", "image/png"), MessagePart.Text("What is it?")),
            ),
        )
        engine.chat(withPicture).toList()
        engine.chat(withPicture + assistant("Hello.") + user("And now?")).toList()

        // Reset before the picture turn, and again after it: embeddings in the cache are
        // not something the text record can promise to extend.
        assertThat(bridge.contextResets).isEqualTo(3)
        assertThat(bridge.pictures).hasSize(2)
    }

    @Test
    fun `a file that is not a picture is left out rather than counted`() = runTest {
        // A PDF beside a PNG used to put two markers in the text for one picture and trip
        // an invariant check (codex QA). The runtime has no way in for the PDF.
        bridge.hasVision = true
        bridge.exportedContextLength = EXPORTED_WINDOW
        bridge.reply = "One picture.<|im_end|>"
        val engine =
            ExecuTorchEngine(bridge, reader = { _, side, _ -> FloatArray(3 * side * side) })
        engine.load(installed(VISION_MODEL), PARAMS)

        val message = ChatMessage(
            ChatRole.USER,
            listOf(
                MessagePart.File("/docs/report.pdf", "application/pdf"),
                MessagePart.File("/pictures/a.png", "image/png"),
                MessagePart.Text("What is it? \u0000picture\u0000"),
            ),
        )
        val events = engine.chat(listOf(message)).toList()

        assertThat(bridge.pictures).hasSize(1)
        assertThat(bridge.lastPrompt).startsWith("<|image_end|>What is it? <|im_end|>")
        assertThat(events.filterIsInstance<GenerationEvent.Completed>().single().content)
            .isEqualTo("One picture.")
    }

    @Test
    fun `a marker typed into an earlier message does not count as a picture`() = runTest {
        bridge.hasVision = true
        bridge.exportedContextLength = EXPORTED_WINDOW
        bridge.reply = "Fine.<|im_end|>"
        val engine =
            ExecuTorchEngine(bridge, reader = { _, side, _ -> FloatArray(3 * side * side) })
        engine.load(installed(VISION_MODEL), PARAMS)

        val conversation = listOf(
            user("Earlier I typed \u0000picture\u0000 by accident."),
            assistant("Noted."),
            ChatMessage(
                ChatRole.USER,
                listOf(MessagePart.File("/p/a.png", "image/png"), MessagePart.Text("Now?")),
            ),
        )
        engine.chat(conversation).toList()

        assertThat(bridge.pictures).hasSize(1)
        assertThat(bridge.fed[0]).contains("Earlier I typed  by accident.")
    }

    @Test
    fun `too many pictures for the window are refused before the first is read`() = runTest {
        bridge.hasVision = true
        bridge.exportedContextLength = EXPORTED_WINDOW
        bridge.exportedContextLength = 2048
        val engine =
            ExecuTorchEngine(bridge, reader = { _, side, _ -> FloatArray(3 * side * side) })
        engine.load(installed(VISION_MODEL), PARAMS)

        val eight = List(8) { MessagePart.File("/p/$it.png", "image/png") }
        val failure = runCatching {
            engine.chat(
                listOf(ChatMessage(ChatRole.USER, eight + MessagePart.Text("Compare."))),
            ).toList()
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ContextWindowExceededException::class.java)
        assertThat(bridge.pictures).isEmpty()
        // Nothing half-fed is left behind for the next turn to extend.
        assertThat(engine.loadedModel?.contextUsed).isEqualTo(0)
    }

    @Test
    fun `a stop between pictures ends the turn as a cancellation`() = runTest {
        bridge.hasVision = true
        bridge.exportedContextLength = EXPORTED_WINDOW
        bridge.reply = "never"
        lateinit var engine: ExecuTorchEngine
        engine =
            ExecuTorchEngine(bridge, reader = { _, side, _ ->
                engine.cancel()
                FloatArray(
                    3 * side * side,
                )
            })
        engine.load(installed(VISION_MODEL), PARAMS)

        val two =
            listOf(
                MessagePart.File("/p/a.png", "image/png"),
                MessagePart.File("/p/b.png", "image/png"),
            )
        val events = engine.chat(
            listOf(
                ChatMessage(
                    ChatRole.USER,
                    two + MessagePart.Text("Compare."),
                ),
            ),
        ).toList()

        val done = events.filterIsInstance<GenerationEvent.Completed>().single()
        assertThat(done.reason).isEqualTo(StopReason.CANCELLED)
        assertThat(bridge.pictures).hasSize(1)
        assertThat(bridge.prompts).isEmpty()
    }

    @Test
    fun `a vision export of a family with no square opens as text`() = runTest {
        bridge.hasVision = true
        bridge.exportedContextLength = EXPORTED_WINDOW
        engine.load(installed(MODEL), PARAMS)

        assertThat(bridge.loadedMultimodal).isFalse()
        assertThat(engine.loadedModel?.mediaSupport?.vision).isFalse()
    }

    @Test
    fun `a tokenizer that adds no BOS gets the template's written into the prompt`() = runTest {
        // LFM2.5-2.6B's tokenizer.json (transformers 5) has no BOS post-processor; the runtime
        // fed the model bare text and it answered garbage on every chip. The engine writes
        // <|startoftext|> itself then, and only then: the 1.2B's tokenizer adds it already.
        bridge.tokenizerAddsBos = false
        engine.load(installed("LFM2.5-2.6B-8da4w-16k.pte"), PARAMS)
        engine.chat(listOf(ChatMessage.text(ChatRole.USER, "Hi"))).toList()
        assertThat(bridge.lastPrompt).startsWith("<|startoftext|><|im_start|>user\nHi<|im_end|>")

        bridge.tokenizerAddsBos = true
        engine.load(installed("LFM2.5-1.2B-Instruct-8da4w-16k.pte"), PARAMS)
        engine.chat(listOf(ChatMessage.text(ChatRole.USER, "Hi"))).toList()
        assertThat(bridge.lastPrompt).startsWith("<|im_start|>user\nHi<|im_end|>")
    }

    @Test
    fun `a vision export without a window is refused before the runner can abort on it`() =
        runTest {
            // Seen on the phone with an older exporter's SmolVLM2: the multimodal runner reads
            // get_max_seq_len first and kills the process when it is missing.
            bridge.hasVision = true
            bridge.exportedContextLength = EXPORTED_WINDOW
            bridge.exportedContextLength = null

            val refused = runCatching {
                engine.load(installed(VISION_MODEL), PARAMS)
            }.exceptionOrNull()

            assertThat(refused).isInstanceOf(LlamaException::class.java)
            assertThat(refused).hasMessageThat().contains("exported without the metadata")
            assertThat(bridge.loadedMultimodal).isFalse()
            assertThat(engine.loadedModel).isNull()
        }

    @Test
    fun `a file that does not say its window keeps the preference`() = runTest {
        engine.load(installed(MODEL), PARAMS.copy(contextLength = 4096))

        assertThat(engine.loadedModel?.contextSize).isEqualTo(4096)
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

    /**
     * The exporter bounds one prefill at `max_seq_len - 1` tokens and the runtime chunks
     * at `max_seq_len`, so a generate call carrying a whole long prompt fails on the
     * phone. The engine feeds the head in pieces and hands generate only a short tail.
     */
    @Test
    fun `a long prompt is fed ahead in pieces and generate gets only the tail`() = runTest {
        engine.load(installed(MODEL), PARAMS)
        val essay = buildString { repeat(400) { append("word").append(it).append(' ') } }
        bridge.reply = "Noted."
        // What the runtime reports is the tail alone: the pieces went in by prefill, which
        // reports nothing. Four hundred tokens for the tail, at whatever the tail's length is.
        bridge.outcome =
            ExecuTorchOutcome(StopReason.END_OF_TURN, promptTokens = 400, generatedTokens = 2)

        val done = engine.chat(listOf(user(essay)), NO_THINKING).completed()

        assertThat(bridge.prefills).isNotEmpty()
        bridge.prefills.forEach { assertThat(it.length).isAtMost(1600) }
        assertThat(bridge.prompts.last().length).isAtMost(1600)
        // Nothing lost and nothing doubled between the pieces and the tail.
        val fed = bridge.prefills.joinToString("") + bridge.prompts.last()
        assertThat(fed).contains("word0 ")
        assertThat(fed).contains("word399 ")
        assertThat(fed.indexOf("word200 ")).isEqualTo(fed.lastIndexOf("word200 "))

        // The pieces are this turn's prompt, at the tail's measured rate, and not a cache
        // hit. On the phone a two-thousand-token head fed this way read as 85% cached and
        // the prefill rate of the tail alone.
        val pieces = bridge.prefills.sumOf { it.length }
        val tailRate = bridge.prompts.last().length.toDouble() / 400
        assertThat(done.stats.promptTokens).isEqualTo(400 + (pieces / tailRate).toInt())
        assertThat(done.stats.cachedTokens).isEqualTo(0)
        assertThat(done.stats.cacheHitRate).isEqualTo(0.0)
    }

    @Test
    fun `the first token is the prefill's and is counted with the reply`() = runTest {
        // The runner samples the first token at the end of the prompt and reports only its
        // decode loop's steps as generated, so the reply the reader saw is one longer than
        // the runtime's count, and the loop's steps are exactly the tokens after the first,
        // which is what the decode rate divides by.
        bridge.reply = "Hello there.<|im_end|>"
        bridge.outcome = ExecuTorchOutcome(
            StopReason.END_OF_TURN,
            promptTokens = 40,
            generatedTokens = 3,
            prefillMs = 200,
            decodeMs = 300,
        )
        engine.load(installed(MODEL), PARAMS)

        val done = engine.chat(listOf(user("Hi")), NO_THINKING).completed()

        assertThat(done.stats.generatedTokens).isEqualTo(4)
        assertThat(done.stats.decodeTokensPerSecond).isEqualTo(10.0)
        assertThat(done.stats.prefillTokensPerSecond).isEqualTo(200.0)
        // The committed position is the runtime's: the prompt, and the tokens its loop fed
        // back, which the last sampled one never was.
        assertThat(done.stats.contextUsed).isEqualTo(43)
    }

    /** An export that states a small prefill bound gets pieces under it. */
    @Test
    fun `the piece size follows the export's prefill bound`() = runTest {
        bridge.prefillLength = 128
        engine.load(installed(MODEL), PARAMS)
        val essay = buildString { repeat(200) { append("word").append(it).append(' ') } }
        bridge.reply = "Noted."

        engine.chat(listOf(user(essay)), NO_THINKING).completed()

        bridge.prefills.forEach { assertThat(it.length).isAtMost(127) }
        assertThat(bridge.prompts.last().length).isAtMost(127)
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

        // The next turn starts over from nothing, which is slow and correct: the rules go
        // in again, ahead of generate in pieces because the prompt is long.
        bridge.failsDuringPrefill = null
        bridge.reply = "Hello."
        val piecesBefore = bridge.prefills.size
        engine.chat(
            listOf(head, ChatMessage.text(ChatRole.USER, "hi")),
            NO_THINKING,
        ).toList()
        val fed = bridge.prefills.drop(piecesBefore).joinToString("") + bridge.prompts.last()
        assertThat(fed).contains("Rule 0:")
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

        /** What Software Mansion's LFM2.5-VL export reports; a real vision export always has one. */
        const val EXPORTED_WINDOW = 2048
        const val VISION_MODEL =
            "react-native-executorch-lfm2.5-VL-1.6B-lfm2_5_vl_1_6b_8da4w_xnnpack.pte"

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
