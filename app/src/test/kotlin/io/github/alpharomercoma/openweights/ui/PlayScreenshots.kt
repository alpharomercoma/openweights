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

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The Play Store screenshots, rendered on the host rather than captured off a phone.
 *
 * Not really tests. Each is skipped unless `OPENWEIGHTS_SCREENSHOTS=<dir>` says where to put
 * the files, so an ordinary `./gradlew verify` runs them as no-ops. An environment variable
 * rather than a system property because Gradle forks the test JVM and only the environment
 * survives the fork without a build-script change. They live in the test source set because
 * that is the only place the real composables, the real theme and a renderer exist together.
 *
 * Rendered rather than captured for three reasons, in order of what they cost. A screenshot
 * off a modern handset is 1220 x 2712, and Play refuses any image whose long side is more
 * than twice its short one, so every capture would need reframing before it could be
 * uploaded at all. A capture needs the device unlocked and driven through `adb shell input`,
 * which is the thing this project already avoids. And a capture is whatever the model
 * happened to say that minute, where these are the same pixels every time, so the listing
 * can be regenerated after a UI change without anyone re-staging a conversation.
 *
 * What is on screen is the real thing: the actual `ChatScreen`, the actual theme, the actual
 * type, at the actual size. Only the state is composed, the way any product screenshot
 * stages its content, and the numbers in it are ones this app has really produced.
 *
 * One screen per test, because a compose rule takes one `setContent` and throws on the
 * second.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// 360 x 640 dp at xxhdpi is 1080 x 1920 px, which is the 9:16 Play asks for. Night because
// the app is dark-first and MarkdownText reads the system setting directly for its code
// theme, so telling the theme alone would light the syntax highlighting and nothing else.
// Qualifier order is the resource-directory order, and night comes before density.
@Config(qualifiers = "w360dp-h640dp-night-xxhdpi")
class PlayScreenshots {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun chatWithItsTelemetry() = shoot(
        "01-chat",
        until = "attention layers",
        // Staged with something typed, the way any product screenshot stages its content.
        // An empty composer means a disabled send button, which means the app's one accent
        // colour appears nowhere at all on the screen the listing leads with.
        typing = DRAFT,
    ) { ChatShots.midReply() }

    // Opened, because a finished tool run now folds to one line and the listing is the one
    // place the folded state is the wrong one to show: what is behind the line is the whole
    // reason to install this rather than a hosted assistant.
    @Test
    fun aToolRunAndTheAnswerItFed() =
        shoot("02-tools", until = "thunderstorms", tap = SHOW_STEPS) { ChatShots.toolRound() }

    @Test
    fun aPlanYouCanTick() = shoot("03-plan", until = "Write the summary") {
        ChatShots.planned()
    }

    @Test
    fun theHubRatherThanACatalogue() =
        shoot("04-discover", until = "Hammer2.1") { ListingShots.discover() }

    @Test
    fun everyToolWithAnOffSwitch() = shoot("05-tools", until = "Search the web") {
        ListingShots.tools()
    }

    // The two tablet slots. Both are 9:16 in pixels, which is what Play asks for, and both
    // are above the width the conversation is now capped at, so what they show is the real
    // large-screen layout rather than a phone stretched sideways. Four each: Play wants at
    // least four at 1080px to consider a listing for promotion.
    @Test
    @Config(qualifiers = SEVEN_INCH)
    fun sevenInchChat() = shoot(
        "7/01-chat",
        until = "attention layers",
        typing = DRAFT,
    ) { ChatShots.midReply() }

    @Test
    @Config(qualifiers = SEVEN_INCH)
    fun sevenInchTools() =
        shoot("7/02-tools", until = "thunderstorms", tap = SHOW_STEPS) { ChatShots.toolRound() }

    @Test
    @Config(qualifiers = SEVEN_INCH)
    fun sevenInchPlan() = shoot("7/03-plan", until = "Write the summary") { ChatShots.planned() }

    @Test
    @Config(qualifiers = SEVEN_INCH)
    fun sevenInchDiscover() = shoot("7/04-discover", until = "Hammer2.1") {
        ListingShots.discover()
    }

    @Test
    @Config(qualifiers = TEN_INCH)
    fun tenInchChat() = shoot(
        "10/01-chat",
        until = "attention layers",
        typing = DRAFT,
    ) { ChatShots.midReply() }

    @Test
    @Config(qualifiers = TEN_INCH)
    fun tenInchTools() =
        shoot("10/02-tools", until = "thunderstorms", tap = SHOW_STEPS) { ChatShots.toolRound() }

