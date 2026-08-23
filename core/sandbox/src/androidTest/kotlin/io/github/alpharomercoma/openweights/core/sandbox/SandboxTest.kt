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

package io.github.alpharomercoma.openweights.core.sandbox

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The interpreter, in the process it actually runs in.
 *
 * Has to be a device test rather than a host one, and not only because of the native
 * library: what is being checked is that a service declared `isolatedProcess` can start,
 * load a JNI library out of the apk, and answer over a binder, none of which a JVM on a
 * laptop can tell you. An isolated process is refused a great deal, and whether loading our
 * own shared object is among the things it is refused is exactly the sort of question that
 * should be answered by a device rather than by reading documentation.
 *
 * The last few are the security claims. They are worth as much as their tests and no more.
 */
@RunWith(AndroidJUnit4::class)
class SandboxTest {
    private val sandbox = Sandbox(ApplicationProvider.getApplicationContext())

    @Test
    fun runsAScriptInAnIsolatedProcessAndReturnsTheValue() = runBlocking {
        val result = sandbox.run("2 + 3")

        assertThat(result.failed).isFalse()
        assertThat(result.output).isEqualTo("5")
    }

    @Test
    fun anObjectComesBackAsItsContentsRatherThanAsObjectObject() = runBlocking {
        val result = sandbox.run("({ total: 4, items: ['a', 'b'] })")

        assertThat(result.failed).isFalse()
        assertThat(result.output).contains("\"total\":4")
    }

    @Test
    fun consoleLogIsThereBecauseAModelWillWriteItRegardless() = runBlocking {
        val result = sandbox.run("console.log('counted', 41 + 1)")

        assertThat(result.failed).isFalse()
        assertThat(result.output).contains("counted 42")
    }

    @Test
    fun aScriptThatReturnsIsReadAsAFunctionBody() = runBlocking {
        // Measured on device: given the same sum three times, the model wrote `return` once
        // and a bare expression twice. As written the first is a syntax error and nothing
        // runs at all, so a third of the attempts were being lost to the shape of the answer
        // rather than to anything wrong with it.
        val result = sandbox.run("let total = 48273 * 1179; return total;")

        assertThat(result.failed).isFalse()
        assertThat(result.output).isEqualTo("56913867")
    }

    @Test
    fun aGenuinelyBrokenScriptStillHearsAboutItsOwnMistake() = runBlocking {
        // The other half of that rule. Reading it a second way must not mean reporting the
        // second reading's complaint, which is about code the model never wrote.
        val result = sandbox.run("function ( {")

        assertThat(result.failed).isTrue()
        assertThat(result.output).contains("SyntaxError")
    }

    @Test
    fun aRuntimeFailureIsNotReportedAsASyntaxError() = runBlocking {
        // The bug this replaces, and the one users actually saw. A program with a top-level
        // `return` fails to parse, gets read again as a function body, runs, and throws for
        // a real reason. The old path kept the *first* complaint and threw the real one
        // away, so a TypeError arrived labelled SyntaxError and the model spent its next
        // turn hunting for a grammar mistake that was never there.
        val result = sandbox.run("const d = {};\nreturn d.user.id;")

        assertThat(result.failed).isTrue()
        assertThat(result.output).contains("TypeError")
        assertThat(result.output).doesNotContain("SyntaxError")
    }

    @Test
    fun topLevelAwaitIsAllowedBecauseTheModelWritesIt() = runBlocking {
        // Almost all the JavaScript a model has read lives in a module, where this is legal.
        // In a classic script it is a syntax error, which is a fact about the evaluation
        // mode rather than about the program.
        val result = sandbox.run("const v = await Promise.resolve(7);\nv * 6")

        assertThat(result.failed).isFalse()
        assertThat(result.output).contains("42")
    }

    @Test
    fun awaitBesideReturnIsAlsoRead() = runBlocking {
        // Both rewrites at once, which is the combination the four rung ladder exists for.
        val result = sandbox.run("const v = await Promise.resolve(6);\nreturn v * 7;")

        assertThat(result.failed).isFalse()
        assertThat(result.output).contains("42")
    }

    @Test
    fun anAwaitedValueComesBackRatherThanAPromise() = runBlocking {
        // Allowing await is only half of it. The evaluation then hands back a promise, and
        // reporting "[object Promise]" would be barely better than the syntax error.
        val result = sandbox.run("await Promise.all([1, 2, 3].map(async n => n * 2))")

        assertThat(result.failed).isFalse()
        assertThat(result.output).doesNotContain("Promise")
        assertThat(result.output).contains("6")
    }

    @Test
    fun aLoopWithNoEndIsStoppedRatherThanWaitedFor() = runBlocking {
        // The case a memory limit cannot catch, because it allocates nothing. Without the
        // interrupt handler this hangs the turn rather than failing it.
        val result = sandbox.run("while (true) {}", millis = 400)

        assertThat(result.failed).isTrue()
    }

    @Test
    fun aSyntaxErrorComesBackAsSomethingToActOn() = runBlocking {
        // At roughly a third of generated programs being right, this is the common path
        // rather than the edge case, so what it says matters as much as that it fails.
        val result = sandbox.run("function ( {")

        assertThat(result.failed).isTrue()
        assertThat(result.output).isNotEmpty()
    }

    @Test
    fun theFilesTheAppChoseToHandOverAreVisible() = runBlocking {
        val inputs = """{"notes.md":"the budget is 4200"}"""

        val result = sandbox.run("inputs['notes.md'].split(' ').pop()", inputsJson = inputs)

        assertThat(result.failed).isFalse()
        assertThat(result.output).contains("4200")
    }

    @Test
    fun thereIsNoWayToReachAFile() = runBlocking {
        // qjs-libc is not linked, so the functions a script would use do not exist in the
        // binary at all. This is the test that says so out loud.
        val probe = """
            [typeof require, typeof std, typeof os, typeof open, typeof process]
                .join(',')
        """.trimIndent()

        val result = sandbox.run(probe)

        assertThat(result.output)
            .isEqualTo("\"undefined,undefined,undefined,undefined,undefined\"")
    }

    @Test
    fun thereIsNoWayToReachTheNetwork() = runBlocking {
        val probe = "[typeof fetch, typeof XMLHttpRequest, typeof WebSocket].join(',')"

        val result = sandbox.run(probe)

        assertThat(result.output).isEqualTo("\"undefined,undefined,undefined\"")
    }
}
