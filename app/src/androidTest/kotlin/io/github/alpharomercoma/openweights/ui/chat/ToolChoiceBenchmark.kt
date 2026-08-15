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
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.common.model.ToolDefinition
import io.github.alpharomercoma.openweights.core.data.ModelPreferences
import io.github.alpharomercoma.openweights.core.engine.GenerationEvent
import io.github.alpharomercoma.openweights.core.engine.InferenceEngine
import io.github.alpharomercoma.openweights.core.engine.LlamaCppEngine
import io.github.alpharomercoma.openweights.core.sandbox.Sandbox
import io.github.alpharomercoma.openweights.core.tools.CallFormat
import io.github.alpharomercoma.openweights.core.tools.FetchUrlTool
import io.github.alpharomercoma.openweights.core.tools.ReadFileTool
import io.github.alpharomercoma.openweights.core.tools.RunScriptTool
import io.github.alpharomercoma.openweights.core.tools.SearchFilesTool
import io.github.alpharomercoma.openweights.core.tools.SearchSettings
import io.github.alpharomercoma.openweights.core.tools.ToolPrompting
import io.github.alpharomercoma.openweights.core.tools.ToolRegistry
import io.github.alpharomercoma.openweights.core.tools.WebSearchTool
import io.github.alpharomercoma.openweights.core.tools.Workspace
import io.github.alpharomercoma.openweights.core.tools.WorkspaceGrant
import io.github.alpharomercoma.openweights.core.tools.WriteFileTool
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/**
 * Whether a model reaches for a tool when it should, and only then.
 *
 * The first version measured nothing anyone ships: no system message, a temperature the app
 * does not use, and twelve observations per arm, which cannot tell 2/12 from 4/12. The second
 * fixed all of that and then grew to six models by four wordings by eight cases by three
 * seeds, which is five hundred and seventy six generations and most of an hour, and the run
 * it produced said something worth acting on about the size of it:
 *
 * ```
 * llama3.2-3b   12/24 under=12   in every one of four arms
 * granite3.3-2b 12/24 under=12   in every one of four arms
 * phi4-mini     15/24 over=9     in every one of four arms
 * ```
 *
 * Three of six models scored identically under four different system messages. Wording is not
 * where their accuracy is, and paying three seeds to establish that at temperature zero, where
 * the sampler has no variance to average out, was paying for nothing. So the matrix is cut to
 * what the last run showed can still move: the format the prompted models are asked for, and
 * the question of whether the model was ever told about the tools at all.
 *
 * That last one is why [probeRendering] exists. A model that never calls anything is either
 * declining or ignorant, and those look identical from here while meaning opposite things.
 * Rendering the same question with and without tools and comparing what the engine actually
 * had to prefill separates them: no difference in tokens means the template dropped the
 * definitions and the model was never asked.
 *
 * Push the models first; whichever are absent are skipped rather than failed:
 * ```
 * adb shell mkdir -p /data/local/tmp/openweights/bench
 * adb push qwen.gguf gemma.gguf llama.gguf /data/local/tmp/openweights/bench/
 * ```
 *
 * Mostly a measurement. It asserts what would make the numbers meaningless: that no
 * generation failed, and that two identical arms produced identical answers.
 */
@RunWith(AndroidJUnit4::class)
class ToolChoiceBenchmark {
    internal data class Case(val prompt: String, val expects: String?)

    /**
     * One arrangement: the shape a call is asked for in.
     *
     * Only the prompted route can vary it. A template that renders tools itself decides the
     * syntax, so for those models the two arms send byte-identical prompts, which is not
     * waste: it is the determinism check the seed count now rests on, run for free.
     */
    internal data class Arm(
        val label: String,
        val format: CallFormat = CallFormat.BARE,
        /** Reversed, to find out whether being listed first is worth anything. */
        val reversed: Boolean = false,
        /** Asked on a cache the same conversation warmed, rather than from nothing. */
        val warm: Boolean = false,
    )

    /** How a call was found, in the order [TurnRunner] looks for one. */
    private enum class Route { NATIVE, PROMPTED, NONE, ERROR }

    /** What one generation chose, and by which route it got there. */
    private data class Choice(val tool: String?, val route: Route)

