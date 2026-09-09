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
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.common.model.ToolDefinition
import io.github.alpharomercoma.openweights.core.engine.GenerationEvent
import io.github.alpharomercoma.openweights.core.engine.InferenceEngine
import io.github.alpharomercoma.openweights.core.engine.LlamaCppEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * How often a page a small model builds raises an error the model never sees.
 *
 * The measurement that decides whether the canvas gets a grader. The canvas renders what
 * the model saved and the WebView's own errors go to a console nobody reads; a grader
 * would hand them back as the tool's result, at the price of a page load per save and a
 * retry round per error. Whether that price is worth paying is a rate, and this is where
 * the rate comes from: ten asks that a person would plausibly make, six of them wanting
 * scripts because that is where pages break, each built by the tool loop the app runs and
 * loaded into a real WebView with every uncaught error, failed resource and console error
 * counted.
 *
 * Nothing here asserts; a census is not a test. The rows go to logcat under [TAG] and the
 * pages stay under the test app's files (`run-as ... cat files/census/...`) so a page that
 * raised can be read afterwards.
 */
@RunWith(AndroidJUnit4::class)
class CanvasErrorCensus {

    @Test
    fun countErrorsInBuiltPages(): Unit = runBlocking {
        // `-e models a.gguf,b.gguf` narrows the run, for a rerun after one model's crash.
        val wanted = InstrumentationRegistry.getArguments().getString("models")
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: MODELS
        val models = wanted.mapNotNull { name ->
            WebsiteBuild.EVAL_DIR.resolve(name).takeIf { it.isFile }
        }
        assumeTrue("no census models in ${WebsiteBuild.EVAL_DIR}", models.isNotEmpty())
        // The app's own files: /data/local/tmp is readable from here and not writable, the
        // mkdirs fails without a word and the first save throws. Pulled with run-as.
        val out = InstrumentationRegistry.getInstrumentation().targetContext.filesDir.resolve(
            "census",
        )
        out.mkdirs()
        Log.i(TAG, "START models=${models.map { it.name }} asks=${ASKS.size}")

        for (model in models) {
            LlamaCppEngine().use { engine ->
                engine.load(model, ModelLoadParams(contextLength = 4096))
                ASKS.forEachIndexed { index, ask ->
                    val site = out.resolve(model.nameWithoutExtension).resolve("ask-$index").apply {
                        deleteRecursively()
                        mkdirs()
                    }
                    val built = build(engine, ask.text, site)
                    val page = site.resolve("index.html")
                    if (!page.isFile) {
                        Log.i(
                            TAG,
                            "ROW model=${model.name} ask=$index id=${ask.id} built=false rounds=${built.rounds} calls=${built.calls}",
                        )
                        return@forEachIndexed
                    }
                    val report = errorsIn(page)
                    Log.i(
                        TAG,
                        "ROW model=${model.name} ask=$index id=${ask.id} built=true " +
                            "rounds=${built.rounds} calls=${built.calls} chars=${page.length()} " +
                            "files=${site.list()?.size} script=${page.readText().contains(
                                "<script",
                                ignoreCase = true,
                            )} " +
                            "elements=${report.elements} errors=${report.errors.size} " +
                            "resourceErrors=${report.resourceErrors.size}",
                    )
                    report.errors.forEach { Log.i(TAG, "  ERROR ask=$index ${it.take(300)}") }
                    report.resourceErrors.forEach {
                        Log.i(TAG, "  RESOURCE ask=$index ${it.take(200)}")
                    }
                }
            }
        }
        Log.i(TAG, "END")
    }

    private class Built(val rounds: Int, val calls: Int)

    /** The app's tool loop, the way [WebsiteBuild] runs it, with every file kept. */
    private suspend fun build(engine: InferenceEngine, ask: String, site: File): Built {
        val messages = mutableListOf<ChatMessage>(ChatMessage.text(ChatRole.USER, ask))
        var rounds = 0
        var calls = 0
        var shown = false
        while (rounds < MAX_ROUNDS && !shown) {
            rounds += 1
            val started = System.currentTimeMillis()
            val events = engine.chat(messages, PARAMS, TOOLS).toList()
            val done = events.filterIsInstance<GenerationEvent.Completed>().single()
            val raw = events.filterIsInstance<GenerationEvent.Token>().joinToString("") { it.text }
            Log.i(
                TAG,
                "  round $rounds ${System.currentTimeMillis() - started} ms, " +
                    "${raw.length} chars, ${done.toolCalls.size} calls",
            )
            messages += ChatMessage.text(ChatRole.ASSISTANT, raw.ifEmpty { done.content })
            if (done.toolCalls.isEmpty()) break
            done.toolCalls.forEach { call ->
                calls += 1
                // LFM2.5 wrote an unterminated object on the sixth ask; the app's argument
                // reader survives that, and so must the census.
                val arguments = runCatching {
                    org.json.JSONObject(call.argumentsJson.ifBlank { "{}" })
                }
                    .getOrElse { org.json.JSONObject() }
                val result = when (call.name) {
                    "write_file" -> {
                        val path = arguments.optString("path").ifEmpty { "site/index.html" }
                        val content = arguments.optString("content")
                        if (content.isEmpty()) {
                            "No content was given."
                        } else {
                            // Kept by base name in one folder, so a page's own style.css
                            // and app.js resolve beside it whatever folder was named.
                            site.resolve(path.substringAfterLast('/')).writeText(content)
                            "Saved $path with ${content.length} characters."
                        }
                    }
                    "show_website" -> {
                        shown = true
                        "Showing the page to the user. Further saves update it live."
                    }
                    else -> "There is no tool called ${call.name}."
                }
                messages += ChatMessage.toolResult(call.name, result)
            }
        }
        return Built(rounds, calls)
    }

    private class Report(
        val elements: Int,
        val errors: List<String>,
        val resourceErrors: List<String>,
    )

    /**
     * Loads the page as the canvas would and collects what a person would not see.
     *
     * Uncaught exceptions and unhandled rejections reach the console as error messages,
     * which is why the console is the catch; a stylesheet or script the page names and did
     * not save is a resource error. Served over file:// with file access on, which the app
     * never does, so that the page's relative links resolve the way they would over the
     * canvas's loopback server. The page is given a moment after load for timers and
     * handlers that fire on their own.
     */
    private suspend fun errorsIn(page: File): Report {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val errors = mutableListOf<String>()
        val resources = mutableListOf<String>()
        val loaded = CompletableDeferred<WebView>()
        instrumentation.runOnMainSync {
            val web = WebView(instrumentation.targetContext)
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.settings.allowFileAccess = true
            web.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        errors +=
                            "${message.message()} (${message.sourceId().substringAfterLast(
                                '/',
                            )}:${message.lineNumber()})"
                    }
                    return true
                }
            }
            web.webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (!request.isForMainFrame) {
                        resources +=
                            "${request.url.lastPathSegment}: ${error.description}"
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    response: WebResourceResponse,
                ) {
                    if (!request.isForMainFrame) {
                        resources +=
                            "${request.url.lastPathSegment}: HTTP ${response.statusCode}"
                    }
                }

                override fun onPageFinished(view: WebView, url: String) {
                    loaded.complete(view)
                }
            }
            web.loadUrl("file://${page.absolutePath}")
        }
        val web = withTimeoutOrNull(LOAD_TIMEOUT_MS) { loaded.await() }
        delay(SETTLE_MS)
        val counted = CompletableDeferred<Int>()
        instrumentation.runOnMainSync {
            if (web == null) {
                counted.complete(-1)
            } else {
                web.evaluateJavascript("document.querySelectorAll('*').length") { value ->
                    counted.complete(value?.toIntOrNull() ?: 0)
                }
            }
        }
        val elements = withTimeoutOrNull(LOAD_TIMEOUT_MS) { counted.await() } ?: -1
        // Torn down, or a page with a timer keeps a thread busy under the next build.
        instrumentation.runOnMainSync { web?.destroy() }
        return Report(elements, errors.toList(), resources.toList())
    }

    private class Ask(val id: String, val text: String)

    private companion object {
        const val TAG = "CanvasCensus"
        const val MAX_ROUNDS = 4
        const val LOAD_TIMEOUT_MS = 20_000L
        const val SETTLE_MS = 2_000L

        val MODELS = listOf("Qwen3-1.7B-Q8_0.gguf", "LFM2.5-1.2B-Instruct-Q4_K_M.gguf")

        // Thinking off, as the public benchmarks ran: with it on, Qwen3 spent twenty-seven
        // minutes on one to-do page while the phone swapped, and the census is about the
        // pages, not the reasoning. The cap is the build eval's.
        val PARAMS = SamplerParams(
            thinking = false,
            temperature = 0f,
            topK = 1,
            seed = 7,
            maxTokens = 2048,
        )

        const val SUFFIX = " Save it as site/index.html with the CSS and JavaScript inline, " +
            "then call show_website."

        val ASKS = listOf(
            Ask(
                "moon",
                "Build a small single-page website about the phases of the moon: a heading, a short paragraph for each of the four main phases, and simple CSS so it looks deliberate.$SUFFIX",
            ),
            Ask(
                "todo",
                "Build a to-do list web page: a text box, an Add button, and a list where each item has a Remove button. Adding and removing must work.$SUFFIX",
            ),
            Ask(
                "tip",
                "Build a tip calculator web page: inputs for the bill and the tip percentage, and the total updates as you type.$SUFFIX",
            ),
            Ask(
                "countdown",
                "Build a web page that counts down to the next New Year in days, hours, minutes and seconds, updating every second.$SUFFIX",
            ),
            Ask(
                "quiz",
                "Build a three-question multiple-choice quiz web page about geography that shows the score at the end.$SUFFIX",
            ),
            Ask(
                "coffee",
                "Build a landing page for a small coffee shop called Bean There: a navigation bar, a hero section, a menu section with four items and prices, and a contact section.$SUFFIX",
            ),
            Ask(
                "convert",
                "Build a unit converter web page between kilometres and miles that converts in both directions as you type.$SUFFIX",
            ),
            Ask(
                "draw",
                "Build a drawing pad web page using a canvas element where you can draw with a finger or mouse, with a colour picker and a Clear button.$SUFFIX",
            ),
            Ask(
                "portfolio",
                "Build a personal portfolio web page with three tabs, About, Projects and Contact, where clicking a tab shows only that section.$SUFFIX",
            ),
            Ask(
                "gallery",
                "Build a colour palette gallery web page: eight coloured tiles, and clicking a tile copies its hex code and shows a small toast saying it was copied.$SUFFIX",
            ),
        )

        val TOOLS = listOf(
            ToolDefinition(
                name = "write_file",
                description = "Save a file into the shared folder. Pass replace to overwrite " +
                    "one that exists.",
                parametersJson = """{"type": "object", "properties": {"path": """ +
                    """{"type": "string"}, "content": {"type": "string"}, "replace": """ +
                    """{"type": "boolean"}}, "required": ["path", "content"]}""",
            ),
            ToolDefinition(
                name = "show_website",
                description = "Show an HTML page you saved to the user, rendered live. Call it " +
                    "once after the first save.",
                parametersJson = """{"type": "object", "properties": {"path": """ +
                    """{"type": "string"}}, "required": ["path"]}""",
            ),
        )
    }
}
