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

package io.github.alpharomercoma.openweights.ui.discover

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.common.model.ToolDefinition
import io.github.alpharomercoma.openweights.core.data.ModelPreferences
import io.github.alpharomercoma.openweights.core.engine.GenerationEvent
import io.github.alpharomercoma.openweights.core.engine.InferenceEngine
import io.github.alpharomercoma.openweights.core.engine.LlamaCppEngine
import io.github.alpharomercoma.openweights.core.tools.AgentMode
import io.github.alpharomercoma.openweights.ui.chat.toolInstruction
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A regression test for a real bug, found live on-device: asked "Name three planets." with
 * tools switched on, LFM2.5-1.2B answered correctly but opened with "I'm sorry, but I don't
 * have a tool that can directly list planets" — an apology the app's own tool policy already,
 * explicitly forbade. codex and agy independently traced this to two compounding causes,
 * confirmed against this exact model:
 *
 * 1. The turn had reasoning switched off. A prior eval of this app (142 deterministic
 *    tool-calling cases) found that suppressing reasoning costs LFM2.5-2.6B 15.5 points and
 *    all of it is abstention — correctly deciding *not* to call a tool goes from 17/18 to
 *    5/18 with thinking off. Deciding "no tool fits, just answer" is itself a small reasoning
 *    step, and without it the model defaults to a confused, hedging reply instead of a clean
 *    one. [ChatViewModel.send] now forces `thinking = true` whenever tools are offered, for
 *    exactly this reason — this test simulates that fix directly rather than going through
 *    the ViewModel, since the model's own behavior is what's under test.
 * 2. [ModelPreferences.DEFAULT_TOOL_PROMPT] forbade saying "you lack a tool" but not
 *    explaining *why* the tools it does have don't fit — a narrower thing to say, and the
 *    exact loophole this model found. The prompt now closes it explicitly.
 *
 * Neither cause is provably the only one — see the qualifications either reviewer's own
 * notes carry — which is why this test runs with both fixes applied together rather than
 * isolating one.
 *
 * @see ModelPreferences.DEFAULT_TOOL_PROMPT
 */
@RunWith(AndroidJUnit4::class)
class ToolRefusalTest {
    @Test
    fun trivialQuestionsAreAnsweredWithoutApologisingForTheToolsOffered() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val modelsDir = File(context.getExternalFilesDir(null), "models")
        assumeTrue("no models directory at $modelsDir", modelsDir.isDirectory)

        val modelFile = File(modelsDir, "LFM2.5-1.2B-Instruct-QAD-Q4_0.gguf")
        assumeTrue("${modelFile.name} not downloaded", modelFile.isFile)

