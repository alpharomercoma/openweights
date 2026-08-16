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
import androidx.compose.ui.test.onRoot
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
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
    fun chatWithItsTelemetry() =
        shoot("01-chat", until = "attention layers") { ChatShots.midReply() }

    @Test
    fun aToolRunAndTheAnswerItFed() =
        shoot("02-tools", until = "thunderstorms") { ChatShots.toolRound() }

    @Test
    fun aPlanYouCanTick() = shoot("03-plan", until = "Write the summary") {
        ChatShots.planned()
    }

    @Test
    fun theHubRatherThanACatalogue() =
        shoot("04-discover", until = "Hammer2.1") { ListingShots.discover() }

    @Test
    fun everyToolWithAnOffSwitch() = shoot("05-tools", until = "Web search") {
        ListingShots.tools()
    }

    /**
     * One screen, drawn straight off its own view.
     *
     * `captureToImage` is the obvious call and it does not work here: it goes through
     * `PixelCopy` and waits for a redraw a host JVM never delivers, so it times out after
     * two seconds having produced nothing. Drawing the view onto a bitmap is what
     * Robolectric's native graphics mode is for, and it is the same pixels.
     */
    private fun shoot(name: String, until: String, content: @Composable () -> Unit) {
        val into = System.getenv(OUTPUT) ?: return
        val directory = File(into).apply { mkdirs() }

        compose.setContent { OpenWeightsTheme(dynamicColor = false) { content() } }
        // Markdown is parsed off the composition and swapped in when it finishes, so a
        // capture taken at idle gets the empty slot it holds in the meantime. The first
        // attempt drew a reply with the telemetry row under it and no reply above it.
        compose.waitUntil(timeoutMillis = WAIT_MS) {
            compose.onAllNodesWithText(until, substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        val view = (compose.onRoot().fetchSemanticsNode().root as ViewRootForTest).view
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))

        val file = File(directory, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        check(file.length() > 0) { "wrote nothing to ${file.name}" }
        println("screenshot ${file.name}: ${bitmap.width}x${bitmap.height} ${file.length()} bytes")
    }

    private companion object {
        const val OUTPUT = "OPENWEIGHTS_SCREENSHOTS"

        /** Long enough for a markdown parse on a cold host JVM, short enough to fail fast. */
        const val WAIT_MS = 10_000L
    }
}
