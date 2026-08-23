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

package io.github.alpharomercoma.openweights.ui.chat

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.alpharomercoma.openweights.core.common.context.CompactionPolicy
import io.github.alpharomercoma.openweights.core.common.model.AssistantReply
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import io.github.alpharomercoma.openweights.core.common.model.ToolDefinition
import io.github.alpharomercoma.openweights.core.common.model.assistantHistoryText
import io.github.alpharomercoma.openweights.core.common.model.parseAssistantReply
import io.github.alpharomercoma.openweights.core.engine.GenerationEvent
import io.github.alpharomercoma.openweights.core.engine.InferenceEngine
import io.github.alpharomercoma.openweights.core.engine.LlamaCppEngine
import io.github.alpharomercoma.openweights.core.tools.AgentStep
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What a long conversation actually costs, turn by turn, across several folds.
 *
 * Everything else here measures one turn or two. This runs twenty against a real model, with
 * the app's own prompt assembly and its own compaction policy, and prints a row per turn: how
 * much of the prompt had to be decoded, what that took, how fast the answer came out, and
 * where the folds landed. It is the only thing in the suite that can answer "does this get
 * slower as you use it", which is the question a person actually has.
 *
 * It asserts almost nothing, on purpose. Wall clocks on a phone are a function of heat and of
 * whatever else the scheduler is doing, and a suite that fails on a number nobody can act on
 * stops being run. What it does assert is the one thing that is a property of the code rather
 * than of the device: that an ordinary follow-up turn, one with no fold in it, re-reads only
 * what is new. That is what the prefix cache is for and it has been silently broken before.
 *
 * Push a model first:
 * ```
 * adb push model.gguf /data/local/tmp/openweights/model.gguf
 * ```
 */
@RunWith(AndroidJUnit4::class)
class LongConversationOnDeviceTest {
    private lateinit var engine: InferenceEngine

    @Before
    fun setUp() {
        Fixtures.require("no test model at ${MODEL.path}", MODEL.isFile)
        engine = LlamaCppEngine()
    }

    @After
    fun tearDown() {
        if (::engine.isInitialized) runBlocking { engine.close() }
    }

    /**
     * The window and the length are arguments, because one shape cannot ask both questions.
     *
     * A 4,096 window is what a phone of this class opens with and it takes more turns than
     * anyone runs in a test to fold even once, which is the right answer for that device and
     * a useless one for measuring folds. A 2,048 window folds every few turns and is what a
     * cheaper phone actually gets. Both are real; neither is the default on its own.
     *
     * ```
     * adb shell am instrument -w -e ctx 2048 -e turns 30 -e class ...LongConversationOnDeviceTest ...
     * ```
     */
    private fun arg(name: String, fallback: Int): Int =
        InstrumentationRegistry.getArguments().getString(name)?.toIntOrNull() ?: fallback

    @Test
    fun aLongConversationStaysCheapBetweenFolds() = runBlocking {
        val context = arg("ctx", CONTEXT)
        val turns = arg("turns", TURNS)
        // The control. With the tools out of the prompt the model cannot call one, so no
        // turn in the transcript is missing a call and a result that the cache is holding,
        // and prefix reuse has nothing to trip over. Running the same conversation both ways
        // is what separates "a long conversation is expensive" from "a tool call makes every
        // later turn expensive".
        val useTools = arg("tools", 1) == 1
        engine.load(MODEL, ModelLoadParams(contextLength = context))
        val compactor = ConversationCompactor(engine, CompactionPolicy())
        val folder = Folder(
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "longconv-${System.nanoTime()}",
            ),
        )

        var state = ChatUiState(
            supportsTools = engine.loadedModel?.supportsTools == true,
            toolsAvailable = useTools,
            contextSize = context,
        )
        var nextId = 1L
        var folds = 0
        val followUpCosts = mutableListOf<Int>()
        var probes = 0
        var toolCallTurns = 0
        var toolRounds = 0
        var turnsWithTools = 0
        var recalled = 0

