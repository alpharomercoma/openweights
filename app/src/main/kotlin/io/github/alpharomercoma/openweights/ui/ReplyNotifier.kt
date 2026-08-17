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

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.alpharomercoma.openweights.MainActivity
import io.github.alpharomercoma.openweights.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells the user their answer is ready when they are not looking at it.
 *
 * On a phone a reply takes tens of seconds and sometimes minutes, which is long enough to
 * put the phone down. A hosted assistant answers fast enough that a notification is a
 * courtesy; here it is the difference between a wait and an unread answer.
 *
 * Only when the app is in the background. Posting a notification about text the user is
 * watching appear is noise, and noise is how an app loses the permission it needs for the
 * one notification that mattered.
 */
@Singleton
class ReplyNotifier @Inject constructor(@param:ApplicationContext private val context: Context) {
    /**
     * True when the app is not the thing on screen.
     *
     * ActivityManager rather than a lifecycle observer, because this is a question about
     * the process and the answer is needed once, at the end of a turn, from a place that
     * has no lifecycle of its own.
     */
    private val isInBackground: Boolean
        get() {
            val manager = context.getSystemService<ActivityManager>() ?: return false
            val mine = manager.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }
            return mine == null ||
                mine.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }

    fun notifyReply(preview: String) {
        // Nothing while the user is watching the words appear. A notification about text
        // already on screen is noise, and noise is how an app loses the permission it
        // needed for the one notification that mattered.
        if (!isInBackground || preview.isBlank()) return
        // Read here rather than from a property, for two reasons that point the same way.
        // The permission can be withdrawn between one reply and the next, so the answer is
        // only good at the moment it is used. And lint only accepts a guard it can see on
        // the path to the call: behind a property it reported an unchecked permission,
        // which for a call that throws SecurityException is a fair complaint.
        // The version test comes first because before Tiramisu there is no such permission
        // to hold: asking about one the platform does not define answers "denied", which
        // would silence every notification on the oldest phones this app supports.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel()

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val text = preview.trim().take(PREVIEW_CHARS)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Your answer is ready")
            // The answer itself, not "tap to read": the point of a notification about a
            // reply is that short replies are read without opening anything.
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        // Guarded because the permission can be revoked between the check and the post.
        runCatching { NotificationManagerCompat.from(context).notify(REPLY_ID, notification) }
    }

    private fun ensureChannel() {
        val manager = context.getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Replies", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a reply has finished being written"
            },
        )
    }

    private companion object {
        const val CHANNEL = "replies"
        const val REPLY_ID = 1

        /** Enough of the answer to be worth reading without opening the app. */
        const val PREVIEW_CHARS = 400
    }
}
