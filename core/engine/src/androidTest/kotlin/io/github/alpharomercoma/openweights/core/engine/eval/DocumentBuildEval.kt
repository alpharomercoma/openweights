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
 * The document half of the agentic path, graded the way [WebsiteBuildEval] grades the
 * site half: a real model, the app's own tool semantics, and the output judged as an
 * artifact rather than as prose.
 *
 * The grading is structural because a Markdown document's quality *is* structure plus
 * facts: a heading, real sections, and content about the thing asked for. A reply that
 * dumped one paragraph into a file would pass a length check and fail this.
 */
@RunWith(AndroidJUnit4::class)
class DocumentBuildEval {

    @Test
    fun writesAStructuredDocument(): Unit = runBlocking {
        val model = WebsiteBuild.EVAL_DIR
            .listFiles { file -> file.name == GGUF_BUILDER }?.firstOrNull()
        assumeTrue("no $GGUF_BUILDER in ${WebsiteBuild.EVAL_DIR}", model != null)

        val folder = InstrumentationRegistry.getInstrumentation()
            .targetContext.filesDir.resolve("e2e-doc").apply {
                deleteRecursively()
                mkdirs()
            }

        var shown: String? = null
        val messages = mutableListOf(ChatMessage.text(ChatRole.USER, ASK))

        LlamaCppEngine().use { engine ->
            engine.load(model!!, ModelLoadParams(contextLength = 8192))
            var rounds = 0
            var nudged = false
            while (rounds < MAX_ROUNDS && shown == null) {
                rounds += 1
                val events = engine.chat(messages, WebsiteBuild.PARAMS, TOOLS).toList()
                val done = events.filterIsInstance<GenerationEvent.Completed>().single()
                val raw = events.filterIsInstance<GenerationEvent.Token>()
                    .joinToString("") { it.text }
                messages += ChatMessage.text(ChatRole.ASSISTANT, raw.ifEmpty { done.content })

                if (done.toolCalls.isEmpty()) {
                    if (!nudged && File(folder, "notes.md").isFile) {
                        nudged = true
                        messages += ChatMessage.text(
                            ChatRole.USER,
                            "Now call show_document with the file's path.",
                        )
                        continue
                    }
                    break
                }
                done.toolCalls.forEach { call ->
                    val result = execute(call, folder) { shown = it }
                    Log.i(TAG, "round $rounds ${call.name} -> $result")
                    messages += ChatMessage.toolResult(call.name, result)
                }
            }
        }

        val file = File(folder, "notes.md")
        assertThat(file.isFile).isTrue()
        val text = file.readText()
        Log.i(TAG, "wrote ${text.length} chars; shown=$shown")

        // Structure: a title and at least three section headings.
        assertThat(text.lines().count { it.trimStart().startsWith("#") }).isAtLeast(4)
        // Substance: it is about the subject asked for, not a template.
        val lowered = text.lowercase()
        assertThat(lowered).contains("mercury")
        assertThat(lowered).contains("venus")
        assertThat(lowered).contains("mars")
        assertThat(text.length).isGreaterThan(MIN_DOC_CHARS)
        assertThat(shown).isNotNull()
    }

    private fun execute(call: ToolCall, folder: File, onShow: (String) -> Unit): String {
        val arguments = org.json.JSONObject(call.argumentsJson.ifBlank { "{}" })
        return when (call.name) {
            "write_file" -> {
                val content = arguments.optString("content")
                if (content.isEmpty()) return "No content was given."
                File(folder, "notes.md").writeText(content)
                "Saved notes/notes.md with ${content.length} characters."
            }

            "show_document" -> {
                onShow(arguments.optString("path").ifEmpty { "notes/notes.md" })
                "Showing the document to the user. Further saves update it live."
            }

            else -> "There is no tool called ${call.name}."
        }
    }

    private companion object {
        const val TAG = "DocumentBuildEval"
        const val GGUF_BUILDER = "Qwen3-1.7B-Q8_0.gguf"
        const val MAX_ROUNDS = 6
        const val MIN_DOC_CHARS = 500

        val ASK = "Write a short reference document about the four inner planets of the " +
            "solar system in Markdown: a title, then one section per planet with two " +
            "or three factual sentences each. Save it as notes/notes.md, then show it " +
            "to me."

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
                name = "show_document",
                description = "Show a Markdown document you saved to the user, " +
                    "rendered live. Call it once after the first save.",
                parametersJson = """{"type": "object", "properties": {"path": """ +
                    """{"type": "string"}}, "required": ["path"]}""",
            ),
        )
    }
}
