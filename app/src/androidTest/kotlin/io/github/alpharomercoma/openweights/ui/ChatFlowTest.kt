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

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
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
import java.io.File

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
 * Two things about the device, both of which fail in a way that points at the wrong place.
 *
 * The phone has to be awake with the lock screen down, or every case here fails with
 * `No compose hierarchies found in the app`. That reads as a `setContent` problem and is
 * not one: behind a keyguard the activity reaches RESUMED and is PAUSED milliseconds
 * later, and a window that is never visible never composes.
 * ```
 * adb shell input keyevent KEYCODE_WAKEUP && adb shell wm dismiss-keyguard
 * ```
 *
 * And a model has to be where the app looks, which is not where the engine tests look.
 * Without one the app opens on "Pick a model to begin", a screen with no composer, so the
 * cases below hunt a "Message" field the app is right not to be showing.
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
        // at the moment a destination is tapped, which is to say in front of a person.
        //
        // These used to be five tabs on a bottom bar and are now three rows in the drawer,
        // pushed over the conversation rather than swapped with it. Models left the list
        // entirely: it is reached from the model name in the top bar, which is a different
        // gesture and belongs in a different test.
        listOf("Tools", "Usage", "Settings").forEach { destination ->
            rule.onNodeWithContentDescription("Past chats").performClick()
            rule.waitForIdle()

            // The drawer sheet stays composed while closed, so its own row and the title of
            // the screen it opens are two nodes carrying the same word. Take the drawer's.
            rule.onAllNodesWithText(destination).onFirst().performClick()
            rule.waitForIdle()

            // Pushed, not swapped: the conversation is gone rather than behind a tab.
            rule.onNodeWithContentDescription("Message").assertDoesNotExist()

            goBack()
            rule.waitForIdle()
        }
        rule.onNodeWithContentDescription("Message").assertIsDisplayed()
    }

    @Test
    fun theDrawerOpensAndCloses() {
        // It asserted on a "Chats" heading, which the drawer no longer has: it opens on the
        // New chat pill and a search field, because a list of past conversations does not
        // need a word telling you it is a list of past conversations.
        rule.onNodeWithContentDescription("Past chats").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("New chat").assertIsDisplayed()

        // And closes, which the name has always promised and the body never checked.
        goBack()
        rule.waitForIdle()
        rule.onNodeWithText("New chat").assertIsNotDisplayed()
        rule.onNodeWithContentDescription("Message").assertIsDisplayed()
    }

    /**
     * The system back gesture, which is how every pushed screen and the drawer are left.
     *
     * Through the activity's own dispatcher rather than Espresso, so this suite keeps its
     * one dependency on Compose testing and does not grow a second on a UI framework it
     * otherwise never touches.
     */
    private fun goBack() = rule.runOnUiThread {
        rule.activity.onBackPressedDispatcher.onBackPressed()
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

        /**
         * The lock screen, which fails exactly the way the permission dialog above did.
         *
         * A window behind a keyguard is never visible, so the activity reaches RESUMED and
         * is PAUSED milliseconds later and Compose never attaches. The error says "No
         * compose hierarchies found in the app", which sends you looking at `setContent`.
         * A cloud device arrives locked and a desk device locks itself while you are
         * reading, so this is not an unusual state to be in.
         *
         * Fixed here rather than written down for the same reason the permission is: a
         * setup step in a document is a step somebody runs once and then forgets on the
         * machine where it matters.
         */
        @JvmStatic
        @BeforeClass
        fun unlockTheScreen() {
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
        }

        /**
         * A model, moved from where the engine tests keep one to where the app looks.
         *
         * These are two different places and nothing bridged them. `/data/local/tmp` is
         * outside any package, survives an uninstall, and is where a pushed GGUF lands; the
         * app reads its own external files directory, which Gradle's
         * `connectedAndroidTest` deletes every run when it uninstalls afterwards. So the
         * fixture had to be re-copied by hand between every single run, which is not a
         * thing anybody remembers to do.
         *
         * It matters more than it used to. Before the redesign most of this suite ran
         * without a model; now the app opens on "Pick a model to begin" instead of a
         * conversation, and a screen with no composer fails every case that reaches for
         * one. Copied through the shell rather than with Kotlin file APIs because the app's
         * own uid cannot read shell-owned storage, and `-n` so a real download is never
         * overwritten by a test fixture.
         */
        @JvmStatic
        @BeforeClass
        fun installAModelIfOneWasPushed() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val models = File(
                context.getExternalFilesDir(null) ?: context.filesDir,
                "models",
            )
            if (!File(PUSHED_MODEL).isFile && !models.isDirectory) return
            shell("mkdir -p ${models.absolutePath}")
            shell("cp -n $PUSHED_MODEL ${models.absolutePath}/$FIXTURE_NAME")
        }

        /**
         * One shell command, run to completion.
         *
         * `executeShellCommand` returns as soon as the command is spawned, so the pipe has
         * to be drained: a keyguard dismissed after the activity launched is a keyguard
         * that was not dismissed.
         */
        private fun shell(command: String) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand(command)
                .use { descriptor ->
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                        .use { it.readBytes() }
                }
        }

        /** Where a pushed GGUF lives, outside any package and safe from an uninstall. */
        private const val PUSHED_MODEL = "/data/local/tmp/openweights/model.gguf"

        /** What it is called once it is somewhere the app can see it. */
        private const val FIXTURE_NAME = "qwen.gguf"
    }
}
