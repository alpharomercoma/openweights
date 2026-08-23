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

package io.github.alpharomercoma.openweights.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import io.github.alpharomercoma.openweights.R

/**
 * Keeps the process running while the model is answering.
 *
 * Without this, leaving the app mid-reply stops the reply. That is not a guess: measured on
 * an Android 14 phone, backgrounding the app takes `oom_score_adj` from 0 to 700 and the
 * process accumulates **zero** CPU ticks over the next fifteen seconds. Android freezes
 * cached processes, so a turn in flight does not slow down, it stops, and comes back only
 * when the user does.
 *
 * That is survivable for a chat app where every reply is watched. It is fatal for anything
 * that runs on its own, which is what a goal is, so this exists before the goal does.
 *
 * ### Why `specialUse`
 *
 * None of the other types describe this. `dataSync` is for moving data and is what the
 * downloader legitimately uses; borrowing it for arithmetic would be a misdeclaration, and
 * on Android 15 it is capped at six hours a day besides. `shortService` stops at three
 * minutes, which is shorter than one long answer on a phone. `mediaPlayback`, `location`
 * and the rest are unrelated.
 *
 * `specialUse` is the type for exactly this situation and it carries a cost: Play asks what
 * the special use is, in writing, at review. The answer is in `docs/play-store.md` and it is
 * the honest one, that the app runs a language model on the device's own processor and a
 * reply the user asked for has to finish.
 *
 * ### What it does not do
 *
 * It holds no work. Generation stays where it was, in the view model's scope; this only
 * raises the process's priority and puts a notification up so the user can see and stop it.
 * A service that owned the turn would have to own the engine, the transcript and the
 * database with it, which is the whole app moved into a Service for a lifecycle bump.
 */
class GenerationService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val label = intent?.getStringExtra(EXTRA_LABEL) ?: "Working"
        ensureChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(label),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
        // Not sticky. If the system kills the process the turn died with it, and restarting
        // an empty service would put a notification on screen for work nobody is doing.
        return START_NOT_STICKY
    }

    private fun notification(label: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(label)
        .setContentText("Running on this device")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(openApp())
        .build()

    private fun openApp() = packageManager.getLaunchIntentForPackage(packageName)?.let {
        android.app.PendingIntent.getActivity(
            this,
            0,
            it,
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel() {
        val manager = getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Working", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the model is answering, so it can keep going " +
                    "when you leave the app"
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val CHANNEL = "generation"
        private const val NOTIFICATION_ID = 4201
        private const val EXTRA_LABEL = "label"

        /** A turn holds this for as long as it is generating. */
        const val TURN = "turn"

        /** A goal holds this from its first step to its last, across the gaps between. */
        const val GOAL = "goal"

        /**
         * Who currently needs the process raised.
         *
         * Counted rather than a bare start and stop, and the reason is a bug this shipped
         * with. A turn raised the process in `generate` and dropped it in `releaseTurn`,
         * which is right for one question and wrong for a goal: a goal is many turns, so
         * the service stopped at the end of every step and started again at the beginning
         * of the next. In the gap between them the process has no foreground service, and
         * the gap is exactly where a goal does its own work, deciding the next step and
         * folding the context. Backgrounded, Android is free to freeze it there, and a goal
         * frozen between steps never reaches the code that would start the service again.
         *
         * So the goal takes its own hold for its whole life and the per-turn hold nests
         * inside it. The service stops when the last holder lets go.
         */
        private val holders = mutableSetOf<String>()

        /**
         * Raises the process on [holder]'s behalf while [label] is happening.
         *
         * Failures are swallowed on purpose. Starting a foreground service can be refused,
         * most often because the app is already in the background when the turn begins,
         * and the right response is to generate anyway: the reply is what the user asked
         * for and a crash is worse than a turn that might be frozen.
         */
        fun hold(context: Context, holder: String, label: String) {
            synchronized(holders) { holders += holder }
            runCatching {
                context.startForegroundService(
                    Intent(context, GenerationService::class.java)
                        .putExtra(EXTRA_LABEL, label),
                )
            }
        }

        /** Lets go on [holder]'s behalf, stopping the service only once nobody needs it. */
        fun release(context: Context, holder: String) {
            val idle = synchronized(holders) {
                holders -= holder
                holders.isEmpty()
            }
            if (!idle) return
            runCatching { context.stopService(Intent(context, GenerationService::class.java)) }
        }

        /** Whether anything is currently holding the process up. For tests and for logs. */
        fun isHeld(): Boolean = synchronized(holders) { holders.isNotEmpty() }
    }
}