    /** One arm's outcome: the counts, and what it picked for each case in order. */
    private data class Scored(val tally: Tally, val chosen: List<String?>)

    /** The outcomes, kept apart because they cost a user different things. */
    private data class Tally(
        var right: Int = 0,
        var overCall: Int = 0,
        var underCall: Int = 0,
        var wrongTool: Int = 0,
        var errored: Int = 0,
        var millis: Long = 0,
    )

    @Test
    fun measuresToolChoiceAcrossModels() = runBlocking<Unit> {
        val present = MODELS.filter { it.value.isFile }
        assumeTrue("no models under ${BENCH.path}", present.isNotEmpty())

        Log.i(TAG, "CATALOGUE offered=${catalogue().map { it.name }}")
        // One model that dies must not take the others with it, and must not be mistaken for
        // a run that finished either. Swallowing it entirely made a native crash and a
        // completed benchmark look identical in the result.
        val faults = present.flatMap { (name, file) ->
            runCatching { measure(name, file) }.getOrElse { failure ->
                Log.i(TAG, "ABORT model=$name reason=${failure.message}")
                listOf("$name aborted: ${failure.message}")
            }
        }
        if (faults.isNotEmpty()) Log.i(TAG, "FAULTS $faults")
        assertThat(present).isNotEmpty()
        // Reported as a failure, because a table with a hole in it is not a result.
        assertWithMessage("generations that did not produce an answer").that(faults).isEmpty()
    }

    /** Scores one model and returns whatever went wrong that was not the model's judgement. */
    private suspend fun measure(name: String, file: File): List<String> {
        val engine: InferenceEngine = LlamaCppEngine()
        try {
            engine.load(file, ModelLoadParams(contextLength = CONTEXT))
            // Not a reason to skip any more. A template that drops tool definitions gets them
            // in the system message instead, which is what the app now does, so what is
            // measured here is the route each model actually takes.
            val native = engine.loadedModel?.supportsTools == true
            // The size as well as the label, because the label is a key in a map and the file
            // under it is whatever somebody pushed. A run that reported lfm2-1.2b while
            // measuring LFM2.5-2.6B is a table with the wrong name on a row.
            Log.i(TAG, "ROUTE model=$name native=$native file=${file.name} bytes=${file.length()}")
            probeRendering(engine, name, native)

            val scored = ARMS.map { arm -> arm to score(engine, name, arm, native) }
            val byLabel = scored.associate { (arm, result) -> arm.label to result }
            // Only the two arms that differ in nothing on a native template. The other two
            // differ on purpose, and comparing them here would call a finding a fault.
            if (native) reportDeterminism(name, listOfNotNull(byLabel["bare"], byLabel["tagged"]))
            reportDifference(name, "ORDERING", byLabel["bare"], byLabel["reversed"])
            reportDifference(name, "CACHE", byLabel["bare"], byLabel["warm"])
            return scored.flatMap { (arm, result) ->
                List(result.tally.errored) { "$name/${arm.label} generation failed" }
            }
        } finally {
            engine.close()
        }
    }

    /**
     * Whether the tool definitions reach the model at all, in tokens rather than in trust.
     *
     * `supportsTools` is a test on the template, and it said yes for two models that then
     * never called anything in twenty four tries. That reading is compatible with two
     * opposite explanations, so this asks the only question that separates them: does adding
     * the tools change what the engine has to prefill. The context is cleared either side,
     * because `promptTokens` counts what was processed after cache reuse and would otherwise
     * report the second prompt as nearly free.
     */
    private suspend fun probeRendering(engine: InferenceEngine, name: String, native: Boolean) {
        if (!native) return
        val question = listOf(ChatMessage.text(ChatRole.USER, CASES.first().prompt))
        val bare = prefillOf(engine, question, emptyList())
        val armed = prefillOf(engine, question, catalogue())
        Log.i(TAG, "RENDER model=$name without=$bare with=$armed delta=${armed - bare}")
    }

