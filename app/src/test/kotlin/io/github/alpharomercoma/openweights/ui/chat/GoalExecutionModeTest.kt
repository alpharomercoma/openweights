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

package io.github.alpharomercoma.openweights.ui.chat

import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.tools.AgentMode
import org.junit.Test

class GoalExecutionModeTest {
    @Test
    fun `a goal planned from plan mode executes in auto`() {
        assertThat(goalExecutionMode(requested = null, previous = AgentMode.PLAN))
            .isEqualTo(AgentMode.AUTO)
    }

    @Test
    fun `research override cannot inherit yolo`() {
        assertThat(goalExecutionMode(requested = AgentMode.AUTO, previous = AgentMode.YOLO))
            .isEqualTo(AgentMode.AUTO)
    }

    @Test
    fun `generic goal preserves a non-plan user mode`() {
        assertThat(goalExecutionMode(requested = null, previous = AgentMode.ASK))
            .isEqualTo(AgentMode.ASK)
    }
}
