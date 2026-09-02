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

package io.github.alpharomercoma.openweights.ui.tools

import android.content.Context
import android.net.Uri
import io.github.alpharomercoma.openweights.core.sandbox.Sandbox
import io.github.alpharomercoma.openweights.core.tools.AdvanceTool
import io.github.alpharomercoma.openweights.core.tools.AskBoard
import io.github.alpharomercoma.openweights.core.tools.AskUserTool
import io.github.alpharomercoma.openweights.core.tools.CanvasBoard
import io.github.alpharomercoma.openweights.core.tools.DeleteFileTool
import io.github.alpharomercoma.openweights.core.tools.FetchUrlTool
import io.github.alpharomercoma.openweights.core.tools.ForgetMemoryTool
import io.github.alpharomercoma.openweights.core.tools.Memory
import io.github.alpharomercoma.openweights.core.tools.PlanBoard
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
import io.github.alpharomercoma.openweights.core.tools.ToolRegistry
import io.github.alpharomercoma.openweights.core.tools.UpdateMemoryTool
import io.github.alpharomercoma.openweights.core.tools.WebSearchTool
import io.github.alpharomercoma.openweights.core.tools.Workspace
import io.github.alpharomercoma.openweights.core.tools.WorkspaceGrant
import io.github.alpharomercoma.openweights.core.tools.WriteFileTool
import io.github.alpharomercoma.openweights.core.tools.di.ToolsModule
import okhttp3.OkHttpClient

/**
 * The registry the app really ships, built by the module that builds it.
 *
 * Two tests used to keep their own lists of tools, and both had fallen behind: the
 * catalogue checks covered ten of the eighteen schemas the app registers, and the eval
 * dump was missing two memory tools the phone had been offering for weeks. Hilt hands
 * [ToolsModule.registry] its tools in production and there is no Hilt graph under
 * Robolectric, so the tools are constructed here by hand with the same collaborators, and
 * the module itself does the listing and the ordering. A tool added to the app is a new
 * parameter there, and this stops compiling until it is given one, which is the drift this
 * exists to make impossible.
 */
internal object AppToolRegistry {
    /**
     * @param online What the web tools are told about the network. Up by default, because
     *   a test over the catalogue is about what it says rather than about when it is
     *   offered.
     * @param sharedFolder Whether to stand in a folder the file and canvas tools can work
     *   in, which is the only condition under which they describe themselves to the model.
     *   The grant is the real one, taken through the resolver, so [Workspace.isReady] and
     *   [Workspace.acceptsNewFiles] answer the way they do on a phone.
     */
    fun build(
        context: Context,
        online: Boolean = true,
        sharedFolder: Boolean = false,
    ): ToolRegistry {
        val reachability = Reachability { online }
        val client = OkHttpClient()
        val grant = WorkspaceGrant(context)
        if (sharedFolder) grant.remember(Uri.parse(SHARED_FOLDER))
        val workspace = Workspace(context, grant)
        val artifacts = SessionArtifacts()
        val board = CanvasBoard()
        val settings = SearchSettings(context, SecretSealer.Unavailable)
        val memory = Memory(context)
        return ToolsModule.registry(
            search = WebSearchTool(client, settings, reachability),
            media = SearchMediaTool(client, settings, reachability),
            fetch = FetchUrlTool(client, reachability, workspace, artifacts),
            searchFiles = SearchFilesTool(workspace),
            readFile = ReadFileTool(workspace),
            writeFile = WriteFileTool(workspace, artifacts, board),
            deleteFile = DeleteFileTool(workspace, artifacts),
            showWebsite = ShowWebsiteTool(workspace, board),
            showDocument = ShowDocumentTool(workspace, board),
            showSlides = ShowSlidesTool(workspace, board),
            runScript = RunScriptTool(Sandbox(context), workspace),
            advance = AdvanceTool(PlanBoard()),
            askUser = AskUserTool(AskBoard()),
            watch = ToolsModule.watchTool { _, _ -> null },
            readMemory = ReadMemoryTool(memory),
            saveMemory = SaveMemoryTool(memory),
            updateMemory = UpdateMemoryTool(memory),
            forgetMemory = ForgetMemoryTool(memory),
        )
    }

    /** A document tree the shadow resolver will record a persistable grant on. */
    private const val SHARED_FOLDER =
        "content://com.android.externalstorage.documents/tree/primary%3AOpenWeights"
}