    private suspend fun prefillOf(
        engine: InferenceEngine,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
    ): Int {
        engine.resetContext()
        val params = SHIPPED.copy(maxTokens = 1, seed = SEED, temperature = 0f)
        return engine.chat(messages, params, tools).toList()
            .filterIsInstance<GenerationEvent.Completed>()
            .single().stats.promptTokens
    }

    private suspend fun score(
        engine: InferenceEngine,
        model: String,
        arm: Arm,
        native: Boolean,
    ): Scored {
        val tally = Tally()
        val chosen = mutableListOf<String?>()
        for (case in CASES) {
            val began = System.currentTimeMillis()
            val choice = runCatching { chosenTool(engine, case.prompt, arm, native) }
                .getOrElse {
                    Log.i(TAG, "FAILED model=$model arm=${arm.label} why=${it.message}")
                    Choice(null, Route.ERROR)
                }
            tally.millis += System.currentTimeMillis() - began
            tally.count(case, choice)
            chosen += choice.tool
        }
        Log.i(
            TAG,
            "SCORE model=$model arm=${arm.label} right=${tally.right}/${CASES.size} " +
                "over=${tally.overCall} under=${tally.underCall} wrong=${tally.wrongTool} " +
                "errors=${tally.errored} ms=${tally.millis / CASES.size}",
        )
        return Scored(tally, chosen)
    }

    private fun Tally.count(case: Case, choice: Choice) {
        if (choice.route == Route.ERROR) {
            errored++
            return
        }
        when {
            choice.tool == case.expects -> right++
            case.expects == null -> overCall++
            choice.tool == null -> underCall++
            else -> wrongTool++
        }
    }

    /**
     * Whether the same prompt twice gave the same answer twice.
     *
     * The seed count rests on this. Three trials is right for a stochastic agent and the
     * public suites use it for that reason; at temperature zero with a fixed prompt there is
     * no distribution to sample, so one is right and three are waste. That argument is only
     * as good as the claim underneath it, so the claim is measured rather than assumed: on a
     * native template the two arms differ in nothing, and the second runs on a cache the
     * first one warmed, so identical tallies say both that the sampler is deterministic and
     * that reuse does not perturb it.
     */
    private fun reportDeterminism(model: String, arms: List<Scored>) {
        // Case by case rather than on the totals, which two arms could match while
        // disagreeing about every question in a way that cancelled out.
        val identical = arms.distinctBy { it.chosen }.size == 1
        Log.i(TAG, "DETERMINISM model=$model identical=$identical picked=${arms.first().chosen}")
        assertWithMessage("$model gave different answers to identical prompts")
            .that(identical).isTrue()
    }

    /**
     * How much two arms disagreed, case by case.
     *
     * A count rather than a verdict. Both of these are questions nobody here has an answer
     * to: whether a tool listed first is picked more often, and whether the same prompt
     * routes differently over a warm cache than a cold one. Either coming back non-zero
     * would mean every cold number in this file describes one of two behaviours.
     */
    private fun reportDifference(model: String, what: String, base: Scored?, other: Scored?) {
        if (base == null || other == null) return
        val differing = base.chosen.zip(other.chosen).count { (one, two) -> one != two }
        Log.i(
            TAG,
            "$what model=$model differing=$differing/${base.chosen.size} " +
                "right=${base.tally.right}->${other.tally.right} " +
                "base=${base.chosen} other=${other.chosen}",
        )
    }

    private suspend fun chosenTool(
        engine: InferenceEngine,
        prompt: String,
        arm: Arm,
        native: Boolean,
    ): Choice {
        // The same split TurnRunner makes: a template that can carry the definitions gets
        // them, and one that cannot gets them written into what it can carry.
        val tools = catalogue().let { if (arm.reversed) it.reversed() else it }
        val described =
            if (native) "" else "\n\n" + ToolPrompting.describe(tools, arm.format)
        val messages = listOf(
            ChatMessage.text(ChatRole.SYSTEM, system() + described),
            ChatMessage.text(ChatRole.USER, prompt),
        )
        // Greedy, because TurnRunner forces temperature zero while tools are on the table and
        // an arm sampled any other way is measuring behaviour the app stopped having.
        val params = SHIPPED.copy(maxTokens = BUDGET, seed = SEED, temperature = 0f)
        val offered = if (native) tools else emptyList()
        // Cleared before each case, so a case is not measured on the cache the previous one
        // left. It also keeps the window from filling over a run, which is what
        // llama_decode returned 1 was: the sixth model died partway through the old suite
        // and took its remaining arms with it.
        //
        // Except in the warm arm, which exists because clearing it is exactly what makes
        // every other number here a cold one. In the app the second pass of a turn reads a
        // cache the first pass filled, and whether that changes which tool comes back had
        // never been asked.
        engine.resetContext()
        if (arm.warm) warmTheCache(engine, messages, offered)
        val completed = engine.chat(messages, params, tools = offered)
            .toList()
            .filterIsInstance<GenerationEvent.Completed>()
            .single()
        return read(completed)
    }

