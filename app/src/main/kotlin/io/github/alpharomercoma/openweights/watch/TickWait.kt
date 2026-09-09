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

/**
 * How a fast watch's ticker waits for its next deadline, and stays awake through the check.
 *
 * Its own seam because the obvious wait is the wrong one. A coroutine `delay` counts on the
 * monotonic clock, and on Android that clock stops while the device is in deep sleep: a
 * phone in a pocket with the screen off spends most of a five-minute period asleep, so the
 * delay fires only once the CPU has been awake for five minutes in total, which can be an
 * hour of wall time. The notification's countdown is drawn from the wall clock, so it
 * reached zero on time and kept going, with a minus sign, for as long as the phone slept.
 * That was "the monitor is broken": the ticker was not late, it was not running.
 *
 * The production wait sets an alarm, which is the one timer the platform promises to honour
 * through sleep, and holds the CPU while [block] runs so the check it woke up for finishes.
 * A test hands in a plain `delay`, so the ticker's tests keep running on virtual time.
 */
interface TickWait {
    /**
     * Waits until the wall clock reaches [dueAt], then runs [block] with the device awake.
     *
     * [periodMs] is the interval the deadline was set from. It bounds the wait: a deadline
     * that never arrives, because the alarm was dropped, still ends after one period plus
     * some grace, which is no worse than the delay this replaced.
     */
    suspend fun <T> awake(dueAt: Long, periodMs: Long, block: suspend () -> T): T
}
