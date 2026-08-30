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

package io.github.alpharomercoma.openweights.ui.markdown

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.data.ModelPreferences
import io.github.alpharomercoma.openweights.core.engine.GenerationEvent
import io.github.alpharomercoma.openweights.core.engine.LlamaCppEngine
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What the models on this phone actually write when asked for demanding Markdown.
 *
 * An instrument rather than a test: it asserts almost nothing and is read. Half the
 * question "is our Markdown any good" belongs to the renderer, which
 * [MarkdownTortureOnDeviceTest] answers with hand-written hostile input; this is the other
 * half, where the input is whatever a 1.2B model on a phone decides to emit. The replies
 * are written to disk so the renderer can then be pointed at real output rather than at
 * markdown a person wrote to be difficult.
 */
@RunWith(AndroidJUnit4::class)
class MarkdownAbilityProbe {

    @Test
    fun showsWhatEachModelWrites() = runBlocking<Unit> {
        val models = File(
            InstrumentationRegistry.getInstrumentation().targetContext
                .getExternalFilesDir(null),
            "models",
        ).listFiles { f -> f.extension == "gguf" }.orEmpty().sortedBy { it.length() }
        assumeTrue("no models installed", models.isNotEmpty())

        val out = File(
            InstrumentationRegistry.getInstrumentation().targetContext
                .getExternalFilesDir(null),
            "markdown-replies",
        ).apply { mkdirs() }

        // The two smallest, which is what most people will actually be running on a phone.
        models.take(2).forEach { file ->
            val engine = LlamaCppEngine()
            try {
                engine.load(file, ModelLoadParams(contextLength = CONTEXT))
                PROMPTS.forEach { (name, prompt) ->
                    engine.resetContext()
                    val reply = engine.chat(
                        listOf(
                            ChatMessage.text(ChatRole.SYSTEM, SYSTEM),
                            ChatMessage.text(ChatRole.USER, prompt),
                        ),
                        ModelPreferences().toSamplerParams()
                            .copy(maxTokens = BUDGET, seed = 1, temperature = 0f),
                    ).toList().filterIsInstance<GenerationEvent.Completed>().single()
                    File(out, "${file.nameWithoutExtension}--$name.md")
                        .writeText(reply.content)
                }
            } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
                File(out, "${file.nameWithoutExtension}--FAILED.txt")
                    .writeText(failure.message ?: "unknown")
            } finally {
                engine.close()
            }
        }
    }

    private companion object {
        const val CONTEXT = 4096
        const val BUDGET = 700
        const val SYSTEM = "You are a helpful assistant. Use Markdown."

        /** Each one asks for a different feature, and asks for it hard. */
        val PROMPTS = listOf(
            "table" to
                "Compare three phone-sized language models in a Markdown table with the " +
                "columns Model, Parameters, Quantisation, Context and Notes. Table only.",
            "code" to
                "Write a Kotlin function that reverses a linked list. Put it in a fenced " +
                "code block with the language tag, and add one sentence before it that " +
                "uses `inline code` for the function name.",
            "structure" to
                "Explain what a KV cache is using: a level-2 heading, two bullet points, " +
                "a numbered list of three steps, one **bold** term and one *italic* term.",
            "mixed" to
                "Give me a short README for a tool called owctl: a heading, a one-line " +
                "description, a bulleted feature list, a fenced bash code block showing " +
                "two commands, and a two-column table of flags.",
        )
    }
}