    /**
     * Puts this exact prompt through once and throws the answer away.
     *
     * What is left behind is the whole prompt in the cache, which is the state the app is in
     * on the second and later passes of a turn. The reuse walk then matches every token and
     * decodes only the last one, so the arithmetic is the same as a cold run everywhere
     * except in the kernels: cold prefill runs the prompt as a few large batches, warm reads
     * it back out of the cache. If those disagree, greedy routing can flip on it, because at
     * temperature zero the decision is an argmax over logits that are often close.
     */
    private suspend fun warmTheCache(
        engine: InferenceEngine,
        messages: List<ChatMessage>,
        offered: List<ToolDefinition>,
    ) {
        val once = SHIPPED.copy(maxTokens = 1, seed = SEED, temperature = 0f)
        engine.chat(messages, once, tools = offered).toList()
    }

    /**
     * Which of the two routes found a call, in the order [TurnRunner] tries them.
     *
     * There were three. The third read a tool's name out of ordinary prose and built the call
     * from the question, and counting it apart here is what retired it: across two runs and
     * six models it fired seven times, improved a score never, and cost one twice. What
     * replaced it is a repair round, which cannot be scored from a single generation because
     * it spends a pass to get a real call.
     */
    private fun read(completed: GenerationEvent.Completed): Choice {
        completed.toolCalls.firstOrNull()?.let { return Choice(it.name, Route.NATIVE) }
        ToolPrompting.parse(completed.content, registry())
            ?.let { return Choice(it.name, Route.PROMPTED) }
        return Choice(null, Route.NONE)
    }

    /**
     * What the app would really offer, whatever that turns out to be here.
     *
     * Filtered by [io.github.alpharomercoma.openweights.core.tools.Tool.isAvailable] rather
     * than listed, because the file tools describe themselves only once a folder has been
     * shared, and whether one has been is a property of the device this runs on: a grant taken
     * through the picker outlives the app that took it. So the size of the catalogue is logged
     * rather than assumed. Reading it off the log is the only way to know which of the two
     * arrangements a given run measured.
     */
    private fun registry(): ToolRegistry {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val client = OkHttpClient()
        val workspace = Workspace(context, WorkspaceGrant(context))
        return ToolRegistry(
            listOf(
                WebSearchTool(client, SearchSettings(context)),
                FetchUrlTool(client),
                SearchFilesTool(workspace),
                ReadFileTool(workspace),
                WriteFileTool(workspace),
                RunScriptTool(Sandbox(context), workspace),
            ).filter { it.isAvailable },
        )
    }

    private fun catalogue(): List<ToolDefinition> = registry().definitions

    /** Assembled the way ChatViewModel assembles it, date included, or it is not the app. */
    private fun system(): String =
        "Today is ${LocalDate.now()}.\n\n$ANSWER_STYLE\n\n${ModelPreferences.DEFAULT_TOOL_PROMPT}"