        Log.i(TAG, "ctx=$context turns=$turns tools=$useTools")
        Log.i(TAG, "turn  fold  prompt  prefill    tg     ctx  question")
        repeat(turns) { index ->
            val (ask, recall) = ASKS[index % ASKS.size]
            state = state.copy(
                transcript = state.transcript +
                    TranscriptEntry(id = nextId++, role = ChatRole.USER, text = ask),
            )

            // Exactly where ChatViewModel folds: before the turn, never during it.
            var foldMs = 0L
            if (compactor.shouldCompact(state)) {
                val startedAt = System.currentTimeMillis()
                val compaction = compactor.compact(state, engineIsDecoding = false)
                foldMs = System.currentTimeMillis() - startedAt
                if (compaction != null) {
                    folds++
                    state = state.copy(compaction = compaction)
                    Log.i(
                        TAG,
                        "fold $folds: ${compaction.foldedEntryCount} entries in $foldMs ms, " +
                            "summary ${compaction.summary.length} chars",
                    )
                    // The tail, because a summary that ran into the token cap ends mid
                    // sentence and is worse than a shorter one that finished. Nothing else
                    // here can tell those apart.
                    Log.i(TAG, "  head: ${compaction.summary.take(110).replace("\n", " ")}")
                    Log.i(TAG, "  tail: ${compaction.summary.takeLast(110).replace("\n", " ")}")
                }
            }

            // The loop, which this file did not have and needed.
            //
            // It used to call the engine once and store whatever came back. When the model
            // asked for a tool nothing ran it, so the raw call syntax became the turn's
            // text, and compaction then summarised a transcript full of
            // `<|tool_call_start|>` and produced more of the same: the 178 character
            // "summary" that was nothing but two web searches was this file's fault and not
            // the app's. Every reading it produced about summary quality or recall after the
            // first fold was measuring the harness.
            //
            // So it now does what `TurnRunner` does, in the small: ask, run what was asked
            // for, feed the results back, ask again, up to the same round cap. The tools are
            // answered from a fixed fixture rather than the real registry, because what is
            // being measured is what a multi-turn conversation costs and remembers, not
            // whether DuckDuckGo is up.
            var pass = state.engineMessages()
            var rounds = 0
            var raw: String
            var parsed: AssistantReply
            var done: GenerationEvent.Completed
            val used = mutableListOf<String>()
            // Recorded the way TurnRunner records it, because the entry's blocks are what
            // the next turn's prompt is rebuilt from. A harness that keeps only the answer
            // cannot see whether that rebuild matches the cache, which is the whole
            // question.
            val blocks = mutableListOf<TurnBlock>()
            while (true) {
                val events = engine.chat(
                    messages = pass,
                    params = SamplerParams(
                        temperature = 0f,
                        maxTokens = ANSWER_TOKENS,
                        seed = 1,
                    ),
                    tools = if (useTools) TOOLS else emptyList(),
                ).toList()
                raw = events.filterIsInstance<GenerationEvent.Token>()
                    .joinToString("") { it.text }
                done = events.filterIsInstance<GenerationEvent.Completed>().single()
                parsed = parseAssistantReply(raw)

                val calls = done.toolCalls
                if (calls.isEmpty() || rounds >= MAX_ROUNDS) break
                rounds++
                toolRounds++
                calls.forEach { used += it.name }
                done.content.takeIf { it.isNotBlank() }?.let { blocks += TurnBlock.Said(it) }
                val results = calls.map { it to folder.answer(it) }
                results.forEach { (call, result) ->
                    blocks += TurnBlock.Step(AgentStep.Ran(call, result, 0))
                }
                pass = pass +
                    ChatMessage.text(ChatRole.ASSISTANT, assistantHistoryText(raw)) +
                    results.map { (call, result) ->
                        ChatMessage.text(ChatRole.TOOL, result).copy(toolCallId = call.id)
                    }
            }
            if (used.isNotEmpty()) {
                turnsWithTools++
                Log.i(TAG, "  tools: ${used.joinToString(", ")}")
            }

            // `done.content` rather than a re-parse of the stream. The engine has already
            // split the reply for this model's format and taken the tool syntax out; the
            // stream still has it in, so parsing the stream stores a turn whose text is a
            // tool call whenever the loop stopped at the round cap with one outstanding.
            val answer = done.content.ifBlank { parsed.answer }
            state = state.copy(
                transcript = state.transcript + TranscriptEntry(
                    id = nextId++,
                    role = ChatRole.ASSISTANT,
                    text = answer,
                    history = raw.takeIf { it.isNotBlank() }
                        ?.let { assistantHistoryText(it, done.stats.thinkingPrefilled) },
                    answer = answer,
                    reasoning = done.reasoning.ifBlank { parsed.reasoning.orEmpty() }
                        .takeIf { it.isNotBlank() },
                    blocks = blocks,
                ),
                contextUsed = done.stats.contextUsed,
                contextSize = done.stats.contextSize,
            )

            // The old failure, now an assertion rather than a warning. A turn whose stored
            // text is tool syntax means the loop stopped running and every number after it
            // is worthless.
            if (TOOL_CALL_MARKERS.any { it in answer }) toolCallTurns++

            if (recall != null) {
                probes++
                // Whitespace-folded, because the model wrote "14\u00a0September" with a
                // non-breaking space and the first version of this probe called that a lost
                // fact. A recall test that fails on the width of a space is measuring the
                // test.
                val said = answer.replace(WHITESPACE, " ")
                val kept = recall.any { said.contains(it, ignoreCase = true) }
                if (kept) recalled++
                Log.i(
                    TAG,
                    "  recall ${if (kept) "kept " else "LOST "} \"${recall.first()}\" after " +
                        "$folds fold(s): ${answer.take(120).replace("\n", " ")}",
                )
            }

            val fold = if (foldMs > 0) "yes" else "  ."
            Log.i(
                TAG,
                "%4d  %4s  %6d  %7d  %4.1f  %6d  %s".format(
                    index + 1,
                    fold,
                    done.stats.promptTokens,
                    done.stats.prefillMs,
                    done.stats.decodeTokensPerSecond ?: 0.0,
                    done.stats.contextUsed,
                    ask.take(38),
                ),
            )
            // A turn that did not fold is an ordinary follow-up, and an ordinary follow-up
            // should be reading the new question and nothing else. The first turn is not one.
            if (index > 0 && foldMs == 0L) followUpCosts += done.stats.promptTokens
        }

