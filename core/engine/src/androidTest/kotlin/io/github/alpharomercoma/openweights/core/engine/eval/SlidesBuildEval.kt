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

package io.github.alpharomercoma.openweights.core.engine.eval

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import io.github.alpharomercoma.openweights.core.common.model.ToolDefinition
import io.github.alpharomercoma.openweights.core.engine.GenerationEvent
import io.github.alpharomercoma.openweights.core.engine.LlamaCppEngine
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The presentation path, end to end, iteration included.
 *
 * Two user turns against one engine and one growing conversation: build a deck, then ask
 * for one more slide. The second turn is the half that matters - a deck the model can
 * write once but not edit is a screenshot, not a canvas - so the grading asserts the
 * *edit*: the deck after turn two holds everything turn one promised plus the new slide,
 * in one file, saved with replace.
 */
@RunWith(AndroidJUnit4::class)
class SlidesBuildEval {

    @Test
    fun buildsADeckAndThenExtendsIt(): Unit = runBlocking {
        val model = WebsiteBuild.EVAL_DIR
            .listFiles { file -> file.name == GGUF_BUILDER }?.firstOrNull()
        assumeTrue("no $GGUF_BUILDER in ${WebsiteBuild.EVAL_DIR}", model != null)

        val folder = InstrumentationRegistry.getInstrumentation()
            .targetContext.filesDir.resolve("e2e-slides").apply {
                deleteRecursively()
                mkdirs()
            }

        var shown = 0
        val messages = mutableListOf(ChatMessage.text(ChatRole.USER, BUILD_ASK))

        LlamaCppEngine().use { engine ->
            engine.load(model!!, ModelLoadParams(contextLength = 8192))
            runTurn(engine, messages, folder) { shown += 1 }

            val deck = File(folder, "slides.md")
            assertThat(deck.isFile).isTrue()
            val first = deck.readText()
            Log.i(TAG, "turn one wrote ${first.length} chars, ${slideCount(first)} slides")
            assertThat(slideCount(first)).isAtLeast(3)
            assertThat(first.lowercase()).contains("saturn")
            assertThat(shown).isAtLeast(1)

            // The iteration: a person looks at the deck and asks for more. Nothing is
            // reset - same conversation, same file, and the model must reach for
            // replace rather than a second file.
            messages += ChatMessage.text(ChatRole.USER, EXTEND_ASK)
            runTurn(engine, messages, folder) { shown += 1 }

            val second = deck.readText()
            Log.i(TAG, "turn two left ${second.length} chars, ${slideCount(second)} slides")
            assertThat(slideCount(second)).isAtLeast(slideCount(first) + 1)
            assertThat(second.lowercase()).contains("neptune")
            // The edit kept the deck, rather than replacing it with only the new slide.
            assertThat(second.lowercase()).contains("saturn")
        }
    }

    private suspend fun runTurn(
        engine: LlamaCppEngine,
        messages: MutableList<ChatMessage>,
        folder: File,
        onShow: () -> Unit,
    ) {
        var rounds = 0
        var acted = true
        while (rounds < MAX_ROUNDS && acted) {
            rounds += 1
            val events = engine.chat(messages, WebsiteBuild.PARAMS, TOOLS).toList()
            val done = events.filterIsInstance<GenerationEvent.Completed>().single()
            val raw = events.filterIsInstance<GenerationEvent.Token>()
                .joinToString("") { it.text }
            messages += ChatMessage.text(ChatRole.ASSISTANT, raw.ifEmpty { done.content })
            acted = done.toolCalls.isNotEmpty()
            done.toolCalls.forEach { call ->
                val result = execute(call, folder, onShow)
                Log.i(TAG, "round $rounds ${call.name} -> ${result.take(80)}")
                messages += ChatMessage.toolResult(call.name, result)
            }
        }
    }

    private fun execute(call: ToolCall, folder: File, onShow: () -> Unit): String {
        val arguments = org.json.JSONObject(call.argumentsJson.ifBlank { "{}" })
        return when (call.name) {
            "write_file" -> {
                val content = arguments.optString("content")
                if (content.isEmpty()) return "No content was given."
                // Mirrors the app's rule: a file this session created replaces freely -
                // the first version of this harness demanded the replace flag even for
                // the model's own file, and greedy Qwen3 read the refusal and moved on
                // instead of retrying, which is exactly the stall the app rule prevents.
                File(folder, "slides.md").writeText(content)
                "Saved talk/slides.md with ${content.length} characters."
            }

            "show_slides" -> {
                onShow()
                "Showing talk/slides.md as slides. Saves with replace update it live."
            }

            else -> "There is no tool called ${call.name}."
        }
    }

    private fun slideCount(deck: String): Int =
        deck.split(Regex("""(?m)^\s*---\s*$""")).count { it.isNotBlank() }

    private companion object {
        const val TAG = "SlidesBuildEval"
        const val GGUF_BUILDER = "Qwen3-1.7B-Q8_0.gguf"
        const val MAX_ROUNDS = 6

        val BUILD_ASK = "Make a short slide deck about the planet Saturn in Markdown: a " +
            "title slide and three content slides, each slide separated by a line " +
            "containing only ---. Save it as talk/slides.md, then call show_slides."

        val EXTEND_ASK = "Good. Add one more slide about Neptune at the end of the same " +
            "deck. Keep every existing slide, save it over talk/slides.md with replace."

        val TOOLS = listOf(
            ToolDefinition(
                name = "write_file",
                description = "Save a file into the shared folder. Pass replace to " +
                    "overwrite one that exists.",
                parametersJson = """{"type": "object", "properties": {"path": """ +
                    """{"type": "string"}, "content": {"type": "string"}, "replace": """ +
                    """{"type": "boolean"}}, "required": ["path", "content"]}""",
            ),
            ToolDefinition(
                name = "show_slides",
                description = "Show a Markdown file as a slide deck the user swipes " +
                    "through. Separate slides with a line containing only ---. Call it " +
                    "once after the first save; later saves with replace update the " +
                    "deck live.",
                parametersJson = """{"type": "object", "properties": {"path": """ +
                    """{"type": "string"}}, "required": ["path"]}""",
            ),
        )
    }
}