        val engine: InferenceEngine = LlamaCppEngine()
        try {
            engine.load(modelFile, ModelLoadParams(contextLength = CONTEXT))

            for (prompt in PROMPTS) {
                engine.resetContext()

                val messages = listOf(
                    ChatMessage.text(ChatRole.SYSTEM, ModelPreferences.DEFAULT_TOOL_PROMPT),
                    ChatMessage.text(ChatRole.USER, prompt),
                )
                val completed = engine.chat(
                    messages = messages,
                    // Greedy and the exact fix under test: thinking forced on because
                    // tools are offered, regardless of what the composer's toggle would
                    // otherwise say.
                    params = SamplerParams(
                        temperature = 0f,
                        thinking = true,
                        maxTokens = MAX_TOKENS,
                    ),
                    tools = TOOLS,
                ).toList().filterIsInstance<GenerationEvent.Completed>().single()

                Log.i(TAG, "prompt=\"$prompt\" reply=\"${completed.content.take(LOG_CHARS)}\"")

                // A trivial, memory-answerable question offering no reason to call a tool.
                // A real call here would be its own bug, of a different kind than the one
                // this test exists for.
                assertTrue(
                    "expected no tool call for \"$prompt\", got ${completed.toolCalls}",
                    completed.toolCalls.isEmpty(),
                )
                assertTrue("expected a real answer for \"$prompt\"", completed.content.isNotBlank())

                val lowercased = completed.content.lowercase()
                for (phrase in BANNED_PHRASES) {
                    assertFalse(
                        "reply to \"$prompt\" contains the banned phrase \"$phrase\": " +
                            completed.content,
                        lowercased.contains(phrase),
                    )
                }
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun autoModeRequiresToolIsCalledDirectlyWithoutAsking() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val modelsDir = File(context.getExternalFilesDir(null), "models")
        assumeTrue("no models directory at $modelsDir", modelsDir.isDirectory)

        val models = MODEL_NAMES.mapNotNull { name ->
            File(modelsDir, name).takeIf { it.isFile }
        }
        assumeTrue("none of the expected models are downloaded", models.isNotEmpty())

        // A prompt that genuinely needs a search, matching the reported bug.
        val prompt = "Who is King Richard of Normandy?"

        for (modelFile in models) {
            val engine: InferenceEngine = LlamaCppEngine()
            try {
                engine.load(modelFile, ModelLoadParams(contextLength = CONTEXT))
                engine.resetContext()

                // The real production function, not a hand-copied string: this is what
                // actually reaches the model in Auto mode, and a test that duplicated the
                // wording would drift from it silently the next time that wording changed.
                val autoInstruction = requireNotNull(
                    toolInstruction(
                        mode = AgentMode.AUTO,
                        configured = ModelPreferences.DEFAULT_TOOL_PROMPT,
                        anyTools = true,
                    ),
                )

                val messages = listOf(
                    ChatMessage.text(ChatRole.SYSTEM, autoInstruction),
                    ChatMessage.text(ChatRole.USER, prompt),
                )
                val completed = engine.chat(
                    messages = messages,
                    params = SamplerParams(
                        temperature = 0f,
                        thinking = true,
                        maxTokens = MAX_TOKENS,
                    ),
                    tools = TOOLS,
                ).toList().filterIsInstance<GenerationEvent.Completed>().single()

                val reply = completed.content.take(LOG_CHARS)
                Log.i(TAG, "model=${modelFile.name} prompt=\"$prompt\" reply=\"$reply\"")

                assertTrue(
                    "expected a tool call for \"$prompt\" on model ${modelFile.name}, got none",
                    completed.toolCalls.isNotEmpty(),
                )
            } finally {
                engine.close()
            }
        }
    }

    private companion object {
        const val TAG = "OWToolRefusal"
        const val CONTEXT = 4096
        const val MAX_TOKENS = 128
        const val LOG_CHARS = 200

        /**
         * Trivial, unambiguously answerable from memory, and none of them look like a tool
         * would help — the same shape as the two prompts that reproduced the bug live.
         */
        val PROMPTS = listOf(
            "Say hello in five words.",
            "Name three planets.",
            "What is the capital of France?",
            "Who wrote Romeo and Juliet?",
        )

        /** The same two tools offered live when the bug reproduced. */
        val TOOLS = listOf(
            ToolDefinition(
                name = "web_search",
                description = "Search the web",
                parametersJson = """{"type":"object","properties":{"query":{"type":"string"}}}""",
            ),
            ToolDefinition(
                name = "fetch_url",
                description = "Fetch a webpage",
                parametersJson = """{"type":"object","properties":{"url":{"type":"string"}}}""",
            ),
        )

        /**
         * What the model actually said, and the shapes of that same failure. Scoped to this
         * curated, unambiguously-answerable prompt set on purpose: a *legitimate* refusal —
         * asked for something no tool here and no memory could answer — would trip the same
         * phrases and should, since nothing in [PROMPTS] should ever produce one.
         */
        val BANNED_PHRASES = listOf(
            "don't have a tool",
            "do not have a tool",
            "lack a tool",
            "no tool that",
            "no tool to",
            "no function to",
            "available functions",
            "available tools",
            "cannot look things up",
            "can't look things up",
            "no access to external information",
            "i cannot search",
            "i can't search",
            "i'm sorry, but",
        )
    }
}