        Log.i(
            TAG,
            "folds=$folds recall=$recalled/$probes turnsWithTools=$turnsWithTools " +
                "toolRounds=$toolRounds unexecuted=$toolCallTurns " +
                "followUpPrompts=${followUpCosts.sorted()} worst=${followUpCosts.maxOrNull()}",
        )
        // Not a warning any more. If this fires the loop above stopped running and every
        // number this test printed is measuring the harness rather than the app, which is
        // exactly the way it was wrong before and is not worth being wrong the same way
        // twice.
        assertThat(toolCallTurns).isEqualTo(0)
        // Only when the run was shaped to produce them. At a 4,096 window this conversation
        // does not reach the threshold, which is itself worth knowing and is not a failure.
        if (context <= 2048) assertThat(folds).isAtLeast(1)
        // The property being guarded: a follow-up with no fold in it costs the question, not
        // the conversation. That is what the prefix cache is for and it has been silently
        // broken before.
        //
        // With tools on it is broken now, and this is the test that says so. A turn that
        // used a tool stores its answer without the call or the result, so the next turn's
        // prompt diverges from the cache 55 to 180 tokens from the end, the hybrid rollback
        // is refused, and the whole prefix is thrown away: measured at 1,393 to 1,931 tokens
        // and eleven to nineteen seconds a turn, on every turn, for the rest of the
        // conversation. See "The cache throws away a thousand tokens to drop a hundred".
        //
        // Left failing rather than relaxed. The ceiling is the number the app should meet,
        // the gap is the defect, and a test quietly widened to accept a regression is worse
        // than no test. With `-e tools 0` the same conversation passes, which is the control
        // that says the ceiling itself is reachable.
        val worst = followUpCosts.maxOrNull() ?: 0
        assertWithMessage(
            "a follow-up re-read $worst prompt tokens. With tools off this is under " +
                "$FOLLOW_UP_CEILING, so this is the tool-history defect rather than the " +
                "conversation being long",
        ).that(worst).isLessThan(FOLLOW_UP_CEILING)
    }

    private companion object {
        const val TAG = "OpenWeights"

        /** Any run of whitespace, including the non-breaking kind a model likes to emit. */
        val WHITESPACE = Regex("\\s+")

        /** The app's own round cap, so the harness stops where the product stops. */
        const val MAX_ROUNDS = 4

        /** What an unexecuted tool call looks like once it is sitting in a transcript. */
        val TOOL_CALL_MARKERS = listOf("<|tool_call_start|>", "<tool_call>", "\"tool_calls\"")
        const val CONTEXT = 4096
        const val TURNS = 20
        const val ANSWER_TOKENS = 160

        /**
         * What a follow-up may re-read before something is wrong.
         *
         * Two hundred rather than twenty: the question itself, its turn markers, and the
         * tokeniser's behaviour where the previous answer meets the new turn. A conversation
         * being re-read shows up in the thousands, so this separates the two cases without
         * being a number anybody has to tune.
         */
        const val FOLLOW_UP_CEILING = 200

        val MODEL = File("/data/local/tmp/openweights/model.gguf")

        /**
         * Questions that refer back, because that is what a long conversation is.
         *
         * A list of unrelated trivia would measure the cache and nothing else. These build on
         * each other, so a fold that loses the thread shows up as an answer that no longer
         * makes sense, and the log carries enough of each reply to see it.
         */
        /**
         * The conversation, and what has to survive it.
         *
         * Half of these turns need a tool to answer, so half the facts in the transcript
         * arrive through a tool result rather than out of the model. That is deliberate and
         * it is the harder case for compaction: a summariser that keeps the gist of what the
         * user said may still drop the number a file gave back, and until this file ran the
         * tools there was no way to tell.
         *
         * A probe is a question with an answer a string can check for, asked long after the
         * fact was established and at least one fold later. Alternatives, because "eight"
         * and "8" are the same answer and neither is the one the model has to pick.
         */
        val ASKS: List<Pair<String, List<String>?>> = listOf(
            "I am planning a small vegetable garden. Where should I start?" to null,
            "How much space does that need?" to null,
            "Look in my notes for anything about sun and tell me what it says." to null,
            "Read notes/seeds.md and tell me how many tomato plants I bought." to null,
            "What about watering, how often? Check my notes." to null,
            "When should I plant, if it is late August?" to null,
            "Can I grow any of them in containers instead?" to null,
            "Save a file at notes/plan.md saying which crop needs the most sun." to null,
            // The probes. Three of the five ask for a fact that only ever entered this
            // conversation through a tool result, which is the case nothing here could
            // measure before.
            "In one word, what kind of garden did I say I was planning?" to listOf("vegetable"),
            "How many hours of direct sun did my notes say tomatoes need?" to
                listOf("8", "eight"),
            "What sowing deadline did my notes give? Name the date." to
                listOf("14 september", "september 14", "sept 14"),
            "How many tomato plants did I buy, according to my notes?" to listOf("6", "six"),
            "How often did my notes say to water?" to listOf("twice", "two times"),
            // Reads back what the model itself wrote, several turns and a fold later. It
            // can only pass if the write really happened and the model can find it again.
            "Read notes/plan.md and tell me in one word which crop it names." to
                listOf("tomato"),
        )

        /**
         * A tool block the size of the shipped catalogue.
         *
         * The prompt is the thing being measured and the eight real definitions are about a
         * thousand tokens of it, so a test with no tools would measure a conversation this
         * app never has. These are the shipped descriptions for the four that matter most
         * here; the rest of the weight is made up by the instructions.
         */
        val TOOLS = listOf(
            ToolDefinition(
                name = "web_search",
                description = "Search the web, only for what you cannot already know: what " +
                    "changed, what is recent, or the present state of a named person, " +
                    "product or organisation. Not for definitions, translations, grammar, " +
                    "history, arithmetic, opinions or explanations, and not to double check " +
                    "something you already know. Answer those yourself.",
                parametersJson = """
                    {"type":"object","properties":{"query":{"type":"string",
                    "description":"What to look up, as you would type it into a search box"}},
                    "required":["query"]}
                """.trimIndent(),
            ),
            ToolDefinition(
                name = "search_files",
                description = "Find files in the folder the user shared. Match names with a " +
                    "pattern like *.md, and optionally give text to look for inside them. " +
                    "Not for reading a file whose path you have. If nothing was named to " +
                    "look for, ask rather than guess.",
                parametersJson = """
                    {"type":"object","properties":{"pattern":{"type":"string",
                    "description":"A file name or a pattern such as *.md or notes*"},
                    "contains":{"type":"string",
                    "description":"Optional text that must appear inside the file"}},
                    "required":["pattern"]}
                """.trimIndent(),
            ),
            ToolDefinition(
                name = "read_file",
                description = "Read the text of a file in the folder the user shared. Use " +
                    "the path exactly as search_files gave it. If no path is known, ask " +
                    "which file rather than inventing one.",
                parametersJson = """
                    {"type":"object","properties":{"path":{"type":"string",
                    "description":"The file's path inside the shared folder, like notes/todo.md"},
                    "offset":{"type":"integer",
                    "description":"Characters to skip, to read further into a long file"}},
                    "required":["path"]}
                """.trimIndent(),
            ),
            ToolDefinition(
                name = "write_file",
                description = "Save text to a file in the folder the user shared. Give the " +
                    "path and the whole content. Use it when the user asks for something to " +
                    "be written down or kept.",
                parametersJson = """
                    {"type":"object","properties":{"path":{"type":"string",
                    "description":"Where to save it, like notes/summary.md"},
                    "content":{"type":"string","description":"The whole text of the file"}},
                    "required":["path","content"]}
                """.trimIndent(),
            ),
            ToolDefinition(
                name = "run_script",
                description = "Write a JavaScript program, run it in a sandbox, and use what " +
                    "it returns. Use it for real computation: arithmetic, parsing, " +
                    "filtering, regular expressions, JSON, dates, and going through a file " +
                    "too large to read into the conversation. Give source, or path to run a " +
                    ".js file you saved. Name data files as inputs['path']. The last " +
                    "expression is the answer.",
                parametersJson = """
                    {"type":"object","properties":{"source":{"type":"string",
                    "description":"The JavaScript to run. The last expression is the answer."},
                    "path":{"type":"string","description":"Instead of source, a .js file to run"}}}
                """.trimIndent(),
            ),
        )
    }
}

