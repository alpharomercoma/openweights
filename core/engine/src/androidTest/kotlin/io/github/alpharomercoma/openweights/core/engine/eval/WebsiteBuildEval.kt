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
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import io.github.alpharomercoma.openweights.core.common.model.ToolDefinition
import io.github.alpharomercoma.openweights.core.engine.GenerationEvent
import io.github.alpharomercoma.openweights.core.engine.InferenceEngine
import io.github.alpharomercoma.openweights.core.engine.LlamaCppEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The whole agentic development path, end to end, against a real model on a real phone.
 *
 * The model is given the same write_file and show_website tools the app offers, asked for
 * a small site, and its calls are executed against a real folder. What it built is then
 * loaded into a real WebView and the DOM is interrogated — not "did it answer", but "does
 * the page it made actually render into elements". This is the test that the canvas
 * feature's promise survives contact with a 1.7B model.
 *
 * Engine-level rather than through the app's UI, because the folder tools sit behind a
 * Storage Access Framework grant that only a person can give. The tool loop here is the
 * same shape TurnRunner drives: definitions in, calls out, results fed back as tool turns.
 */
@RunWith(AndroidJUnit4::class)
class WebsiteBuildEval {

    @Test
    fun buildsAWorkingWebsite(): Unit = runBlocking {
        val model = WebsiteBuild.EVAL_DIR
            .listFiles { file -> file.name == GGUF_BUILDER }?.firstOrNull()
        assumeTrue("no $GGUF_BUILDER in ${WebsiteBuild.EVAL_DIR}", model != null)

        LlamaCppEngine().use { engine ->
            engine.load(model!!, ModelLoadParams(contextLength = 8192))
            WebsiteBuild.build(engine)
        }
    }

    private companion object {
        const val GGUF_BUILDER = "Qwen3-1.7B-Q8_0.gguf"
    }
}

/** The build loop and its grading, shared by both engines' tests. */
internal object WebsiteBuild {

    suspend fun build(engine: InferenceEngine) {
        val site = InstrumentationRegistry.getInstrumentation()
            .targetContext.filesDir.resolve("e2e-site").apply {
                deleteRecursively()
                mkdirs()
            }

        var shown: String? = null
        val messages = mutableListOf<ChatMessage>(
            ChatMessage.text(
                ChatRole.USER,
                "Build a small single-page website about the phases of the moon: a " +
                    "heading, a short paragraph for each of the four main phases, and " +
                    "simple CSS so it looks deliberate. Save it as site/index.html with " +
                    "the CSS inline, then show it to me.",
            ),
        )

        var rounds = 0
        while (rounds < MAX_ROUNDS && shown == null) {
            rounds += 1
            val events = engine.chat(messages, PARAMS, TOOLS).toList()
            val done = events.filterIsInstance<GenerationEvent.Completed>().single()
            val raw = events.filterIsInstance<GenerationEvent.Token>()
                .joinToString("") { it.text }
            messages += ChatMessage.text(ChatRole.ASSISTANT, raw.ifEmpty { done.content })

            if (done.toolCalls.isEmpty()) break
            done.toolCalls.forEach { call ->
                val result = execute(call, site) { shown = it }
                Log.i(TAG, "round $rounds ${call.name} -> $result")
                messages += ChatMessage.toolResult(call.name, result)
            }
        }

        val index = File(site, "index.html")
        assertThat(index.isFile).isTrue()
        val html = index.readText()
        Log.i(TAG, "built ${html.length} chars in $rounds rounds; shown=$shown")
        assertThat(html.length).isGreaterThan(MIN_PAGE_CHARS)
        assertThat(html.lowercase()).contains("<html")
        assertThat(html.lowercase()).contains("moon")

        val elements = renderedElementCount(index)
        Log.i(TAG, "rendered element count: $elements")
        // A page that renders into a real tree, not an unparsed blob: a heading and four
        // paragraphs cannot come in under a dozen elements.
        assertThat(elements).isAtLeast(MIN_ELEMENTS)
        assertThat(shown).isNotNull()
    }

    /** The app's own tool semantics, executed against a plain folder. */
    private fun execute(call: ToolCall, site: File, onShow: (String) -> Unit): String {
        val arguments = org.json.JSONObject(call.argumentsJson.ifBlank { "{}" })
        return when (call.name) {
            "write_file" -> {
                val path = arguments.optString("path").ifEmpty { "site/index.html" }
                val content = arguments.optString("content")
                if (content.isEmpty()) return "No content was given."
                val target = File(site, path.substringAfterLast('/'))
                target.writeText(content)
                "Saved $path with ${content.length} characters."
            }

            "show_website" -> {
                val path = arguments.optString("path").ifEmpty { "site/index.html" }
                onShow(path)
                "Showing $path to the user. Further saves update the page live."
            }

            else -> "There is no tool called ${call.name}."
        }
    }

    /** Loads the page in a real WebView and counts the elements the DOM parsed it into. */
    private suspend fun renderedElementCount(page: File): Int {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val counted = CompletableDeferred<Int>()
        instrumentation.runOnMainSync {
            val web = WebView(instrumentation.targetContext)
            web.settings.javaScriptEnabled = true
            web.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript("document.querySelectorAll('*').length") { value ->
                        counted.complete(value?.toIntOrNull() ?: 0)
                    }
                }
            }
            web.loadUrl("file://${page.absolutePath}")
        }
        return withTimeout(RENDER_TIMEOUT_MS) { counted.await() }
    }

    private const val TAG = "WebsiteBuildEval"
    private const val MAX_ROUNDS = 6
    private const val MIN_PAGE_CHARS = 400
    private const val MIN_ELEMENTS = 12
    private const val RENDER_TIMEOUT_MS = 20_000L
    val EVAL_DIR = File("/data/local/tmp/openweights/eval")

    private val PARAMS = SamplerParams(temperature = 0f, topK = 1, seed = 7, maxTokens = 2048)

    private val TOOLS = listOf(
        ToolDefinition(
            name = "write_file",
            description = "Save a file into the shared folder. Pass replace to " +
                "overwrite one that exists.",
            parametersJson = """{"type": "object", "properties": {"path": """ +
                """{"type": "string"}, "content": {"type": "string"}, "replace": """ +
                """{"type": "boolean"}}, "required": ["path", "content"]}""",
        ),
        ToolDefinition(
            name = "show_website",
            description = "Show an HTML page you saved to the user, rendered live. " +
                "Call it once after the first save.",
            parametersJson = """{"type": "object", "properties": {"path": """ +
                """{"type": "string"}}, "required": ["path"]}""",
        ),
    )
}
