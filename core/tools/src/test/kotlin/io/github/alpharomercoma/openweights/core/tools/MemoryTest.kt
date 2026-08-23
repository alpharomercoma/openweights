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

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What crosses from one conversation into the next, and what stops it growing.
 *
 * Everything kept here is prefill on every turn of every future conversation, so the bounds
 * are the feature. A memory nobody capped is a context window that shrinks for reasons the
 * user cannot see.
 */
@RunWith(RobolectricTestRunner::class)
class MemoryTest {
    private val memory =
        Memory(ApplicationProvider.getApplicationContext<android.app.Application>())

    private fun call(fact: String) =
        ToolCall(id = "1", name = "remember", argumentsJson = """{"fact":"$fact"}""")

    @Test
    fun `a fact is kept and comes back in the prompt`() {
        assertThat(memory.remember("Prefers answers without preamble")).isEqualTo("Remembered.")

        val prompt = memory.asPrompt()
        assertThat(prompt).contains("Prefers answers without preamble")
        // Framed as background rather than as orders, which is what stops a small model
        // reading a bare list at the top of a prompt as instructions.
        assertThat(prompt).contains("earlier conversations")
        assertThat(prompt).contains("ignore them otherwise")
    }

    @Test
    fun `nothing to say means nothing in the prompt`() {
        assertThat(memory.asPrompt()).isNull()
    }

    @Test
    fun `the same fact twice is kept once`() {
        memory.remember("Lives in Manila")
        assertThat(memory.remember("lives in MANILA")).isEqualTo("Already remembered.")
        assertThat(memory.facts.value).hasSize(1)
    }

    @Test
    fun `an empty or oversized note is refused with a reason`() {
        assertThat(memory.remember("   ")).contains("empty")
        assertThat(memory.remember("x".repeat(Memory.MAX_CHARS + 1))).contains("Too long")
        assertThat(memory.facts.value).isEmpty()
    }

    @Test
    fun `the cap drops the oldest rather than refusing the newest`() {
        repeat(Memory.MAX_FACTS + 5) { memory.remember("fact number $it", now = it.toLong()) }

        val kept = memory.facts.value
        assertThat(kept).hasSize(Memory.MAX_FACTS)
        assertThat(kept.first().text).isEqualTo("fact number 5")
        assertThat(kept.last().text).isEqualTo("fact number ${Memory.MAX_FACTS + 4}")
    }

    @Test
    fun `forgetting one leaves the rest, and forgetting all leaves nothing`() {
        memory.remember("First")
        memory.remember("Second")

        memory.forget("first")
        assertThat(memory.facts.value.map { it.text }).containsExactly("Second")

        memory.forgetAll()
        assertThat(memory.facts.value).isEmpty()
        assertThat(memory.asPrompt()).isNull()
    }

    @Test
    fun `the tool is off for anybody who has never opened the screen`() {
        val tool = RememberTool(memory)
        assertThat(tool.defaultsOn).isFalse()

        val switches =
            ToolSwitches(ApplicationProvider.getApplicationContext<android.app.Application>())
        assertThat(switches.isEnabled(tool)).isFalse()
        // And every other tool is unaffected by the default existing at all.
        assertThat(switches.isEnabled(AdvanceTool(PlanBoard()))).isTrue()
    }

    @Test
    fun `the tool saves what it is given and says so`() {
        runBlocking {
            val tool = RememberTool(memory)

            assertThat(tool.run(call("Writes in British English"))).isEqualTo("Remembered.")
            assertThat(memory.facts.value.map { it.text })
                .containsExactly("Writes in British English")
        }
    }

    @Test
    fun `the tool asks again rather than saving nothing`() {
        runBlocking {
            val tool = RememberTool(memory)
            val answer = tool.run(ToolCall(id = "1", name = "remember", argumentsJson = "{}"))

            assertThat(answer).contains("No fact was given")
            assertThat(memory.facts.value).isEmpty()
        }
    }
}