/**
 * A real folder on the phone, that the model reads and writes for real.
 *
 * The first version of this answered every call out of a fixed map, which proves nothing
 * about whether a model can use a filesystem: a read that cannot miss and a write that goes
 * nowhere will make any model look competent. Here `search_files` walks a directory,
 * `read_file` opens a file and fails if it is not there, and `write_file` puts bytes on disk
 * that the next `read_file` has to find. So the model can be wrong, and the conversation can
 * be wrong later because of it.
 *
 * Under the instrumentation context's own cache directory, which is inside this app's
 * package and is deleted at the end of the run. Nothing on the device outside it is touched.
 *
 * This is not the SAF layer. `Workspace` speaks `DocumentsContract` and needs a folder the
 * user picked, which is covered by `WorkspaceOnDeviceTest` and cannot be granted from a test
 * without driving the system picker. What is under test here is the other half, and the half
 * the question was about: whether the model knows what to do with the tools.
 */
private class Folder(root: File) {
    val dir = File(root, "notes").apply { mkdirs() }

    init {
        write(
            "garden.md",
            "Beds: 4 by 8 feet. Tomatoes need 8 hours of sun. " +
                "Lettuce tolerates 4. Sowing deadline is 14 September.",
        )
        write(
            "handover.md",
            "Hand the plot over before the deadline. Water deeply twice " +
                "a week rather than lightly every day.",
        )
        write("seeds.md", "Bought: 6 tomato, 12 lettuce, 3 courgette.")
    }

