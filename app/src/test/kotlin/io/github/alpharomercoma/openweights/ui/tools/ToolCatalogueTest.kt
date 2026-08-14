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

package io.github.alpharomercoma.openweights.ui.tools

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.alpharomercoma.openweights.core.common.model.ToolDefinition
import io.github.alpharomercoma.openweights.core.sandbox.Sandbox
import io.github.alpharomercoma.openweights.core.tools.FetchUrlTool
import io.github.alpharomercoma.openweights.core.tools.ReadFileTool
import io.github.alpharomercoma.openweights.core.tools.RunScriptTool
import io.github.alpharomercoma.openweights.core.tools.SearchFilesTool
import io.github.alpharomercoma.openweights.core.tools.SearchSettings
import io.github.alpharomercoma.openweights.core.tools.Tool
import io.github.alpharomercoma.openweights.core.tools.ToolPrompting
import io.github.alpharomercoma.openweights.core.tools.WebSearchTool
import io.github.alpharomercoma.openweights.core.tools.Workspace
import io.github.alpharomercoma.openweights.core.tools.WorkspaceGrant
import io.github.alpharomercoma.openweights.core.tools.WriteFileTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What every tool has to be true of before a model is ever shown it.
 *
 * These are the faults that produce nothing anybody can debug. A schema that does not parse
 * is dropped by llama.cpp's template renderer without a word, so the tool is described to the
 * model and can never be called, and what the user sees is a model that ignores its tools. A
 * `required` entry naming a property that is not there is the same failure one level down. A
 * name that is a substring of another name breaks prose salvage, which matches whole names in
 * the reply and would then find two tools where the model named one.
 *
 * None of that shows up in a test of any single tool, because each of them is a property of
 * the set. So this is over the set the app really registers, built the way
 * [io.github.alpharomercoma.openweights.core.tools.di.ToolsModule] builds it.
 */
@RunWith(RobolectricTestRunner::class)
class ToolCatalogueTest {
    private val tools: List<Tool> = run {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val client = OkHttpClient()
        val workspace = Workspace(context, WorkspaceGrant(context))
        listOf(
            WebSearchTool(client, SearchSettings()),
            FetchUrlTool(client),
            SearchFilesTool(workspace),
            ReadFileTool(workspace),
            WriteFileTool(workspace),
            RunScriptTool(Sandbox(context), workspace),
        )
    }

    private val definitions: List<ToolDefinition> = tools.map { it.definition }

    @Test
    fun `every schema is json an object and has properties`() {
        definitions.forEach { tool ->
            val root = runCatching { Json.parseToJsonElement(tool.parametersJson).jsonObject }
                .getOrNull()
            assertWithMessage("${tool.name} schema is not a JSON object").that(root).isNotNull()
            assertWithMessage("${tool.name} is not type object")
                .that(root?.get("type")?.jsonPrimitive?.content).isEqualTo("object")
            assertWithMessage("${tool.name} describes no properties")
                .that(root?.get("properties")).isNotNull()
        }
    }

    @Test
    fun `everything required is something the schema describes`() {
        definitions.forEach { tool ->
            val root = tool.schema()
            val properties = root["properties"]?.jsonObject.orEmpty()
            val required = root["required"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }

            required.forEach { name ->
                assertWithMessage("${tool.name} requires $name, which it does not describe")
                    .that(properties.keys).contains(name)
            }
        }
    }

    @Test
    fun `every property says what it is and what it means`() {
        definitions.forEach { tool ->
            tool.schema()["properties"]?.jsonObject.orEmpty().forEach { (name, schema) ->
                val property = schema.jsonObject
                assertWithMessage("${tool.name}.$name has no type")
                    .that(property["type"]).isNotNull()
                // The description is the only thing telling a 1B model what to put there,
                // and an argument it cannot fill is a tool it cannot call.
                assertWithMessage("${tool.name}.$name has no description")
                    .that(property["description"]?.jsonPrimitive?.content).isNotEmpty()
            }
        }
    }

    @Test
    fun `no tool name is hidden inside another`() {
        // Prose salvage reads a tool's name out of an ordinary sentence by looking for it in
        // the text. With `search` and `web_search` both registered, a reply naming either
        // would match both, salvage would see two options where the model named one, and it
        // would give up: one tool's arrival would silently switch another one off.
        definitions.forEach { one ->
            definitions.filter { it.name != one.name }.forEach { other ->
                assertWithMessage("${one.name} contains ${other.name}")
                    .that(one.name.contains(other.name)).isFalse()
            }
        }
    }

    @Test
    fun `names are the shape a template can render`() {
        definitions.forEach { tool ->
            assertWithMessage("${tool.name} is not a plain lower case identifier")
                .that(tool.name.matches(Regex("[a-z][a-z0-9_]*"))).isTrue()
            assertWithMessage("${tool.name} has no description").that(tool.description).isNotEmpty()
        }
    }

    @Test
    fun `the catalogue still fits a small window`() {
        // Described in full, which is what a template that drops tool definitions is handed
        // in its system message on every pass of every turn. It is the share of the window
        // spent before the user has said anything, and the number that grows quietly as tools
        // are added, so it is asserted rather than trusted.
        val everything = ToolPrompting.describe(definitions).length / CHARS_PER_TOKEN
        val shipped = ToolPrompting
            .describe(tools.filter { it.isAvailable }.map { it.definition })
            .length / CHARS_PER_TOKEN

        assertThat(shipped).isGreaterThan(0)
        assertWithMessage("the tools every install has cost about $shipped tokens")
            .that(shipped).isLessThan(DEFAULT_CEILING)
        assertWithMessage("the whole catalogue costs about $everything tokens")
            .that(everything).isLessThan(FULL_CEILING)
    }

    private fun ToolDefinition.schema(): JsonObject =
        Json.parseToJsonElement(parametersJson).jsonObject

    private companion object {
        /** The same English approximation the tool budget uses. */
        const val CHARS_PER_TOKEN = 4

        /**
         * What the three tools every install has cost to describe, with room to edit.
         *
         * Measured at 378 tokens for web_search, fetch_url and run_script together. On the
         * 2048 token window these models are given that is already a sixth of it, spent on
         * every pass of every turn before the user has said a word. The margin is there so a
         * copy edit does not fail the build, and it is small enough that a fourth tool will.
         */
        const val DEFAULT_CEILING = 448

        /**
         * And what all six cost, once a folder has been shared: 672 tokens, a third of that
         * window.
         *
         * A ceiling rather than a target, and a number worth being uncomfortable about. It is
         * the reason [io.github.alpharomercoma.openweights.core.tools.Tool.isAvailable] keeps
         * the file tools out of the prompt until there is a folder for them to work in, and
         * the reason a seventh tool is not free: choosing among tools gets measurably worse
         * as the list grows, so each addition costs twice, once in tokens and once in
         * accuracy.
         */
        const val FULL_CEILING = 768
    }
}
