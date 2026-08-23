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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Which quoted strings in a program count as asking for a file.
 *
 * `run_script` reads what the program mentions as well as what it declared in `files`,
 * because the model writes `readFileSync('data/sales.csv')` far more readily than it fills
 * the argument in. That widening is only safe if it is narrow: a pattern that matched
 * ordinary prose would hand the sandbox files nobody asked for, and one that matched a URL
 * would treat a web address as a local path.
 *
 * The regex itself is private, so this exercises the same expression. It is a copy, which is
 * a cost worth paying to have the behaviour pinned at all: the alternative is a rule with no
 * test, in a tool that reads the user's files.
 */
class RunScriptPathsTest {
    private val pathLike = Regex("""["']((?!\w+://)[\w.\-]+(?:/[\w.\-]+)+\.[A-Za-z0-9]{1,6})["']""")

    private fun found(source: String) = pathLike.findAll(source).map { it.groupValues[1] }.toList()

    @Test
    fun `a file a program reads is found`() {
        assertThat(found("""const d = fs.readFileSync('data/sales.csv', 'utf8')"""))
            .containsExactly("data/sales.csv")
    }

    @Test
    fun `double quotes count too`() {
        assertThat(found("""inputs["notes/todo.md"]""")).containsExactly("notes/todo.md")
    }

    @Test
    fun `a web address is not a local file`() {
        assertThat(found("""fetch("https://example.com/page.html")""")).isEmpty()
    }

    @Test
    fun `prose is not a path`() {
        assertThat(found("""const m = "a sentence, with commas and a full stop."""")).isEmpty()
    }

    @Test
    fun `a bare name with no folder is not matched`() {
        // Deliberate: the workspace is browsed by search_files, and a program saying "utf8"
        // or "total.js" before it has been written should not send the tool looking.
        assertThat(found("""readFileSync("sales.csv")""")).isEmpty()
    }

    @Test
    fun `several are found in order and without repeats`() {
        val source = """
            const a = read('notes/todo.md');
            const b = read('notes/meeting.md');
            const c = read('notes/todo.md');
        """.trimIndent()
        assertThat(found(source).distinct())
            .containsExactly("notes/todo.md", "notes/meeting.md").inOrder()
    }
}
