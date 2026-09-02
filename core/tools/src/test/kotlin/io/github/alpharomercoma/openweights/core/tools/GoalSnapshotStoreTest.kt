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

package io.github.alpharomercoma.openweights.core.tools

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.context.GoalState
import io.github.alpharomercoma.openweights.core.common.context.TaskPlan
import io.github.alpharomercoma.openweights.core.common.context.TaskStep
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class GoalSnapshotStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val preferences =
        context.getSharedPreferences(GoalSnapshotStore.PREFERENCES, Context.MODE_PRIVATE)
    private val store = GoalSnapshotStore(context)

    @Before
    fun clearBefore() = store.clear()

    @After
    fun clearAfter() = store.clear()

    @Test
    fun `a board comes back after its process is recreated`() {
        val first = GoalBoard(store)
        first.start("Summarise José's notes\nwithout losing 日本語")
        first.planned(
            TaskPlan(
                listOf(
                    TaskStep("Read José's notes", done = true),
                    TaskStep("Write 日本語 summary"),
                ),
            ),
        )
        first.advanced(first.goal.value!!.plan!!)
        first.steer("Keep the line break\nhere")

        val restored = GoalBoard(GoalSnapshotStore(context))

        assertThat(restored.goal.value?.task)
            .isEqualTo("Summarise José's notes\nwithout losing 日本語")
        assertThat(restored.goal.value?.plan?.steps)
            .containsExactly(
                TaskStep("Read José's notes", done = true),
                TaskStep("Write 日本語 summary"),
            )
            .inOrder()
        assertThat(restored.goal.value?.stepsTaken).isEqualTo(1)
        assertThat(restored.steering.value).containsExactly("Keep the line break\nhere")
    }

    @Test
    fun `an interrupted active goal is restored halted and stays halted`() {
        val first = GoalBoard(store)
        first.start("Do the work")
        first.planned(TaskPlan(listOf(TaskStep("First"), TaskStep("Second"))))

        val restored = GoalBoard(GoalSnapshotStore(context))

        assertThat(restored.goal.value?.state).isEqualTo(GoalState.HALTED)
        assertThat(restored.goal.value?.note).contains("Interrupted")
        assertThat(restored.isRunning).isFalse()
        // Construction writes the safe state back; another recreation cannot revive it.
        assertThat(GoalBoard(GoalSnapshotStore(context)).goal.value?.state)
            .isEqualTo(GoalState.HALTED)
    }

    @Test
    fun `an interruption while planning is also restored halted`() {
        GoalBoard(store).start("Plan the work")

        val restored = GoalBoard(GoalSnapshotStore(context))

        assertThat(restored.goal.value?.state).isEqualTo(GoalState.HALTED)
        assertThat(restored.goal.value?.plan).isNull()
        assertThat(restored.isRunning).isFalse()
    }

    @Test
    fun `terminal state is restored without being rewritten as an interruption`() {
        val first = GoalBoard(store)
        first.start("Do the work")
        first.stop()

        val restored = GoalBoard(GoalSnapshotStore(context))

        assertThat(restored.goal.value?.state).isEqualTo(GoalState.STOPPED)
        assertThat(restored.goal.value?.note).isNull()
    }

    @Test
    fun `clear removes both live and durable state`() {
        val first = GoalBoard(store)
        first.start("Do the work")
        first.steer("Use the short version")

        first.clear()

        val restored = GoalBoard(GoalSnapshotStore(context))
        assertThat(restored.goal.value).isNull()
        assertThat(restored.steering.value).isEmpty()
    }

    @Test
    fun `consuming steering is durable`() {
        val first = GoalBoard(store)
        first.start("Do the work")
        first.steer("Use the short version")

        assertThat(first.takeSteering()).containsExactly("Use the short version")

        val restored = GoalBoard(GoalSnapshotStore(context))
        assertThat(restored.steering.value).isEmpty()
    }

    @Test
    fun `steering is bounded without destroying goal recovery`() {
        val first = GoalBoard(store)
        first.start("Do the work")
        repeat(20) { first.steer("x".repeat(1_000)) }

        assertThat(first.steering.value).hasSize(16)
        assertThat(first.steering.value).doesNotContain("x".repeat(1_000))

        val restored = GoalBoard(GoalSnapshotStore(context))
        assertThat(restored.goal.value?.task).isEqualTo("Do the work")
        assertThat(restored.steering.value).hasSize(16)
    }

    @Test
    fun `a snapshot reaches the disk behind the call, not only the cache`() {
        // Every read in this process is answered from the cache, so the tests above would
        // pass against a store that never wrote anything down. The file is what a restart
        // reads, and the write is queued rather than waited for, so it is given a moment.
        val file = File(context.dataDir, "shared_prefs/${GoalSnapshotStore.PREFERENCES}.xml")
        GoalBoard(store).start("Survive the restart")

        settleUntil { onDisk(file).contains("Survive the restart") }
        assertThat(onDisk(file)).contains("Survive the restart")

        store.clear()

        settleUntil { !onDisk(file).contains(GoalSnapshotStore.KEY) }
        assertThat(onDisk(file)).doesNotContain(GoalSnapshotStore.KEY)
    }

    private fun onDisk(file: File): String = if (file.exists()) file.readText() else ""

    /** Waits, up to a few seconds, for a queued write to land; the assertion follows. */
    private fun settleUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
    }

    @Test
    fun `corrupt and unknown snapshots are discarded without crashing`() {
        listOf(
            "not json",
            """{"version":99,"task":"old"}""",
            """{"version":1,"task":"bad","stepsTaken":0,"state":"RUNAWAY"}""",
            """{"version":1,"task":7,"stepsTaken":0,"state":"STOPPED"}""",
            "x".repeat(32_769),
        ).forEach { corrupt ->
            preferences.edit().putString(GoalSnapshotStore.KEY, corrupt).commit()

            assertThat(GoalSnapshotStore(context).load()).isNull()
            assertThat(preferences.contains(GoalSnapshotStore.KEY)).isFalse()
        }
    }

    @Test
    fun `a value of the wrong preference type is discarded without crashing`() {
        preferences.edit().putInt(GoalSnapshotStore.KEY, 7).commit()

        assertThat(GoalSnapshotStore(context).load()).isNull()
        assertThat(preferences.contains(GoalSnapshotStore.KEY)).isFalse()
    }
}
