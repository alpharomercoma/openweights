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

package io.github.alpharomercoma.openweights.core.common

import io.github.alpharomercoma.openweights.core.common.context.CompactionPolicy
import io.github.alpharomercoma.openweights.core.common.model.OutputModality
import io.github.alpharomercoma.openweights.core.common.model.ToolCallParser
import io.github.alpharomercoma.openweights.core.common.model.Tunable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shared logic, run on every platform rather than merely compiled for them.
 *
 * The distinction matters and this repository has already been caught by it once: the first
 * version of this module reported a successful build while compiling zero classes, because
 * its sources were still in a directory the Android plugin claimed. "It compiles for iOS" is
 * a weaker sentence than it sounds, and this file is the stronger one.
 *
 * Written against `kotlin.test` because Truth and JUnit are JVM only. The rest of the suite
 * stays on Truth: rewriting a hundred working assertions to gain a second execution of
 * arithmetic would be motion rather than progress. What is here is the code where a platform
 * difference could plausibly hide.
 */
class PortableTest {
    @Test
    fun controlCharactersEscapeTheSameWayEverywhere() {
        // Through the production code, not beside it. The first version of this test
        // asserted on `padStart` directly, which proves the standard library works and says
        // nothing about the function that was changed: a reviewer pointed out it would pass
        // unchanged if ToolCallParser had been left broken.
        //
        // The line that stopped this module compiling for iOS was a String.format call,
        // which is JVM only. Its replacement pads by hand, and hand-rolled padding is
        // exactly the kind of thing that is right on one platform and off by a digit on
        // another. Bell is the interesting case: one significant digit, three of padding.
        val raw = "<tool_call><function=write><parameter=text>a\u0007b\u001Fc</parameter>" +
            "</function></tool_call>"

        val call = ToolCallParser.parse(raw).calls.single()

        // The escaped form must be four hex digits each, lower case, zero padded. If the
        // padding were wrong the JSON would still look plausible and would decode to the
        // wrong character, which is the failure mode worth catching on both platforms.
        assertTrue(call.argumentsJson.contains("a\\u0007b\\u001fc"), call.argumentsJson)
    }

    @Test
    fun theCompactionArithmeticHoldsOnEveryPlatform() {
        // Float division and comparison, which is where a native target would differ from
        // the JVM if it were going to differ anywhere.
        val policy = CompactionPolicy()

        assertTrue(
            policy.shouldCompact(contextUsed = 3_500, contextSize = 4_096, entryCount = 12),
        )
        assertFalse(
            policy.shouldCompact(contextUsed = 10, contextSize = 4_096, entryCount = 12),
        )
    }

    @Test
    fun aSpeechModelReadsTheSameThreeSettingsOnEveryPlatform() {
        assertTrue(OutputModality.TEXT.accepts(Tunable.TEMPERATURE))
        assertFalse(OutputModality.SPEECH.accepts(Tunable.TEMPERATURE))
        assertEquals(
            listOf(Tunable.TOP_K, Tunable.TOP_P, Tunable.SEED),
            Tunable.entries.filter { OutputModality.SPEECH.accepts(it) },
        )
    }
}
