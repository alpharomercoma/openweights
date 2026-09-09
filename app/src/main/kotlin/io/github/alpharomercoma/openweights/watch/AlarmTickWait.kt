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

package io.github.alpharomercoma.openweights.watch

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * The [TickWait] the app runs on: one wake-up alarm per tick, and a wake lock through the check.
 *
 * Exact where the system lets it be. From Android 12 an exact alarm needs a permission the
 * person can withdraw, and from Android 14 it starts withdrawn for an app that is not an
 * alarm clock, so the inexact "while idle" alarm is the ordinary case. Doze batches those
 * into its maintenance windows, which run further apart the longer the phone is left alone,
 * so a five-minute watch on a phone in a drawer ticks every ten or fifteen minutes instead.
 * That is the platform's policy and it is honest to follow it: the tick is stamped with the
 * time it actually ran, the next deadline is set from there, and the countdown shows it.
 *
 * The wake lock is what makes the alarm worth setting. The receiver's own hold ends when
 * `onReceive` returns, and the check runs afterwards on a coroutine, so without a lock of its
 * own the phone could go back to sleep between the first token and the last.
 */
@Singleton
class AlarmTickWait @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
) : TickWait {
    /** Whoever is waiting, by the alarm's request code, so the receiver can wake them. */
    private val waiting = mutableMapOf<Int, CancellableContinuation<Unit>>()
    private val codes = AtomicInteger()

    override suspend fun <T> awake(dueAt: Long, periodMs: Long, block: suspend () -> T): T {
        val remaining = dueAt - System.currentTimeMillis()
        if (remaining > 0) {
            // Bounded by the interval itself rather than trusted: an alarm the system dropped,
            // or a Robolectric one that nothing delivers, would otherwise hold the ticker for
            // the life of the process. Past the bound the tick runs anyway, exactly as the
            // delay this replaced would have.
            withTimeoutOrNull(remaining + periodMs + GRACE_MS) { sleepUntil(dueAt) }
        }
        val lock = appContext.getSystemService<PowerManager>()
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        lock?.acquire(CHECK_WAKE_LOCK_MS)
        return try {
            block()
        } finally {
            if (lock?.isHeld == true) lock.release()
        }
    }

    private suspend fun sleepUntil(dueAt: Long) = suspendCancellableCoroutine { cont ->
        val alarms = appContext.getSystemService<AlarmManager>()
        if (alarms == null) {
            cont.resume(Unit)
            return@suspendCancellableCoroutine
        }
        val code = codes.incrementAndGet()
        val pending = PendingIntent.getBroadcast(
            appContext,
            code,
            Intent(appContext, WatchAlarmReceiver::class.java)
                .setAction(ACTION_TICK)
                .putExtra(EXTRA_CODE, code),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        synchronized(waiting) { waiting[code] = cont }
        cont.invokeOnCancellation {
            synchronized(waiting) { waiting.remove(code) }
            alarms.cancel(pending)
        }
        runCatching {
            if (canBeExact(alarms)) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueAt, pending)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueAt, pending)
            }
        }.onFailure { failure ->
            // A refused alarm is not a reason to stop the watch: the bound in [awake] still
            // ends the wait, one period late at worst, on the clock that pauses in sleep.
            Log.w("OpenWeights", "watch alarm refused", failure)
        }
    }

    /** Wakes the waiter an alarm was set for. Called by [WatchAlarmReceiver]. */
    fun fire(code: Int) {
        val cont = synchronized(waiting) { waiting.remove(code) } ?: return
        if (cont.isActive) cont.resume(Unit)
    }

    private fun canBeExact(alarms: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    companion object {
        const val ACTION_TICK = "io.github.alpharomercoma.openweights.watch.TICK"
        const val EXTRA_CODE = "code"
        private const val WAKE_LOCK_TAG = "OpenWeights:watch"

        /** How far past the bound an undelivered alarm is given before the tick runs anyway. */
        private const val GRACE_MS = 30_000L

        /**
         * Long enough for a check on a small model with a tool call or two, short enough
         * that a lock leaked by a crash mid-check is not a night of battery.
         */
        private const val CHECK_WAKE_LOCK_MS = 5 * 60_000L
    }
}
