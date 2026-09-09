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

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The monitor's root cause, measured on the phone: a coroutine `delay` against the alarm
 * wait, both set for the same two minutes, with the screen turned off from the host while
 * they run. Reads back on tag OpenWeightsSleep: how late each one returned against the wall
 * clock, and how long the phone was in deep sleep meanwhile, which is the gap between
 * `elapsedRealtime` (counts through sleep) and `uptimeMillis` (stops in sleep).
 *
 * The alarm wait is built by hand; the receiver's wake-up registry is process-wide, so it
 * reaches this instance as it would the app's own.
 */
@RunWith(AndroidJUnit4::class)
class WatchSleepProbe {
    @Test
    fun delayVersusAlarmWithTheScreenOff(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val wait: TickWait = AlarmTickWait(context)
        val wall0 = System.currentTimeMillis()
        val up0 = SystemClock.uptimeMillis()
        val real0 = SystemClock.elapsedRealtime()
        val dueAt = wall0 + WAIT_MS
        fun stamp(which: String) {
            val late = System.currentTimeMillis() - dueAt
            val asleep =
                (SystemClock.elapsedRealtime() - real0) - (SystemClock.uptimeMillis() - up0)
            Log.i(TAG, "RESULT $which late=${late}ms asleep=${asleep}ms")
        }
        Log.i(TAG, "START wait=${WAIT_MS}ms via=${wait.javaClass.simpleName}")
        val byDelay = async(Dispatchers.Default) {
            delay(WAIT_MS)
            stamp("DELAY")
        }
        val byAlarm = async(Dispatchers.Default) {
            wait.awake(dueAt, WAIT_MS) { stamp("ALARM") }
        }
        awaitAll(byDelay, byAlarm)
        val asleep = (SystemClock.elapsedRealtime() - real0) - (SystemClock.uptimeMillis() - up0)
        Log.i(TAG, "END asleep=${asleep}ms of ${System.currentTimeMillis() - wall0}ms")
        Unit
    }

    private companion object {
        const val TAG = "OpenWeightsSleep"
        const val WAIT_MS = 120_000L
    }
}