    private fun write(name: String, text: String) = File(dir, name).writeText(text)

    fun answer(call: ToolCall): String {
        val args = call.argumentsJson
        return when (call.name) {
            "web_search" ->
                "Late August sowing in a temperate zone suits lettuce, spinach and radish. " +
                    "Tomatoes are too late to start from seed."

            "search_files" -> {
                val pattern = args.value("pattern")?.substringAfterLast('/') ?: "*"
                val contains = args.value("contains")
                val glob = Regex(
                    pattern.split("*").joinToString(".*") { Regex.escape(it) },
                    RegexOption.IGNORE_CASE,
                )
                dir.listFiles().orEmpty()
                    .filter { glob.matches(it.name) || pattern == "*" }
                    .filter { contains == null || it.readText().contains(contains, true) }
                    .joinToString("\n") { "notes/${it.name}" }
                    .ifEmpty { "no files matched" }
            }

            "read_file" -> {
                val path = args.value("path")?.trimStart('/')?.substringAfterLast('/')
                val file = path?.let { File(dir, it) }
                if (file != null && file.isFile) {
                    file.readText()
                } else {
                    "no such file: ${args.value("path")}. Use search_files to find one."
                }
            }

            "write_file" -> {
                val path = args.value("path")?.trimStart('/')?.substringAfterLast('/')
                val content = args.value("content")
                if (path.isNullOrBlank() || content == null) {
                    "write_file needs a path and content"
                } else {
                    File(dir, path).writeText(content)
                    "saved notes/$path, ${content.length} characters"
                }
            }

            // Not the real sandbox, which needs a Workspace this test cannot grant. Enough
            // to tell a model that asked for arithmetic that it will not get it for free.
            "run_script" -> "the sandbox is not available in this run"

            else -> "unknown tool: ${call.name}"
        }
    }

    /** The value of one key out of a flat arguments object, without a JSON parser. */
    private fun String.value(key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(this)
            ?.groupValues?.get(1)?.replace("\\n", "\n")
}