    @Test
    @Config(qualifiers = TEN_INCH)
    fun tenInchPlan() = shoot("10/03-plan", until = "Write the summary") { ChatShots.planned() }

    @Test
    @Config(qualifiers = TEN_INCH)
    fun tenInchDiscover() = shoot("10/04-discover", until = "Hammer2.1") {
        ListingShots.discover()
    }

    // The paper palette, which no listing asks for and which nobody had ever looked at.
    //
    // Both themes are meant to be built properly rather than one being a tint of the other,
    // and the light one carries the palette's sharpest rule: lime measures 1.13:1 on white,
    // so anything that paints the accent as a word or a line is invisible there and fine on
    // the dark canvas the app is developed in. These go in a `light/` folder, are gated by
    // the same environment variable, and are for looking at rather than for uploading.
    @Test
    @Config(qualifiers = PAPER)
    fun paperChat() = shoot("light/01-chat", until = "attention layers", dark = false) {
        ChatShots.midReply()
    }

    @Test
    @Config(qualifiers = PAPER)
    fun paperTools() = shoot("light/02-tools", until = "Search the web", dark = false) {
        ListingShots.tools()
    }

    @Test
    @Config(qualifiers = PAPER)
    fun paperDiscover() = shoot("light/03-discover", until = "Hammer2.1", dark = false) {
        ListingShots.discover()
    }

    @Test
    @Config(qualifiers = PAPER)
    fun paperSettings() = shoot("light/04-settings", until = "Appearance", dark = false) {
        ListingShots.settings()
    }

    @Test
    @Config(qualifiers = PAPER)
    fun paperUsage() = shoot("light/05-usage", until = "By model", dark = false) {
        ListingShots.usage()
    }

    /**
     * One screen, drawn straight off its own view.
     *
     * `captureToImage` is the obvious call and it does not work here: it goes through
     * `PixelCopy` and waits for a redraw a host JVM never delivers, so it times out after
     * two seconds having produced nothing. Drawing the view onto a bitmap is what
     * Robolectric's native graphics mode is for, and it is the same pixels.
     */
    private fun shoot(
        name: String,
        until: String,
        dark: Boolean = true,
        typing: String? = null,
        tap: String? = null,
        content: @Composable () -> Unit,
    ) {
        val into = System.getenv(OUTPUT) ?: return
        val directory = File(into)

        compose.setContent {
            OpenWeightsTheme(
                themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT,
                dynamicColor = false,
            ) { content() }
        }
        // Markdown is parsed off the composition and swapped in when it finishes, so a
        // capture taken at idle gets the empty slot it holds in the meantime. The first
        // attempt drew a reply with the telemetry row under it and no reply above it.
        compose.waitUntil(timeoutMillis = WAIT_MS) {
            compose.onAllNodesWithText(until, substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        tap?.let { compose.onNodeWithContentDescription(it).performClick() }
        typing?.let { compose.onNodeWithContentDescription("Message").performTextInput(it) }
        compose.waitForIdle()

        val view = (compose.onRoot().fetchSemanticsNode().root as ViewRootForTest).view
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))

        val file = File(directory, "$name.png").apply { parentFile?.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        check(file.length() > 0) { "wrote nothing to ${file.name}" }
        println("screenshot ${file.name}: ${bitmap.width}x${bitmap.height} ${file.length()} bytes")
    }

    private companion object {
        const val OUTPUT = "OPENWEIGHTS_SCREENSHOTS"

        /** The header a finished tool run folds behind. See `WorkBlock`. */
        const val SHOW_STEPS = "Show the steps"

        /** Long enough for a markdown parse on a cold host JVM, short enough to fail fast. */
        const val WAIT_MS = 10_000L

        /** 600 x 1067 dp at xhdpi is 1200 x 2134 px: 9:16, inside the 320..3840 Play allows. */
        const val SEVEN_INCH = "w600dp-h1067dp-night-xhdpi"

        /** 900 x 1600 dp at xhdpi is 1800 x 3200 px: 9:16, inside the 1080..7680 Play allows. */
        const val TEN_INCH = "w900dp-h1600dp-night-xhdpi"

        /** The phone canvas again, without the night qualifier. */
        const val PAPER = "w360dp-h640dp-xxhdpi"

        /**
         * Something typed, in every chat shot.
         *
         * Staged the way any product screenshot stages its content. An empty composer means
         * a disabled send button, which means the app's one accent colour appears nowhere
         * at all on the screen the listing leads with.
         */
        const val DRAFT = "And how much memory does the cache take?"
    }
}
