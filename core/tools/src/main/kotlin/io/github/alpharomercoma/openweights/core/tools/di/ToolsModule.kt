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

package io.github.alpharomercoma.openweights.core.tools.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.alpharomercoma.openweights.core.tools.AdvanceTool
import io.github.alpharomercoma.openweights.core.tools.AskUserTool
import io.github.alpharomercoma.openweights.core.tools.FetchUrlTool
import io.github.alpharomercoma.openweights.core.tools.ReadFileTool
import io.github.alpharomercoma.openweights.core.tools.RunScriptTool
import io.github.alpharomercoma.openweights.core.tools.SearchFilesTool
import io.github.alpharomercoma.openweights.core.tools.ToolRegistry
import io.github.alpharomercoma.openweights.core.tools.WebSearchTool
import io.github.alpharomercoma.openweights.core.tools.WriteFileTool
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ToolsModule {
    /**
     * Every tool the app knows how to run.
     *
     * Search then read, in that order, because that is the order a model uses them and the
     * order they are listed in is the order they appear to it. The file tools follow the
     * same shape and come after, and they describe themselves to the model only once a
     * folder has been shared, so an install that never shares one carries none of them.
     */
    @Provides
    @Singleton
    fun registry(
        search: WebSearchTool,
        fetch: FetchUrlTool,
        searchFiles: SearchFilesTool,
        readFile: ReadFileTool,
        writeFile: WriteFileTool,
        runScript: RunScriptTool,
        advance: AdvanceTool,
        askUser: AskUserTool,
    ): ToolRegistry = ToolRegistry(
        listOf(search, fetch, searchFiles, readFile, writeFile, runScript, advance, askUser),
    )
}