    /**
     * Internal rather than private so [RawReplyProbe] can run the same models through the same
     * sampler. The probe exists to explain rows this scores as nulls, and it can only do that
     * if the two are demonstrably measuring one thing.
     */
    internal companion object {
        const val TAG = "OpenWeightsChoice"
        const val CONTEXT = 4096

        /**
         * Short, because a tool call is short.
         *
         * The app lets an answer run to a thousand tokens, which is right for prose and wrong
         * for a decision: a model about to emit a malformed call emits it in the first fifty
         * tokens and spends the rest of the budget elaborating on it.
         */
        const val BUDGET = 160

        /**
         * One seed, and the same one every run.
         *
         * At temperature zero it selects nothing, so this is reproducibility rather than
         * sampling. [reportDeterminism] is what makes that safe to rely on.
         */
        const val SEED = 1

        val SHIPPED: SamplerParams = ModelPreferences().toSamplerParams()

        /** Copied from ChatViewModel, which keeps it private, and the first thing sent. */
        const val ANSWER_STYLE =
            "Answer from what you know, in a few sentences. Reply with the answer itself."

        /**
         * Two arms, where there were four.
         *
         * The wordings that used to be here were `superseded`, which is the instruction this
         * one replaced, and `restrained`, which led with the refusal instead of the triggers.
         * Both are retired against their own numbers rather than on taste: on the model they
         * were written for, restrained scored 6/24 against the shipped wording's 9/24, and on
         * three of the six models every wording scored exactly the same. There is nothing
         * left in that dimension to find, so the runs move to one where the last measurement
         * had nothing to say at all.
         */
        val ARMS = listOf(
            Arm("bare"),
            Arm("tagged", format = CallFormat.TAGGED),
            // Whether being listed first is worth anything. Ordered choices are known to be
            // order sensitive in larger models, and nothing says a 1B is less so, but the
            // size of it on a catalogue of three had never been measured here.
            Arm("reversed", reversed = true),
            // And whether the answer depends on the cache it was asked over, which is the
            // question every other arm assumes away by clearing it first.
            Arm("warm", warm = true),
        )

        /**
         * Six, covering each tool once and each way of being wrong once.
         *
         * Half need a tool and half do not, so a model that always calls and a model that
         * never calls both score fifty percent and neither can look competent. Each of the
         * three tools in the shipped catalogue is the right answer exactly once, because a
         * catalogue is chosen among rather than accepted, and the arithmetic one is there
         * because deciding to compute is a different judgement from deciding to look up.
         *
         * It was eight, with a second recency question and a second settled fact. Those two
         * measured what their neighbours already did.
         */
        val CASES = listOf(
            Case("What is the weather in Manila right now?", "web_search"),
            Case("Read https://example.com and tell me what it says.", "fetch_url"),
            Case("What is 48273 times 1179?", "run_script"),
            Case("What is the capital of France?", null),
            Case("Translate 'good morning' into Spanish.", null),
            Case("Write a haiku about rain.", null),
        )

        val BENCH = File("/data/local/tmp/openweights/bench")

        /**
         * Whatever is on the device, which is how the subset is chosen.
         *
         * The three worth pushing are qwen, gemma and llama: one template that renders tools,
         * one that does not, and one at twice the parameters. Phi and granite stay in the map
         * because they cost a minute each now and cover two more template families; lfm is
         * the one to drop first if anything has to go, since it shares gemma's route and was
         * the model that used to take the suite down with it.
         *
         * The last two are a different kind of candidate and are here to be measured against
         * the rest rather than to cover another template. Every model above is a general chat
         * model asked to acquire a calling policy afterwards, and the table says the best of
         * them manages four of six. Hammer is Qwen 2.5 fine-tuned for function calling, at
         * Q4_0 rather than Q4_K_M because the q6_K tensors in Q4_K_M have no KleidiAI kernel
         * and cost nearly half the decode rate. FunctionGemma is 270M, small enough that if
         * it can route at all it can route in front of something else.
         */
        val MODELS = mapOf(
            "qwen2.5-1.5b" to File(BENCH, "qwen.gguf"),
            "gemma3-1b" to File(BENCH, "gemma.gguf"),
            "llama3.2-3b" to File(BENCH, "llama.gguf"),
            "lfm2-1.2b" to File(BENCH, "lfm.gguf"),
            "phi4-mini" to File(BENCH, "phi.gguf"),
            "granite3.3-2b" to File(BENCH, "granite.gguf"),
            "hammer2.1-1.5b" to File(BENCH, "hammer.gguf"),
            "functiongemma-270m" to File(BENCH, "fgemma.gguf"),
        )
    }
}
