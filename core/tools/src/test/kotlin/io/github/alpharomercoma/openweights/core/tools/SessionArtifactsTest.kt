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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the session made, it may rework without asking; what it found, it asks about.
 */
@RunWith(RobolectricTestRunner::class)
class SessionArtifactsTest {

    @Test
    fun `its own file is its own, whatever the case`() {
        val artifacts = SessionArtifacts()
        artifacts.created("Projects/Site/Index.html")

        assertThat(artifacts.isOwn("projects/site/index.html")).isTrue()
        assertThat(artifacts.isOwn("projects/site/other.html")).isFalse()
    }

    @Test
    fun `what a chat made is the user's once the chat is left`() {
        // The object is one for the whole process, and the chat is not: a file made in one
        // conversation stayed silently replaceable in the next for as long as the app lived.
        val artifacts = SessionArtifacts()
        artifacts.created("site/index.html")

        artifacts.cleared()

        assertThat(artifacts.isOwn("site/index.html")).isFalse()
    }

    @Test
    fun `deleting a user's file asks, deleting the session's own does not`() {
        val artifacts = SessionArtifacts()
        artifacts.created("scratch/attempt.js")
        val tool = DeleteFileTool(workspace = workspaceless(), artifacts = artifacts)

        assertThat(tool.asksInAuto(deleteCall("scratch/attempt.js"))).isFalse()
        assertThat(tool.asksInAuto(deleteCall("taxes/2025.csv"))).isTrue()
    }

    private fun deleteCall(path: String) = ToolCall(
        id = "1",
        name = "delete_file",
        argumentsJson = """{"path":"$path"}""",
    )

    // The asking rule reads nothing but the call and the artifacts; the workspace is a
    // real one over a test context with no folder granted, which is exactly that.
    private fun workspaceless(): Workspace = Workspace(
        ApplicationProvider.getApplicationContext(),
        WorkspaceGrant(ApplicationProvider.getApplicationContext()),
    )
}
