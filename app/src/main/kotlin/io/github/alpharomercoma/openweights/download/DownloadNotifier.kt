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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import io.github.alpharomercoma.openweights.R

/**
 * Says how a download ended, once there is no progress bar left to say it.
 *
 * A model is one to eight gigabytes, which is minutes on a phone connection, and that is
 * the whole reason the download is a worker rather than something a screen owns: people
 * start one and go elsewhere. Until now the app had nothing to say when they did. The
 * progress notification is a foreground-service notification, so it disappears at the exact
 * moment the work ends, and leaving was answered with silence. Silence meant three
 * different things at once: still going, finished, and gave up twenty minutes ago.
 *
 * Failure is announced for that reason and not as an extra. A notifier that reports only
 * success puts the ambiguity straight back, because then nothing arriving still means
 * either "working" or "stopped". The two outcomes together are what make the absence of a
 * notification mean one thing.
 *
 * Separate from the worker so it can be tested against a real notification manager without
 * standing up a downloader, a Hub client and a token source to reach the two lines that
 * matter.
 */
internal class DownloadNotifier(private val context: Context) {

    /**
     * Posts the result of one download under [notificationId].
     *
     * That id must not be the progress notification's. WorkManager takes the foreground
     * notification down as the worker ends, and the two happen together: posting the result
     * under the same id races its removal, and a download that quietly announced nothing is
     * the one failure this feature cannot have.
     */
    fun announce(notificationId: Int, label: String, outcome: Outcome) {
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(label)
            .setContentText(outcome.text)
            // Tapping it is going to the app to use the thing that just arrived, so the
            // notification has done its job and should not need dismissing as well.
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .apply { openTheApp(notificationId)?.let(::setContentIntent) }
            .build()

        // Dropped rather than thrown when notifications are refused, which on Android 13
        // and later they may be. A download that finished is not a download that failed,
        // and there is nothing owed to somebody who said not to tell them.
        runCatching {
            context.getSystemService<NotificationManager>()?.notify(notificationId, notification)
        }
    }

    /**
     * An intent back into the app, or null on a device that somehow cannot launch it.
     *
     * The launcher intent rather than a deep link to the models screen. A link would be
     * nicer and would mean threading a route through navigation for one notification; what
     * this opens is an app in which the model is now listed, installed, and already warming.
     */
    private fun openTheApp(requestCode: Int): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
            PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

    /**
     * The finished channel, which is deliberately not the one the progress bar is on.
     *
     * That one is `IMPORTANCE_LOW` because it reports about a hundred times per download,
     * and a channel that chimes for each is a channel the user switches off, taking the
     * progress bar with it. The same reasoning is why the result cannot share it: at low
     * importance it makes no sound and no heads-up, which is exactly the announcement
     * somebody in another app would never see. Two channels, so silencing the chatter and
     * silencing the answer stay two separate decisions the user gets to make.
     */
    private fun ensureChannel() {
        val manager = context.getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "Finished downloads",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "When a model has finished downloading, or could not be"
            },
        )
    }

    /** How a download ended, as the one line that says so. */
    enum class Outcome(val text: String) {
        FINISHED("Ready to use"),
        FAILED("Download failed. Open the app to try again."),
    }

    companion object {
        /** Separate from the progress channel on purpose. See [ensureChannel]. */
        const val CHANNEL = "downloads-finished"
    }
}
