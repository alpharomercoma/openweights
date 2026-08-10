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
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import io.github.alpharomercoma.openweights.core.common.model.ToolDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * What the agent does with what a model asks for.
 *
 * The loop belongs to the caller, so what is tested here is one round: which calls run,
 * which do not, and what the model is told either way. A model that asks for something and
 * is told nothing is the failure this whole layer exists to prevent.
 */
class AgentRunnerTest {
    private val ran = mutableListOf<String>()

    private val echo = object : Tool {
        override val definition = ToolDefinition("echo", "Echoes", "{}")
        override suspend fun run(call: ToolCall): String {
            ran += call.name
            return "echoed ${call.argumentsJson}"
        }
    }

    private val explodes = object : Tool {
        override val definition = ToolDefinition("explodes", "Throws", "{}")
        override suspend fun run(call: ToolCall): String = error("the network fell over")
    }

    private val open = object : Tool {
        override val definition = ToolDefinition("open", "Needs no approval", "{}")
        override val needsApproval = false
        override suspend fun run(call: ToolCall): String {
            ran += call.name
            return "ok"
        }
    }

    private val registry = ToolRegistry(listOf(echo, explodes, open))
    private val runner = AgentRunner(registry)

    private fun call(name: String, id: String = "c1", args: String = "{}") =
        ToolCall(id = id, name = name, argumentsJson = args)

    @Test
    fun `no calls means the turn is over`() = runTest {
        val decision = runner.step(emptyList(), round = 0, mode = AgentMode.AUTO, approve = {
            true
        })

        assertThat(decision).isEqualTo(AgentDecision.Finished)
    }

    @Test
    fun `auto mode runs without asking and feeds the result back`() = runTest {
        var asked = false

        val decision = runner.step(
            calls = listOf(call("echo", args = """{"q":"hi"}""")),
            round = 0,
            mode = AgentMode.AUTO,
            approve = {
                asked = true
                true
            },
        )

        assertThat(asked).isFalse()
        assertThat(ran).containsExactly("echo")
        val result = (decision as AgentDecision.Continue).messages.single()
        assertThat(result.role).isEqualTo(ChatRole.TOOL)
        assertThat(result.toolCallId).isEqualTo("c1")
        assertThat(result.text).contains("hi")
    }

    @Test
    fun `plan mode runs nothing and says so`() = runTest {
        val decision = runner.step(
            calls = listOf(call("echo")),
            round = 0,
            mode = AgentMode.PLAN,
            approve = { true },
        )

        assertThat(ran).isEmpty()
        val continued = decision as AgentDecision.Continue
        // No tool messages: nothing ran, so there is nothing to tell the model. The steps
        // are what the transcript shows the user.
        assertThat(continued.messages).isEmpty()
        assertThat(continued.steps.single()).isInstanceOf(AgentStep.Skipped::class.java)
    }

    @Test
    fun `a declined call still tells the model, so it can answer without the tool`() = runTest {
        val decision = runner.step(
            calls = listOf(call("echo")),
            round = 0,
            mode = AgentMode.ASK,
            approve = { false },
        )

        assertThat(ran).isEmpty()
        val message = (decision as AgentDecision.Continue).messages.single()
        assertThat(message.role).isEqualTo(ChatRole.TOOL)
        assertThat(message.text).contains("declined")
    }

    @Test
    fun `a tool that needs no approval is never asked about`() = runTest {
        var asked = false

        runner.step(
            calls = listOf(call("open")),
            round = 0,
            mode = AgentMode.ASK,
            approve = {
                asked = true
                false
            },
        )

        assertThat(asked).isFalse()
        assertThat(ran).containsExactly("open")
    }

    @Test
    fun `a tool that throws reports the failure instead of ending the turn`() = runTest {
        val decision = runner.step(
            calls = listOf(call("explodes")),
            round = 0,
            mode = AgentMode.AUTO,
            approve = { true },
        )

        val message = (decision as AgentDecision.Continue).messages.single()
        assertThat(message.text).contains("explodes failed")
        assertThat(message.text).contains("the network fell over")
    }

    @Test
    fun `a tool that does not exist names the ones that do`() = runTest {
        val decision = runner.step(
            calls = listOf(call("teleport")),
            round = 0,
            mode = AgentMode.AUTO,
            approve = { true },
        )

        val message = (decision as AgentDecision.Continue).messages.single()
        assertThat(message.text).contains("no tool called teleport")
        assertThat(message.text).contains("echo")
    }

    @Test
    fun `several calls in one round all run and all answer`() = runTest {
        val decision = runner.step(
            calls = listOf(call("echo", id = "a"), call("open", id = "b")),
            round = 0,
            mode = AgentMode.AUTO,
            approve = { true },
        )

        val messages = (decision as AgentDecision.Continue).messages
        assertThat(messages.map { it.toolCallId }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `the round budget stops a model that will not stop asking`() = runTest {
        val decision = runner.step(
            calls = listOf(call("echo")),
            round = AgentRunner.DEFAULT_MAX_ROUNDS,
            mode = AgentMode.AUTO,
            approve = { true },
        )

        assertThat(ran).isEmpty()
        assertThat(decision).isInstanceOf(AgentDecision.Exhausted::class.java)
    }

    @Test
    fun `auto still asks about a tool whose reach the model chooses`() = runTest {
        var asked = false
        val open = object : Tool {
            override val definition = ToolDefinition("fetch", "Fetches anything", "{}")
            override val alwaysAsk = true
            override suspend fun run(call: ToolCall): String {
                ran += call.name
                return "fetched"
            }
        }
        val runner = AgentRunner(ToolRegistry(listOf(open)))

        runner.step(
            calls = listOf(call("fetch")),
            round = 0,
            mode = AgentMode.AUTO,
            approve = {
                asked = true
                false
            },
        )

        // Auto removes pointless taps. It does not remove the only check on a primitive
        // pointed wherever the model decided.
        assertThat(asked).isTrue()
        assertThat(ran).isEmpty()
    }

    @Test
    fun `stopping during a tool stops the turn instead of becoming a tool failure`() = runTest {
        val slow = object : Tool {
            override val definition = ToolDefinition("slow", "Never returns", "{}")
            override suspend fun run(call: ToolCall): String =
                throw CancellationException("stopped")
        }
        val runner = AgentRunner(ToolRegistry(listOf(slow)))

        // runCatching used to swallow this, so Stop during a slow request became a failed
        // tool result and the agent carried on from it.
        var cancelled = false
        try {
            runner.step(
                calls = listOf(call("slow")),
                round = 0,
                mode = AgentMode.AUTO,
                approve = { true },
            )
        } catch (expected: CancellationException) {
            cancelled = true
        }
        assertThat(cancelled).isTrue()
    }

    @Test
    fun `a mode is chosen by the word the user typed`() {
        assertThat(AgentMode.of("plan")).isEqualTo(AgentMode.PLAN)
        assertThat(AgentMode.of("AUTO")).isEqualTo(AgentMode.AUTO)
        assertThat(AgentMode.of("nonsense")).isNull()
    }
}
