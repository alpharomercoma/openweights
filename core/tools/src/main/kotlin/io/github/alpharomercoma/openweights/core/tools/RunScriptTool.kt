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
        // Measured: the shipped wording ("sums, dates") described a fraction of what is
        // behind it, and the model used it accordingly. Widened to name the work it can
        // actually do, with a negative clause so it does not become a general fallback.
        // On a 48 case suite over these tools the model reached for it correctly 8/8 with
        // this wording, unchanged from the old one, while the old one never invited the
        // parsing, filtering and large-file work the sandbox exists for.
        //
        // And then it has to say what the sandbox *is*, which it did not. Asked to write a
        // script, save it and run it, the model wrote `require('fs')` and
        // `require('csv-parser')`, read the error, and wrote `require('fs')` again until the
        // round budget ran out. That is the reasonable guess: almost all JavaScript a model
        // has read runs in Node. QuickJS in an isolated process has no module loader, no
        // file system and no sockets, and nothing in the description said so. Naming the
        // absences is the same negative-scope lever the rest of these descriptions use.
        description = "Write a JavaScript program, run it in a sandbox, and use what it " +
            "returns. Use it for real computation: arithmetic, parsing, filtering, " +
            "regular expressions, JSON, dates, and going through a file too large to read " +
            "into the conversation. Give source, or path to run a .js file you saved. " +
            "Plain JavaScript only: no require, no file system, no network. To use a file, " +
            "name it in files and read it from inputs['path']. " +
            "The last expression is the answer.",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "source": {
                  "type": "string",
                  "description": "The JavaScript to run. The last expression is the answer."
                },
                "path": {
                  "type": "string",
                  "description": "Instead of source, a .js file to run"
                },
                "files": {
                  "type": "array",
                  "items": { "type": "string" },
                  "description": "Paths in the shared folder to read in as inputs"
                }
              }
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
        // Two ways in, because writing a program and running it are two turns and the
        // second one should not have to repeat the first. `write_file` saves the source,
        // and this runs what was saved, which is the loop a person means by "code a file
        // and execute it". Inline source stays the common path for one-off work.
        val inline = call.textArgument("source", "code", "script", "js")
        val from = call.textArgument("path", "file", "script_path")
        val source = when {
            inline != null -> inline
            from != null ->
                readProgram(from) ?: return "There is no file at $from to run. " +
                    "Save it with write_file first, or pass the program as source."
            else ->
                return "No script was given. Call run_script again with source, " +
                    "or with path pointing at a file you saved."
        }

        // What the script asked for, and what it forgot to ask for. Both, because the model
        // writes `readFileSync('data/sales.csv')` far more readily than it fills in `files`,
        // and a program that names a file it can see is not asking for anything it could not
        // have had by naming it in the argument.
        val wanted = (call.paths() + mentionedPaths(source)).distinct().take(MAX_FILES)
        if (wanted.isNotEmpty() && !workspace.isReady) {
            return "No folder has been shared, so there are no files to read. " +
                "Run the script without files, or ask the user to choose a folder under Tools."
        }

        val inputs = gather(wanted)
        val result = sandbox.run(source = NODE_SHIM + source, inputsJson = inputs)
        return if (result.failed) {
            "The script did not finish: ${result.output}" +
                hostApiHint(source, result.output) +
                repairRoute(from)
        } else {
            result.output.ifBlank { "The script ran and produced nothing." }
        }
    }

    /**
     * How to fix a saved program, for a model that has just been told what was wrong with it.
     *
     * Only for the `path` case, and it earns its sentence. Told that the sandbox has no
     * `require`, the model stopped writing Node and then ran the same unchanged file a second
     * time, because nothing connected "this file is wrong" with "the way to change a file is
     * write_file". It answered from an earlier read instead, correctly and by luck. Naming the
     * two steps is the same repair-route idea the turn loop already uses for a call it could
     * not parse.
     */
    private fun repairRoute(from: String?): String {
        if (from == null) return ""
        return " The program came from $from. Save a corrected one with write_file and " +
            "replace, then run it again."
    }

    /**
     * One sentence about the sandbox, when the failure was reaching for something outside it.
     *
     * Nearly all the JavaScript a model has read runs in Node, so asked to save a program and
     * run it, it writes `require('fs')` and `fs.readFileSync('data/sales.csv')`. Measured on a
     * closed loop task, it then read the error, dropped one of the two imports, wrote
     * `require('fs')` again, and ran out of rounds. Saying so in the tool description was
     * tried first and did not move it: the model is not reading the description at the moment
     * it writes the file, it is writing JavaScript, and JavaScript means Node.
     *
     * So it is said here, where the mistake actually happens and where the round budget has
     * already made room for a second attempt. Named after what was reached for, because "no
     * file system" is advice and "there is no require here, pass the file in files" is an
     * instruction the next attempt can follow.
     */
    private fun hostApiHint(source: String, failure: String): String {
        val reached = HOST_APIS.firstOrNull { (name, _) ->
            failure.contains(name) || source.contains(name)
        } ?: return ""
        return " " + reached.second
    }

    /**
     * The text of a program saved in the shared folder, or null when there is none.
     *
     * Bounded well under [MAX_INPUT_CHARS]: this one does become the program, so a file
     * that is really a dataset should arrive through `files` and be read as data rather
     * than handed to the interpreter as source.
     */
    private suspend fun readProgram(path: String): String? {
        if (!workspace.isReady) return null
        val entry = workspace.resolve(path)?.takeUnless { it.isDirectory } ?: return null
        return workspace.readText(entry, 0, MAX_SOURCE_CHARS)?.takeIf { it.isNotBlank() }
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

    /**
     * Paths a program mentions in a string, whether or not it declared them.
     *
     * Deliberately narrow: something with a slash and a file extension, which is what a path
     * looks like and what a sentence does not. Bounded by the same [MAX_FILES] and read
     * through the same [Workspace.resolve] as a declared one, so this widens nothing a
     * script could not already reach by naming the file properly.
     */
    private fun mentionedPaths(source: String): List<String> =
        PATH_LIKE.findAll(source).map { it.groupValues[1] }.distinct().take(MAX_FILES).toList()

    /** The file names asked for, which arrive as an array rather than as a single value. */
    private fun ToolCall.paths(): List<String> = runCatching {
        Json.parseToJsonElement(argumentsJson).jsonObject["files"]
            ?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.content.takeIf(String::isNotBlank) }
            .orEmpty()
    }.getOrDefault(emptyList())

    private companion object {
        /**
         * A quoted string that is shaped like a path in the shared folder.
         *
         * A slash and an extension. `"notes/todo.md"` matches, `"a sentence, with commas"`
         * does not, and neither does `"https://example.com/page.html"`, which is excluded
         * because a program mentioning a URL is not asking to read a local file.
         */
        val PATH_LIKE = Regex("""["']((?!\w+://)[\w.\-]+(?:/[\w.\-]+)+\.[A-Za-z0-9]{1,6})["']""")

        /**
         * The two Node modules a model actually reaches for, made to work.
         *
         * Three prompt-side interventions were measured against this and none of them
         * stopped it: saying "no require, no file system" in the tool description, returning
         * a named hint when the script failed on one, and naming the two steps to repair a
         * saved file. Across two closed-loop tasks the model wrote `require('fs')` every
         * time, read the error, and wrote `require('fs')` again. It has read far more Node
         * than it has read anything else, and that is not a prior a sentence dislodges.
         *
         * So the sandbox answers to the name instead. `fs.readFileSync` returns the file the
         * tool already gathered, which is the operation the model was reaching for, and
         * anything else still fails with a sentence saying what is not here. This is a
         * shim over the inputs the caller was given, not a file system: nothing it returns
         * was not already being handed to the script.
         *
         * Prepended rather than built into the sandbox, so `core:sandbox` keeps having no
         * policy in it and the isolated process keeps having no host bindings at all.
         */
        val NODE_SHIM = """
            const __read = (p) => {
              const k = String(p).replace(/^\.?\//, "");
              const v = inputs[p] ?? inputs[k];
              if (v === undefined) {
                throw new Error(p + " was not passed in files, so it is not readable here");
              }
              return v;
            };
            const require = (m) => {
              const name = String(m).replace(/^node:/, "");
              if (name === "fs") {
                return {
                  readFileSync: __read,
                  existsSync: (p) => { try { __read(p); return true; } catch (e) { return false; } },
                  promises: { readFile: async (p) => __read(p) },
                };
              }
              if (name === "path") {
                return {
                  join: (...a) => a.filter(Boolean).join("/").replace(/\/+/g, "/"),
                  basename: (p) => String(p).split("/").pop(),
                  extname: (p) => { const b = String(p).split("/").pop(); const i = b.lastIndexOf("."); return i < 0 ? "" : b.slice(i); },
                };
              }
              throw new Error("there is no module '" + m + "' in this sandbox: it is plain JavaScript, with files in inputs['path']");
            };

        """.trimIndent() + "\n"

        /**
         * What a model reaches for that is not here, and what to do instead.
         *
         * Ordered, because a script that does both should be told about the import first:
         * fixing the import is what makes the rest of the error legible.
         */
        val HOST_APIS = listOf(
            "import " to "There is no module loader in this sandbox. Write plain JavaScript " +
                "in one piece, and name any file you need in files.",
            "fetch(" to "There is no network in this sandbox. If you need a page, use " +
                "fetch_url and pass what it returns in as text.",
            "process." to "There is no process object in this sandbox. It is plain " +
                "JavaScript with inputs['path'] for files and nothing else.",
        )

        /** More than a model keeps track of, and enough for the joins anybody actually does. */
        const val MAX_FILES = 3

        /**
         * A program, not a dataset.
         *
         * Smaller than [MAX_INPUT_CHARS] on purpose: anything longer than this is not
         * source a model wrote a moment ago, and handing it to the interpreter as code
         * would be a mistake worth failing rather than attempting.
         */
        const val MAX_SOURCE_CHARS = 16 * 1024

        /**
         * Sixty four thousand characters of a file, which is far past what the model could
         * read for itself. It never reaches the prompt, so the limit here is the binder's
         * appetite rather than the context window's.
         */
        const val MAX_INPUT_CHARS = 64 * 1024
    }
}
