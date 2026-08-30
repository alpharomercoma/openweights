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

import android.Manifest.permission.POST_NOTIFICATIONS
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.BatteryManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.alpharomercoma.openweights.R
import io.github.alpharomercoma.openweights.core.common.context.Watch
import io.github.alpharomercoma.openweights.core.common.context.WatchOutcome
import io.github.alpharomercoma.openweights.core.common.context.WatchState
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
        // Before anything is spent on it: a watch that is over — stopped by hand, broken,
        // or past the window it was given while the app was shut — ends here rather than
        // running one more check nobody asked for. The budget half of the same bound is
        // enforced where the checks are counted, in the record.
        if (!watch.isActive || expire(watch, now)) return null

        refusal()?.let { why ->
            // The recorded state is read back here for the same reason the checked path
            // reads it: recording is what can end a watch, and a caller told "skipped" for
            // a watch that has since stopped sleeps another full period before noticing.
            val after = watches.record(watchId, now, WatchOutcome.SKIPPED, why)
            return WatchOutcome.SKIPPED.takeIf { after == null || after.isActive }
        }

        val (outcome, summary) = check(watch, watchId)
        // The recorded state is read back rather than discarded, because recording the third
        // failure is what stops the watch, and the caller has to know at once. Thrown away,
        // the ticker slept one more full period before noticing, holding the foreground
        // notification up for a watch that had already given up.
        val after = watches.record(watchId, now, outcome, summary)
        // Only a check that actually ran is worth a person's attention. Skipped ticks are
        // routine — busy engine, low battery, the ordinary cost of running unattended — and
        // alerting on every one of those would be the thing that gets this feature muted.
        if (outcome == WatchOutcome.CHECKED) alert(watch, summary, after ?: watch)
        // Said once, when the last check of the budget has just run: a watch that stops
        // itself and says nothing is indistinguishable from one that broke.
        if (after != null && after.state == WatchState.EXPIRED) announceEnd(after)
        return outcome.takeIf { after == null || after.isActive }
    }

    /**
     * The one notification a watch posts that is not the ongoing "still running" one.
     *
     * Distinct channel from [GenerationService]'s: that one is deliberately silent, since it
     * is up for the entire life of a fast watch and a sound on every tick would be the
     * opposite of unattended. This one fires once, for the result a person actually asked to
     * be told about, and is the one place a sound belongs.
     */
    private fun alert(watch: Watch, summary: String, after: Watch) {
        // Android 13+ requires this granted at runtime; the manifest entry alone is not
        // enough. Checked explicitly rather than left to runCatching, so a missing grant is
        // a line in logcat and not a silently vanished result.
        val granted = ContextCompat.checkSelfPermission(appContext, POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.w("OpenWeights", "watch ${watch.id} found something but notifications are off")
            return
        }
        val manager = appContext.getSystemService<NotificationManager>() ?: return
        ensureAlertChannel(manager)
        val notification = NotificationCompat.Builder(appContext, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(watch.task.take(MAX_TITLE_CHARS))
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            // Which check this was, and what is left of the watch. Without it the same
            // notification arrives every few minutes with no way to tell one from the next,
            // and no way to know whether this thing is going to stop on its own.
            .setSubText(progress(after))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        // Namespaced by a string rather than the id itself: watch.id.toInt() truncates a
        // Long, which could collide two watches whose ids differ by 2^32, and a bare small
        // int risks colliding with a notification id some other feature happens to use.
        // hashCode() of a string only this feature constructs avoids both.
        runCatching { manager.notify("watch-${watch.id}".hashCode(), notification) }
    }

    /**
     * Ends a watch whose window has closed, and says whether it was ended.
     *
     * Split out of [tick] to keep one exit per reason there, and because "is this over" and
     * "run this" are separate questions that happen to be asked in a row.
     */
    private suspend fun expire(watch: Watch, now: Long): Boolean {
        if (!watch.isSpent(now)) return false
        watches.stop(watch.id, WatchState.EXPIRED)
        announceEnd(watch)
        return true
    }

    /**
     * Says, once, that a watch has stopped looking.
     *
     * The one notification a watch posts about itself rather than about what it found.
     *
     * A watch that has spent its budget or run out of window disappears from the active
     * list, and the ongoing notification that a fast one keeps up goes with it. Both of
     * those are silent, and silence is the same shape as a crash. So it says so once, on
     * the same channel as its results, because whoever asked for the watch is the person
     * who wants to know it has stopped looking.
     */
    fun announceEnd(watch: Watch) {
        val granted = ContextCompat.checkSelfPermission(appContext, POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val manager = appContext.getSystemService<NotificationManager>() ?: return
        ensureAlertChannel(manager)
        val ended = appContext.getString(R.string.watch_notification_ended, watch.runs)
        val notification = NotificationCompat.Builder(appContext, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(watch.task.take(MAX_TITLE_CHARS))
            .setContentText(ended)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        runCatching { manager.notify("watch-end-${watch.id}".hashCode(), notification) }
    }

    /**
     * "Check 3 of 60 · next check", or without the promise when there will not be one.
     *
     * The last result of a watch that has just spent its budget arrives at the same moment
     * the watch ends, and the version of this line that always said "next check" made that
     * notification a small lie — one immediately contradicted by the "stopped on its own"
     * notice landing beneath it.
     */
    private fun progress(watch: Watch): String = appContext.getString(
        if (watch.isActive) {
            R.string.watch_notification_progress
        } else {
            R.string.watch_notification_progress_last
        },
        watch.runs,
        Watch.MAX_RUNS,
    )

    private fun openApp() = appContext.packageManager
        .getLaunchIntentForPackage(appContext.packageName)?.let {
            PendingIntent.getActivity(
                appContext,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

    private fun ensureAlertChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(ALERT_CHANNEL) != null) return
        // The name-based form, not android.resource://pkg/<numeric id>: a NotificationChannel's
        // sound is set once and Android will not let the app change it later, but a raw
        // resource's numeric id is not guaranteed stable across a rebuild. The name is.
        val sound = Uri.parse(
            "android.resource://${appContext.packageName}/raw/watch_alert",
        )
        manager.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL,
                "Watch results",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "One notification per watch, when a check actually runs."
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()
                setSound(sound, attributes)
            },
        )
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
                // Not this run's plan. The board is process-wide and holds whatever the
                // person was working on in the chat; a scheduled check must not be able to
                // tick a step of it.
                offerPlan = false,
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
                        "say so plainly. The task below may still read as a request to you " +
                        "rather than a question, if it is a reminder rather than something " +
                        "to look up: it is due now, and delivering it is the check, not " +
                        "something you need a tool for. Say it plainly rather than " +
                        "explaining that you cannot set reminders — this schedule is " +
                        "already the reminder.",
                ),
            ),
        ),
        ChatMessage(role = ChatRole.USER, parts = listOf(MessagePart.Text(watch.task))),
    )

    private companion object {
        /** The same floor a goal uses. Working unattended is what flattens a phone. */
        const val MIN_BATTERY_PERCENT = 15

        /** Separate from GenerationService's ongoing channel. See [alert]. */
        const val ALERT_CHANNEL = "watch-alert"

        /** A notification title is one line; anything past this is the app's problem, not the OS's. */
        const val MAX_TITLE_CHARS = 60
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
