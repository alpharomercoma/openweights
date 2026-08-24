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
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.alpharomercoma.openweights.core.common.context.Watch
import io.github.alpharomercoma.openweights.core.common.context.WatchOutcome
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.MessagePart
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import io.github.alpharomercoma.openweights.core.data.WatchRepository
import io.github.alpharomercoma.openweights.core.device.ThermalLevel
import io.github.alpharomercoma.openweights.core.engine.GenerationEvent
import io.github.alpharomercoma.openweights.core.tools.AgentMode
import io.github.alpharomercoma.openweights.core.tools.AgentStep
import io.github.alpharomercoma.openweights.core.tools.ToolNotes
import io.github.alpharomercoma.openweights.ui.chat.ModelRuntime
import io.github.alpharomercoma.openweights.ui.chat.TurnListener
import io.github.alpharomercoma.openweights.ui.chat.TurnRunner
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One tick of one watch: decide whether it may run, run it, and write down what happened.
 *
 * The order of the checks is the design. Everything that can refuse cheaply refuses before
 * the engine is touched, because the expensive thing here is a language model turn and a
 * watch that fires every minute would otherwise spend the battery discovering it should not
 * have.
 *
 * Nothing here waits. Every refusal is recorded as [WatchOutcome.SKIPPED] and the watch stays
 * healthy: a tick whose moment has passed is not a failure, and treating it as one would stop
 * a perfectly good watch after three busy minutes.
 */
@Singleton
class WatchRunner @Inject constructor(
    private val watches: WatchRepository,
    private val runtime: ModelRuntime,
    private val turns: TurnRunner,
    @param:ApplicationContext private val appContext: Context,
) {
    /**
     * Runs the watch with this id, if it should run at all.
     *
     * @return what happened, or null when the watch no longer exists, which is the ordinary
     *   result of a scheduled job outliving the thing that scheduled it.
     */
    suspend fun tick(watchId: Long, now: Long = System.currentTimeMillis()): WatchOutcome? {
        val watch = watches.byId(watchId) ?: return null
        if (!watch.isActive) return null

        refusal()?.let { why ->
            watches.record(watchId, now, WatchOutcome.SKIPPED, why)
            return WatchOutcome.SKIPPED
        }

        val (outcome, summary) = check(watch, watchId)
        // The recorded state is read back rather than discarded, because recording the third
        // failure is what stops the watch, and the caller has to know at once. Thrown away,
        // the ticker slept one more full period before noticing, holding the foreground
        // notification up for a watch that had already given up.
        val after = watches.record(watchId, now, outcome, summary)
        return outcome.takeIf { after == null || after.isActive }
    }

    /**
     * Runs the check and says how it went, without touching the record.
     *
     * Split from [tick] so that every path writes exactly one row through one call. When the
     * recording was scattered through the branches it was one early return away from a tick
     * that ran and left no trace, which for an unattended feature is the same as not running.
     */
    private suspend fun check(watch: Watch, watchId: Long): Pair<WatchOutcome, String> {
        // Deliberately not loading a model. A scheduled tick can arrive in a process that
        // started for this alone, and opening a couple of gigabytes of weights in the
        // background to answer a question nobody is waiting for is the kind of thing that
        // gets an app uninstalled. The next tick with the app open will run.
        val model = runtime.loadedModel
            ?: return WatchOutcome.SKIPPED to
                "No model was loaded, so this check waited for the next one."

        val settings = runtime.settingsFor(model.description)
        val answer = runCatching {
            turns.tryRun(
                conversation = prompt(watch),
                params = settings.toSamplerParams(),
                mode = AgentMode.AUTO,
                withTools = true,
                notes = ToolNotes(),
                listener = Unwatched,
            )
        }

        answer.exceptionOrNull()?.let { failure ->
            // Passed on rather than counted. `runCatching` catches a cancellation like
            // anything else, and a cancelled tick is the watch being paused or the worker
            // being taken back, neither of which is the check failing. Counting it would
            // spend the three-failure guardrail on the one thing it was never meant to
            // catch, and stop a watch that works.
            if (failure is CancellationException) throw failure
            Log.w("OpenWeights", "watch $watchId failed", failure)
            return WatchOutcome.FAILED to (failure.message ?: "The check did not finish.")
        }

        // tryRun declined: something else owns the engine. See TurnRunner.tryRun.
        val text = answer.getOrNull()
            ?: return WatchOutcome.SKIPPED to "The model was busy with something else."

        return WatchOutcome.CHECKED to text.trim().ifBlank { "Nothing new." }
    }

    /**
     * Why this tick should not run, or null to go ahead.
     *
     * The same two conditions a goal checks between steps, for the same reason and with more
     * force: a goal is something the user started and is waiting on, and a watch may have
     * been running for a week.
     */
    private fun refusal(): String? {
        if (runtime.thermalLevel() == ThermalLevel.CRITICAL) {
            return "Skipped: the phone was too hot to run a check."
        }
        val battery = appContext.getSystemService<BatteryManager>()
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (battery != null && battery in 1..<MIN_BATTERY_PERCENT) {
            return "Skipped at $battery% battery."
        }
        return null
    }

    /**
     * The turn a tick sends.
     *
     * Standalone rather than appended to a conversation, and that is the point of a watch
     * rather than a limitation of one. A check that carried its own history would drift:
     * every tick would see the last twenty answers and start comparing itself to them
     * instead of to the world, and the context would grow without end. Each tick asks the
     * same question of the same tools, and the record beside it is what carries the history.
     */
    private fun prompt(watch: Watch) = listOf(
        ChatMessage(
            role = ChatRole.SYSTEM,
            parts = listOf(
                MessagePart.Text(
                    "You are running a scheduled check on the user's phone. Nobody is " +
                        "watching, so do the check with the tools you have and answer in " +
                        "one or two sentences. Say what you found. If nothing has changed, " +
                        "say so plainly.",
                ),
            ),
        ),
        ChatMessage(role = ChatRole.USER, parts = listOf(MessagePart.Text(watch.task))),
    )

    private companion object {
        /** The same floor a goal uses. Working unattended is what flattens a phone. */
        const val MIN_BATTERY_PERCENT = 15
    }
}

/**
 * A listener for a turn nobody is looking at.
 *
 * Every callback is a screen update, and a watch has no screen: the answer is written to the
 * watch's record when the turn returns. Streaming into nothing would still cost the string
 * building it does on the way.
 */
private object Unwatched : TurnListener {
    override fun onText(raw: String) = Unit
    override fun onPass(event: GenerationEvent.Completed, raw: String) = Unit
    override fun onSteps(steps: List<AgentStep>) = Unit
    override fun onIntermediate(text: String) = Unit
    override fun onNextPass() = Unit

    /**
     * Never approves anything.
     *
     * Only reached in [AgentMode.ASK], which a watch never runs in, and false is the right
     * answer if it somehow is: a tool that needs a person cannot be approved by one who is
     * not there, and the alternative is a background turn granting itself permissions.
     */
    override suspend fun onApproval(call: ToolCall): Boolean = false
}
