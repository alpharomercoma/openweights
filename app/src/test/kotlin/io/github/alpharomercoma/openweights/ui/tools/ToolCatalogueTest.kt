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
import io.github.alpharomercoma.openweights.core.tools.CanvasBoard
import io.github.alpharomercoma.openweights.core.tools.FetchUrlTool
import io.github.alpharomercoma.openweights.core.tools.ForgetMemoryTool
import io.github.alpharomercoma.openweights.core.tools.Memory
import io.github.alpharomercoma.openweights.core.tools.Reachability
import io.github.alpharomercoma.openweights.core.tools.ReadFileTool
import io.github.alpharomercoma.openweights.core.tools.ReadMemoryTool
import io.github.alpharomercoma.openweights.core.tools.RunScriptTool
import io.github.alpharomercoma.openweights.core.tools.SaveMemoryTool
import io.github.alpharomercoma.openweights.core.tools.SearchFilesTool
import io.github.alpharomercoma.openweights.core.tools.SearchSettings
import io.github.alpharomercoma.openweights.core.tools.SecretSealer
import io.github.alpharomercoma.openweights.core.tools.SessionArtifacts
import io.github.alpharomercoma.openweights.core.tools.Tool
import io.github.alpharomercoma.openweights.core.tools.ToolPrompting
import io.github.alpharomercoma.openweights.core.tools.UpdateMemoryTool
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
            // Online, because this test is about what the catalogue says rather than about
            // when it is offered.
            WebSearchTool(
                client,
                SearchSettings(context, SecretSealer.Unavailable),
                Reachability { true },
            ),
            FetchUrlTool(client, Reachability { true }, workspace, SessionArtifacts()),
            SearchFilesTool(workspace),
            ReadFileTool(workspace),
            WriteFileTool(workspace, SessionArtifacts(), CanvasBoard()),
            RunScriptTool(Sandbox(context), workspace),
            SaveMemoryTool(Memory(context)),
            ReadMemoryTool(Memory(context)),
            UpdateMemoryTool(Memory(context)),
            ForgetMemoryTool(Memory(context)),
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
        // "Every install" means on by default as well as able to run: the memory tools are
        // available the moment their switch is on, but no install ships with it on.
        val shipped = ToolPrompting
            .describe(tools.filter { it.isAvailable && it.defaultsOn }.map { it.definition })
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
         * Was 378 tokens for web_search, fetch_url and run_script, ceiling 448. Now about
         * 445, and the ceiling moved with it rather than the descriptions being shaved to
         * fit, because the growth is the point rather than an accident.
         *
         * What bought it: each description gained a clause saying what the tool is not for,
         * and each tool with a required argument gained one saying to ask when it is
         * missing. On the app's own eight tools that took the score from 36/48 to 40/48,
         * and no-call detection from 5/12 to 9/12. Seven settled questions, including who
         * wrote Pride and Prejudice, stopped reaching for the network. On a 2048 token
         * window the extra 67 tokens is about three per cent of it, which is the right
         * trade for a model that was otherwise searching the web to check things it had
         * already answered correctly.
         *
         * The property this ceiling exists for is unchanged: the margin absorbs a copy
         * edit, and a fourth default tool, which costs 40 to 90 tokens, still trips it.
         *
         * 512 became 576 when fetch_url gained save_to: a page larger than the context
         * window can now land in a file for the sandbox to work through, which is the
         * capability that makes fetch-then-parse a loop instead of a dead end. About 25
         * tokens after trimming, paid consciously.
         */
        const val DEFAULT_CEILING = 576

        /**
         * And what all of them cost, once a folder has been shared: was 672 tokens, now
         * about 781.
         *
         * A ceiling rather than a target, and a number worth being uncomfortable about. It is
         * the reason [io.github.alpharomercoma.openweights.core.tools.Tool.isAvailable] keeps
         * the file tools out of the prompt until there is a folder for them to work in, and
         * the reason the next tool is not free: choosing among tools gets measurably worse
         * as the list grows, so each addition costs twice, once in tokens and once in
         * accuracy.
         *
         * The rise was paid for and then argued down. Every description first gained a
         * clause saying what the tool is not for and, where an argument is required, one
         * saying to ask when it is missing. Then the three file tools were measured at 5/5
         * both before and after, so their added prose was bought back out and only the
         * ask clause kept, which is the part that made "read that file" ask which file
         * rather than invent a path. What is left is the growth that showed up in a score.
         *
         * Then two capabilities were added and it went to 832 exactly, which is no margin
         * at all: `run_script` gained `path`, so a program saved with `write_file` can be
         * run rather than only written, and `write_file` gained `replace`, so a script can
         * be fixed and saved again. Without both, authoring a program and running it was a
         * loop with no second turn. Every description was trimmed again first, 177
         * characters of wording that was not carrying a measurement, and the ceiling then
         * moved by 32 to restore the margin the comment above promises. The property it
         * exists for still holds: a ninth default tool costs 40 to 90 tokens and trips it.
         *
         * Moved again by 32 when the media tool was renamed and both search descriptions
         * were rewritten around what each returns. The day the media tool started
         * returning real results instead of silently failing, factual questions started
         * stopping there and being answered with pictures — a live misroute, not a
         * hypothetical. Measured at temperature zero on a fourteen-case held-out routing
         * suite: with "search" in the tool's name, five to eight of eight facts misrouted
         * whatever the descriptions said; named show_pictures, zero did. The tokens this
         * bought are the ones carrying that measurement.
         *
         * 896 became 1024 with the agentic development path: delete_file completed the
         * file quartet, and show_website and show_document put the work on screen, plus
         * one sentence teaching a project folder per task. About 130 tokens for the
         * feature the app is for — building things on the phone — and the choice-accuracy
         * cost is bounded by the same isAvailable gate: none of it is described until a
         * folder has been shared.
         *
         * 1024 became 1312 when the four memory tools joined this measurement — two of
         * them new, update and forget, which completed the writing family. Most of the
         * jump is the two that already shipped and had simply never been counted here.
         * The configuration this measures is now triple opt-in: a shared folder, the
         * memory writing switch, and the reading switch, all off until the user says
         * otherwise, so no install pays it by accident — and the shipped number above is
         * untouched.
         */
        const val FULL_CEILING = 1312
    }
}
