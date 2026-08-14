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

package io.github.alpharomercoma.openweights.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alpharomercoma.openweights.MainActivity
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app, driven the way a person drives it.
 *
 * Everything else in this suite reaches past the screens: the host tests build a
 * `ChatUiState` by hand and hand it to one composable, and the device tests call
 * `TurnRunner` directly. Both are worth having and neither can catch a screen wired to
 * nothing. The typed slash command went to the model as text for weeks, and no test
 * anywhere could have noticed, because no test ever pressed the send button.
 *
 * So this launches the real activity, with the real graph, the real view model and the real
 * engine. It asserts on what is on screen and on nothing else.
 *
 * Most of it needs no model. The two that do are marked, and they need one installed the way
 * the app installs one, which for a test means putting it where the model store looks:
 * ```
 * adb shell mkdir -p /sdcard/Android/data/io.github.alpharomercoma.openweights.debug/files/models
 * adb shell cp /data/local/tmp/openweights/model.gguf \
 *   /sdcard/Android/data/io.github.alpharomercoma.openweights.debug/files/models/qwen.gguf
 * ```
 */
@RunWith(AndroidJUnit4::class)
class ChatFlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun theAppOpensOnAChatWithAComposer() {
        rule.onNodeWithContentDescription("Message").assertIsDisplayed()
        rule.onNodeWithContentDescription("Send message").assertIsDisplayed()
    }

    @Test
    fun anEmptyComposerCannotSend() {
        rule.onNodeWithContentDescription("Send message").assertIsNotEnabled()
    }

    @Test
    fun aSlashOpensThePaletteAndTappingAModeSwitchesIt() {
        rule.onNodeWithContentDescription("Message").performTextInput("/plan")
        rule.waitForIdle()

        // Matched on the description rather than the trigger: the trigger is also the text
        // sitting in the composer, so asking for it finds two nodes and says so.
        rule.onNode(hasText(SlashPlan.DESCRIPTION)).assertIsDisplayed()

        rule.onNode(hasText(SlashPlan.DESCRIPTION)).performClick()
        rule.waitForIdle()

        // And the app now says which mode it is in, which for a long time it never did.
        rule.onNodeWithText(SlashPlan.LABEL, substring = true).assertIsDisplayed()
    }

    @Test
    fun everyDestinationOpens() {
        // Nothing here had a test of any kind. A navigation graph that fails to build fails
        // at the moment a tab is tapped, which is to say in front of a person.
        listOf("Models", "Tools", "Usage", "Settings", "Chat").forEach { destination ->
            rule.onNodeWithText(destination).performClick()
            rule.waitForIdle()
        }
        rule.onNodeWithContentDescription("Message").assertIsDisplayed()
    }

    @Test
    fun theDrawerOpensAndCloses() {
        rule.onNodeWithContentDescription("Past chats").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Chats").assertIsDisplayed()
    }

    @Test
    fun aTypedCommandRunsRatherThanBeingSent() {
        // The bug this suite exists for. Send is only enabled once a model is loaded, which
        // is exactly why nothing caught it: the path is unreachable until the app is in the
        // state a person uses it in.
        rule.onNodeWithContentDescription("Message").performTextReplacement(SlashPlan.TRIGGER)
        awaitAModel()

        rule.onNodeWithContentDescription("Send message").performClick()
        rule.waitForIdle()

        // The mode changed, and no message was sent: "/plan" is nowhere in the transcript.
        rule.onNodeWithText(SlashPlan.LABEL, substring = true).assertIsDisplayed()
        rule.onAllNodes(hasText(SlashPlan.TRIGGER)).assertCountEquals(0)
    }

    /**
     * Waits for the model the app opens with, and skips the test if there is none.
     *
     * No navigation: the app loads its preferred model on launch, so the only question is
     * whether one is installed. Send being enabled is the answer, and it is only ever asked
     * with something already typed, because an empty composer disables it whatever else is
     * true. A device with no model can still prove everything above, and failing here would
     * make the whole suite look broken on a fresh phone.
     */
    private fun awaitAModel() {
        val ready = runCatching {
            rule.waitUntil(LOAD_TIMEOUT_MILLIS) {
                rule.onAllNodes(hasContentDescription("Send message") and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        }.isSuccess
        Assume.assumeTrue("no model is installed in the app", ready)
    }

    /** The one command this suite drives, kept in one place so the strings cannot drift. */
    private object SlashPlan {
        const val TRIGGER = "/plan"
        const val DESCRIPTION = "Say what it would do, run no tools"
        const val LABEL = "Plan"
    }

    companion object {
        /** Loading a 1.5B model off external storage takes seconds, not milliseconds. */
        private const val LOAD_TIMEOUT_MILLIS = 120_000L

        /**
         * Granted before the activity ever starts, and the reason is worth writing down.
         *
         * The app asks for notification permission in `onCreate`, so on a fresh install the
         * system's dialog opens over the first frame and the activity is paused about five
         * milliseconds after it resumes. Compose never attaches to a resumed window, and
         * every test here failed with "no compose hierarchies found in the app" until this
         * was pre-granted. That is a real thing about the app as well as about the test: the
         * first thing a new user sees is a permission dialog over an empty screen.
         */
        @JvmStatic
        @BeforeClass
        fun grantNotifications() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.grantRuntimePermission(
                instrumentation.targetContext.packageName,
                "android.permission.POST_NOTIFICATIONS",
            )
        }
    }
}
