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

import androidx.test.core.app.ApplicationProvider
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.sandbox.Sandbox
import io.github.alpharomercoma.openweights.core.tools.AgentMode
import io.github.alpharomercoma.openweights.core.tools.CanvasBoard
import io.github.alpharomercoma.openweights.core.tools.DeleteFileTool
import io.github.alpharomercoma.openweights.core.tools.FetchUrlTool
import io.github.alpharomercoma.openweights.core.tools.Memory
import io.github.alpharomercoma.openweights.core.tools.Reachability
import io.github.alpharomercoma.openweights.core.tools.ReadFileTool
import io.github.alpharomercoma.openweights.core.tools.ReadMemoryTool
import io.github.alpharomercoma.openweights.core.tools.RunScriptTool
import io.github.alpharomercoma.openweights.core.tools.SaveMemoryTool
import io.github.alpharomercoma.openweights.core.tools.SearchFilesTool
import io.github.alpharomercoma.openweights.core.tools.SearchMediaTool
import io.github.alpharomercoma.openweights.core.tools.SearchSettings
import io.github.alpharomercoma.openweights.core.tools.SecretSealer
import io.github.alpharomercoma.openweights.core.tools.SessionArtifacts
import io.github.alpharomercoma.openweights.core.tools.ShowDocumentTool
import io.github.alpharomercoma.openweights.core.tools.ShowSlidesTool
import io.github.alpharomercoma.openweights.core.tools.ShowWebsiteTool
import io.github.alpharomercoma.openweights.core.tools.WatchTool
import io.github.alpharomercoma.openweights.core.tools.WebSearchTool
import io.github.alpharomercoma.openweights.core.tools.Workspace
import io.github.alpharomercoma.openweights.core.tools.WorkspaceGrant
import io.github.alpharomercoma.openweights.core.tools.WriteFileTool
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Not a test: a byte-exact export of what the app sends the model, for the offline
 * routing harness. Dumps the system message, the decorated first user turn, and the
 * shipped tool catalogue as JSON, so llama-server on a workstation can replay the
 * app's prompts against candidate date placements without an APK install.
 */
@RunWith(RobolectricTestRunner::class)
class PromptDumpTest {

    @Test
    fun dump() {
        val out = System.getenv("PROMPT_DUMP") ?: return
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val client = OkHttpClient()
        val workspace = Workspace(context, WorkspaceGrant(context))
        val board = CanvasBoard()
        val artifacts = SessionArtifacts()
        val settings = SearchSettings(context, SecretSealer.Unavailable)
        val online = Reachability { true }
        val memory = Memory(context)

        // The device's live order, from the turn log: the order they are listed in is the
        // order the model sees.
        val tools = listOf(
            WebSearchTool(client, settings, online),
            SearchMediaTool(client, settings, online),
            FetchUrlTool(client, online, workspace, artifacts),
            SearchFilesTool(workspace),
            ReadFileTool(workspace),
            WriteFileTool(workspace, artifacts, board),
            DeleteFileTool(workspace, artifacts),
            ShowWebsiteTool(workspace, board),
            ShowDocumentTool(workspace, board),
            ShowSlidesTool(workspace, board),
            RunScriptTool(Sandbox(context), workspace),
            WatchTool { _, _ -> null },
            ReadMemoryTool(memory),
            SaveMemoryTool(memory),
        )

        val state = ChatUiState(
            transcript = listOf(TranscriptEntry(id = 0, role = ChatRole.USER, text = "hi")),
            mode = AgentMode.AUTO,
            supportsTools = true,
            toolsAvailable = true,
        )
        val messages = state.engineMessages()

        val json = JSONObject()
            .put("system", messages.first { it.role == ChatRole.SYSTEM }.text)
            .put("firstUser", messages.first { it.role == ChatRole.USER }.text)
            .put(
                "tools",
                JSONArray(
                    tools.map {
                        JSONObject()
                            .put("name", it.definition.name)
                            .put("description", it.definition.description)
                            .put("parameters", JSONObject(it.definition.parametersJson))
                    },
                ),
            )
        File(out).writeText(json.toString(2))
    }
}
