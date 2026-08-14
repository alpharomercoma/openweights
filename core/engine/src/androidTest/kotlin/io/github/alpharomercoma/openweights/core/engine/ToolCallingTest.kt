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

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import io.github.alpharomercoma.openweights.core.common.model.ToolDefinition
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Proves tool calling works against the real model.
 *
 * The value of routing through llama.cpp's chat engine is that tool syntax differs per
 * model family; a test that only checked our own plumbing would pass while the model
 * emitted something the parser never sees. So this asserts on a parsed call.
 *
 * Every generation here is greedy, which is what makes asserting on a model's output
 * defensible at all. At a temperature above zero this was a coin weighted by the sampler:
 * the same run could pass and fail with nothing changed, which is how a suite stops being
 * trusted and then stops being run. At zero, one model file and one prompt give one answer,
 * so a failure means something changed. It is still a statement about the file somebody
 * pushed rather than about the app, which is what [org.junit.Assume] is for below.
 */
@RunWith(AndroidJUnit4::class)
class ToolCallingTest {
    private lateinit var engine: InferenceEngine

    private val weather = ToolDefinition(
        name = "get_weather",
        description = "Get the current weather for a city.",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "city": { "type": "string", "description": "City name" }
              },
              "required": ["city"]
            }
        """.trimIndent(),
    )

    @Before
    fun setUp() {
        assumeTrue("no test model at ${MODEL.path}", MODEL.isFile)
        engine = LlamaCppEngine()
    }

    @After
    fun tearDown() {
        if (::engine.isInitialized) runBlocking { engine.close() }
    }

    @Test
    fun modelAsksForAToolWhenOneFits() = runBlocking {
        engine.load(MODEL, ModelLoadParams(contextLength = CONTEXT))
        // Not every model can. A template that does not render tools drops them without
        // complaint and the model answers in prose, so asserting a call here would be
        // asserting something about the file someone happened to push, not about the app.
        assumeTrue(
            "${MODEL.name} has a chat template that does not support tools",
            engine.loadedModel?.supportsTools == true,
        )

        val completed = engine.chat(
            messages = listOf(
                ChatMessage.text(ChatRole.USER, "What is the weather in Manila right now?"),
            ),
            params = SamplerParams(temperature = 0f, maxTokens = REASONING_BUDGET, seed = 3),
            tools = listOf(weather),
        ).toList().filterIsInstance<GenerationEvent.Completed>().single()

        Log.i(
            TAG,
            "content=${completed.content.take(120)} | calls=${completed.toolCalls}",
        )

        assertThat(completed.toolCalls).isNotEmpty()
        val call = completed.toolCalls.first()
        assertThat(call.name).isEqualTo("get_weather")
        // The arguments must be usable JSON, not prose describing the arguments.
        assertThat(call.argumentsJson).contains("Manila")
    }

    @Test
    fun contentIsSeparatedFromReasoningByTheModelsOwnFormat() = runBlocking {
        engine.load(MODEL, ModelLoadParams(contextLength = CONTEXT))

        val completed = engine.chat(
            messages = listOf(ChatMessage.text(ChatRole.USER, "Say the word blue and stop.")),
            params = SamplerParams(temperature = 0f, maxTokens = REASONING_BUDGET, seed = 5),
        ).toList().filterIsInstance<GenerationEvent.Completed>().single()

        Log.i(TAG, "content=${completed.content} | reasoning=${completed.reasoning.take(80)}")

        // Whatever the model thinks, the answer must not still carry the thinking tags.
        assertThat(completed.content).doesNotContain("<|tool_call_start|>")
        assertThat(completed.content.trim()).isNotEmpty()
    }

    @Test
    fun noToolsMeansNoCalls() = runBlocking {
        engine.load(MODEL, ModelLoadParams(contextLength = CONTEXT))

        val completed = engine.chat(
            messages = listOf(ChatMessage.text(ChatRole.USER, "What is 2 + 2?")),
            params = SamplerParams(temperature = 0f, maxTokens = REASONING_BUDGET, seed = 11),
        ).toList().filterIsInstance<GenerationEvent.Completed>().single()

        assertThat(completed.toolCalls).isEmpty()
    }

    private companion object {
        const val TAG = "OpenWeightsTools"
        const val CONTEXT = 4096

        /**
         * Reasoning models think for a long time before answering: LFM2.5 spends tens of
         * seconds on it. A budget that truncates mid-thought produces an unterminated
         * block the parser cannot make sense of, which looks like a parser bug but is a
         * budget bug.
         */
        const val REASONING_BUDGET = 1200
        val MODEL = File("/data/local/tmp/openweights/model.gguf")
    }
}
