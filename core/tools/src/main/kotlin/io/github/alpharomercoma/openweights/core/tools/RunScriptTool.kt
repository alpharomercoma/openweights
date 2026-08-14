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

package io.github.alpharomercoma.openweights.core.tools

import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import io.github.alpharomercoma.openweights.core.common.model.ToolDefinition
import io.github.alpharomercoma.openweights.core.sandbox.Sandbox
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lets the model write a program and run it somewhere it can do no harm.
 *
 * The reason this earns its place on a phone is not that a model likes writing code. It is
 * that a script sees the files and the conversation does not. A file handed to the sandbox
 * never enters the prompt, so a script can go through something far larger than the context
 * window and hand back only the answer, which is the one way this app gets to work with a
 * document it could not otherwise hold.
 *
 * The failure path is the common path, not the edge case. A model this size gets roughly a
 * third of the programs it writes right, so the error has to come back as something to act
 * on, and the round budget already allows for a second attempt.
 */
@Singleton
class RunScriptTool @Inject constructor(
    private val sandbox: Sandbox,
    private val workspace: Workspace,
) : Tool {
    override val definition = ToolDefinition(
        name = "run_script",
        description = "Write JavaScript and run it to work something out. Use it for sums, " +
            "dates and going through data rather than doing it in your head. Return the " +
            "answer, or leave it as the last expression. Name files to read as inputs['x'].",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "source": {
                  "type": "string",
                  "description": "The JavaScript to run. The last expression is the answer."
                },
                "files": {
                  "type": "array",
                  "items": { "type": "string" },
                  "description": "Paths in the shared folder to read in as inputs"
                }
              },
              "required": ["source"]
            }
        """.trimIndent(),
    )

    override val chains: Boolean = true

    /**
     * What comes back was shaped by a script, and possibly by a file the script read.
     *
     * Marked true whether or not a file was named this time. The model wrote the script, and
     * what it wrote may have been suggested by something it read earlier in the turn, so the
     * output is downstream of untrusted text more often than the arguments admit. It costs a
     * tap only if the model then tries to send something off the device.
     */
    override val returnsUntrustedText: Boolean = true

    override suspend fun run(call: ToolCall): String {
        val source = call.textArgument("source", "code", "script", "js")
            ?: return "No script was given. Call run_script again with source."

        val wanted = call.paths()
        if (wanted.isNotEmpty() && !workspace.isReady) {
            return "No folder has been shared, so there are no files to read. " +
                "Run the script without files, or ask the user to choose a folder under Tools."
        }

        val inputs = gather(wanted)
        val result = sandbox.run(source = source, inputsJson = inputs)
        return if (result.failed) {
            "The script did not finish: ${result.output}"
        } else {
            result.output.ifBlank { "The script ran and produced nothing." }
        }
    }

    /**
     * Reads the named files into one JSON object for the sandbox.
     *
     * Generous bounds compared with `read_file`, and deliberately so: these never reach the
     * prompt. The only thing that has to fit a context window is whatever the script decides
     * to return, which is the whole point of running one.
     */
    private suspend fun gather(paths: List<String>): String {
        val named = buildJsonObject {
            paths.take(MAX_FILES).forEach { path ->
                val entry = workspace.resolve(path)
                val text = entry?.takeUnless { it.isDirectory }
                    ?.let { workspace.readText(it, 0, MAX_INPUT_CHARS) }
                put(path, JsonPrimitive(text ?: ""))
            }
        }
        return named.toString()
    }

    /** The file names asked for, which arrive as an array rather than as a single value. */
    private fun ToolCall.paths(): List<String> = runCatching {
        Json.parseToJsonElement(argumentsJson).jsonObject["files"]
            ?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.content.takeIf(String::isNotBlank) }
            .orEmpty()
    }.getOrDefault(emptyList())

    private companion object {
        /** More than a model keeps track of, and enough for the joins anybody actually does. */
        const val MAX_FILES = 3

        /**
         * Sixty four thousand characters of a file, which is far past what the model could
         * read for itself. It never reaches the prompt, so the limit here is the binder's
         * appetite rather than the context window's.
         */
        const val MAX_INPUT_CHARS = 64 * 1024
    }
}
