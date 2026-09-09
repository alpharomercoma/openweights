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
import android.content.Context
import androidx.core.content.getSystemService
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The ticker's sleep is an alarm, not a delay: see [TickWait] for the deep-sleep reason.
 * Robolectric records the alarm and delivers nothing, which is exactly the shape of the
 * dropped-alarm case the bound in [AlarmTickWait.awake] exists for.
 */
@RunWith(RobolectricTestRunner::class)
class AlarmTickWaitTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alarms = shadowOf(context.getSystemService<AlarmManager>()!!)
    private val wait = AlarmTickWait(context)

    @Test
    fun `a wake-up alarm is set for the deadline and the tick runs when it fires`() = runBlocking {
        val dueAt = System.currentTimeMillis() + FAR_MS
        val tick = async(Dispatchers.Default) {
            wait.awake(dueAt, FAR_MS) { "ticked" }
        }
        withContext(Dispatchers.Default) {
            while (alarms.scheduledAlarms.isEmpty()) yield()
        }
        val alarm = alarms.scheduledAlarms.single()
        assertThat(alarm.type).isEqualTo(AlarmManager.RTC_WAKEUP)
        assertThat(alarm.triggerAtMs).isEqualTo(dueAt)
        assertThat(tick.isCompleted).isFalse()

        // What the receiver does when the alarm lands.
        val code = shadowOf(alarm.operation).savedIntent.getIntExtra(AlarmTickWait.EXTRA_CODE, -1)
        wait.fire(code)

        assertThat(tick.await()).isEqualTo("ticked")
    }

    @Test
    fun `a deadline already past runs the tick at once without an alarm`() = runBlocking {
        val ran = wait.awake(System.currentTimeMillis() - 1, FAR_MS) { true }
        assertThat(ran).isTrue()
        assertThat(alarms.scheduledAlarms).isEmpty()
    }

    @Test
    fun `cancelling the wait cancels the alarm`() = runBlocking {
        val tick = async(Dispatchers.Default) {
            wait.awake(System.currentTimeMillis() + FAR_MS, FAR_MS) { Unit }
        }
        withContext(Dispatchers.Default) {
            while (alarms.scheduledAlarms.isEmpty()) yield()
        }
        tick.cancel()
        withContext(Dispatchers.Default) {
            while (alarms.scheduledAlarms.isNotEmpty()) yield()
        }
        assertThat(alarms.scheduledAlarms).isEmpty()
    }

    private companion object {
        /** Well beyond any test's patience, so only a fired alarm can end the wait. */
        const val FAR_MS = 60L * 60_000L
    }
}
