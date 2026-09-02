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
import io.github.alpharomercoma.openweights.core.tools.AgentMode
import io.github.alpharomercoma.openweights.ui.tools.AppToolRegistry
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

        // The catalogue at its fullest, which is what the harness replays: the registry
        // the app builds, with a folder shared and the network up, filtered the way a
        // turn filters it, in the registry's order, which is the order the model sees.
        // The two plan-mode tools gate themselves behind boards nothing here has written
        // to, exactly as they do on the phone, and the switches are taken as all on, as
        // the phone that produced the first dump had them. A list kept by hand here had
        // fallen two memory tools behind the app; this one cannot.
        val tools = AppToolRegistry.build(context, sharedFolder = true).all
            .filter { it.isAvailable }

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
