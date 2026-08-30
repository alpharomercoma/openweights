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

package io.github.alpharomercoma.openweights.ui.drawer

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.ui.chat.ConversationDrawer
import io.github.alpharomercoma.openweights.ui.chat.ConversationSummary
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Renders the drawer on a real screen so the branding can be looked at rather than argued.
 *
 * A screenshot is the only thing that answers "does the lockup read": whether the mark sits
 * level with the letters, whether the wordmark holds its weight at 19sp, and how much of the
 * history is above the fold once the header has taken its row back from the search field.
 * None of that is assertable, and all of it is the point, so this writes PNGs and asserts
 * only that they were produced.
 */
class DrawerLookOnDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = System.currentTimeMillis()
    private val hour = TimeUnit.HOURS.toMillis(1)
    private val day = TimeUnit.DAYS.toMillis(1)

    private val history = listOf(
        ConversationSummary(1, "Markdown stress test", "lfm", now - hour, pinnedAt = now),
        ConversationSummary(2, "Harness review against LangGraph", "lfm", now - 2 * hour),
        ConversationSummary(3, "Watch timer counts down", "lfm", now - 5 * hour),
        ConversationSummary(4, "Archive information architecture", "lfm", now - day),
        ConversationSummary(5, "Why is nothing bold?", "lfm", now - day - hour),
        ConversationSummary(6, "Speculative decoding, measured", "lfm", now - 3 * day),
        ConversationSummary(7, "Paste a picture into the composer", "lfm", now - 4 * day),
        ConversationSummary(8, "KV cache and the stats that lie", "lfm", now - 9 * day),
    )

    @Test
    fun theDrawerAsItActuallyLooks() {
        var term by mutableStateOf("")
        var searchShot = false
        compose.setContent {
            OpenWeightsTheme(dynamicColor = false) {
                ConversationDrawer(
                    conversations = history,
                    activeId = 2,
                    onOpen = {},
                    onNewChat = {},
                    nowMillis = now,
                    archivedCount = 3,
                    search = term,
                    hasSearchAnswer = true,
                    onSearch = { term = it },
                )
            }
        }

        compose.waitForIdle()
        capture("01-drawer-branded")

        // And with the search open, which is the state that used to be the default and is
        // now what the wordmark's row was bought with.
        term = "cache"
        searchShot = true
        compose.waitForIdle()
        capture("02-drawer-searching")

        check(searchShot)
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = File(
            InstrumentationRegistry.getInstrumentation()
                .targetContext.getExternalFilesDir(null),
            "drawer-look",
        ).apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
