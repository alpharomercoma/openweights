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

package io.github.alpharomercoma.openweights.download

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * What the app says when a download ends while nobody is looking at it.
 *
 * The progress notification is a foreground-service notification and disappears the moment
 * the worker finishes, so before this there was nothing: somebody who started an eight
 * gigabyte download and switched apps got silence, and silence meant "still going",
 * "finished" and "gave up" all at once.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadNotifierTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService<NotificationManager>()!!
    private val notifier = DownloadNotifier(context)

    @Test
    fun `a finished download says so, under the model's own name`() {
        notifier.announce(ID, "Qwen3-1.7B-Q4_0", DownloadNotifier.Outcome.FINISHED)

        val posted = shadowOf(manager).allNotifications.single()
        assertThat(posted.title()).isEqualTo("Qwen3-1.7B-Q4_0")
        assertThat(posted.text()).isEqualTo("Ready to use")
    }

    @Test
    fun `a download that gave up says that too`() {
        // Not an extra on top of "tell me when it is done". Announcing only success puts
        // the ambiguity back: nothing arriving would still mean either still working or
        // stopped twenty minutes ago.
        notifier.announce(ID, "Qwen3-1.7B-Q4_0", DownloadNotifier.Outcome.FAILED)

        assertThat(shadowOf(manager).allNotifications.single().text())
            .isEqualTo("Download failed. Open the app to try again.")
    }

    @Test
    fun `the channel it lands on can make a sound`() {
        // The whole point of a second channel. The progress channel is IMPORTANCE_LOW so a
        // hundred updates per download do not chime, and a result posted at that importance
        // is silent and has no heads-up, which is precisely the announcement somebody in
        // another app would never see.
        notifier.announce(ID, "Qwen3-1.7B-Q4_0", DownloadNotifier.Outcome.FINISHED)

        val channel = manager.getNotificationChannel(DownloadNotifier.CHANNEL)
        assertThat(channel).isNotNull()
        assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_DEFAULT)
    }

    @Test
    fun `it is not the channel the progress bar reports on`() {
        // Two channels so that silencing the chatter and silencing the answer stay two
        // separate decisions. One channel would make turning off the progress spam also
        // turn off the one notification worth having.
        assertThat(DownloadNotifier.CHANNEL).isNotEqualTo("downloads")
    }

    @Test
    fun `tapping it opens the app and takes the notification away`() {
        notifier.announce(ID, "Qwen3-1.7B-Q4_0", DownloadNotifier.Outcome.FINISHED)

        val posted = shadowOf(manager).allNotifications.single()
        assertThat(posted.contentIntent).isNotNull()
        assertThat(posted.flags and Notification.FLAG_AUTO_CANCEL)
            .isEqualTo(Notification.FLAG_AUTO_CANCEL)
    }

    @Test
    fun `two downloads finishing do not overwrite each other`() {
        // A multimodal model arrives as two files, so two workers finish moments apart.
        notifier.announce(ID, "Qwen3-VL", DownloadNotifier.Outcome.FINISHED)
        notifier.announce(ID + 1, "mmproj", DownloadNotifier.Outcome.FINISHED)

        assertThat(shadowOf(manager).allNotifications).hasSize(2)
    }

    private fun Notification.title(): String? =
        extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private fun Notification.text(): String? =
        extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private companion object {
        const val ID = 4_242
    }
}
