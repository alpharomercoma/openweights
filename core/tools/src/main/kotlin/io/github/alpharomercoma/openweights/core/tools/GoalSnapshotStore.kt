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

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.alpharomercoma.openweights.core.common.context.Goal
import io.github.alpharomercoma.openweights.core.common.context.GoalState
import io.github.alpharomercoma.openweights.core.common.context.TaskPlan
import io.github.alpharomercoma.openweights.core.common.context.TaskStep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** The complete small piece of mutable state owned by [GoalBoard]. */
internal data class GoalSnapshot(val goal: Goal, val steering: List<String>)

/**
 * One durable slot for an unfinished goal.
 *
 * This is deliberately not a database table. There is only ever one board, it has no history
 * or queries, and replacing its one value atomically is the operation SharedPreferences already
 * provides. The JSON carries its own version so an incompatible future shape is discarded rather
 * than guessed at.
 */
@Singleton
@Suppress("ApplySharedPref") // A recovery boundary must be on disk before the call returns.
class GoalSnapshotStore private constructor(private val preferences: SharedPreferences?) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE),
    )

    internal fun load(): GoalSnapshot? {
        val store = preferences ?: return null
        val raw = try {
            store.getString(KEY, null) ?: return null
        } catch (_: ClassCastException) {
            store.removeSnapshot()
            return null
        }
        val snapshot = raw.takeIf { it.length <= MAX_SNAPSHOT_CHARS }
            ?.let { encoded -> runCatching { decode(encoded) }.getOrNull() }
        if (snapshot == null) store.removeSnapshot()
        return snapshot
    }

    internal fun save(snapshot: GoalSnapshot) {
        val store = preferences ?: return
        val encoded = encode(snapshot)
        val isValid = encoded.length <= MAX_SNAPSHOT_CHARS &&
            runCatching { decode(encoded) }.isSuccess
        if (isValid) {
            val saved = runCatching { store.edit().putString(KEY, encoded).commit() }
                .getOrDefault(false)
            if (!saved) store.removeSnapshot()
        } else {
            // Never leave an older valid goal behind when the new value cannot be saved.
            // Resurrecting that older goal after a restart is worse than having no recovery.
            store.removeSnapshot()
        }
    }

    internal fun clear() {
        preferences?.removeSnapshot()
    }

    private fun SharedPreferences.removeSnapshot() {
        runCatching { edit().remove(KEY).commit() }
    }

    private fun encode(snapshot: GoalSnapshot): String = buildJsonObject {
        put("version", JsonPrimitive(VERSION))
        put("task", JsonPrimitive(snapshot.goal.task))
        put("stepsTaken", JsonPrimitive(snapshot.goal.stepsTaken))
        put("state", JsonPrimitive(snapshot.goal.state.name))
        snapshot.goal.note?.let { put("note", JsonPrimitive(it)) }
        snapshot.goal.conversationId?.let { put("conversationId", JsonPrimitive(it)) }
        snapshot.goal.plan?.let { plan ->
            put(
                "plan",
                buildJsonArray {
                    plan.steps.forEach { step ->
                        add(
                            buildJsonObject {
                                put("text", JsonPrimitive(step.text))
                                put("done", JsonPrimitive(step.done))
                            },
                        )
                    }
                },
            )
        }
        put(
            "steering",
            buildJsonArray { snapshot.steering.forEach { add(JsonPrimitive(it)) } },
        )
    }.toString()

    private fun decode(raw: String): GoalSnapshot {
        val root = Json.parseToJsonElement(raw).jsonObject
        require(root.integer("version") == VERSION)
        val task = root.text("task", MAX_TASK_CHARS)
        val stepsTaken = root.integer("stepsTaken")
        require(stepsTaken in 0..Goal.MAX_STEPS)
        val state = root.requiredText("state").let { GoalState.valueOf(it) }
        val note = root.optionalText("note", MAX_NOTE_CHARS)
        // Absent in a snapshot written before this field existed. Null reads the same as a
        // goal that has always had no conversation of its own would, which is the right
        // default for one nobody can place any more: treated as belonging to whichever
        // conversation is open, exactly as every goal did before this existed.
        val conversationId = root["conversationId"]?.jsonPrimitive?.longOrNull
        val plan = root["plan"]?.jsonArray?.toPlan()
        val steering = root["steering"]?.jsonArray?.toSteering().orEmpty()
        return GoalSnapshot(
            goal = Goal(task, plan, stepsTaken, state, note, conversationId),
            steering = steering,
        )
    }

    private fun JsonArray.toPlan(): TaskPlan {
        require(size <= TaskPlan.MAX_STEPS)
        return TaskPlan(
            map { encoded ->
                val step = encoded.jsonObject
                TaskStep(
                    text = step.text("text", TaskPlan.MAX_STEP_CHARS),
                    done = step.boolean("done"),
                )
            },
        )
    }

    private fun JsonArray.toSteering(): List<String> {
        require(size <= MAX_STEERING_MESSAGES)
        return map {
            it.jsonPrimitive.also { value -> require(value.isString) }.content
        }.also { messages ->
            require(messages.sumOf(String::length) <= MAX_STEERING_CHARS)
        }
    }

    private fun JsonObject.integer(name: String): Int {
        val value = get(name)?.jsonPrimitive ?: error("$name is missing")
        require(!value.isString)
        return value.intOrNull ?: error("$name is not an integer")
    }

    private fun JsonObject.boolean(name: String): Boolean {
        val value = get(name)?.jsonPrimitive ?: error("$name is missing")
        require(!value.isString)
        return value.booleanOrNull ?: error("$name is not a boolean")
    }

    private fun JsonObject.requiredText(name: String): String {
        val value = get(name)?.jsonPrimitive ?: error("$name is missing")
        require(value.isString)
        return value.content
    }

    private fun JsonObject.text(name: String, limit: Int): String =
        requiredText(name).also { require(it.length <= limit) }

    private fun JsonObject.optionalText(name: String, limit: Int): String? = get(name)?.let {
        val value = it.jsonPrimitive
        require(value.isString)
        value.content.also { text -> require(text.length <= limit) }
    }

    companion object {
        internal const val PREFERENCES = "goal_board"
        internal const val KEY = "snapshot"
        private const val VERSION = 1
        private const val MAX_SNAPSHOT_CHARS = 32_768
        private const val MAX_TASK_CHARS = 16_384
        private const val MAX_NOTE_CHARS = 4_096
        private const val MAX_STEERING_MESSAGES = 32
        private const val MAX_STEERING_CHARS = 8_192

        /** Keeps direct construction in small unit fixtures lightweight and non-persistent. */
        internal fun none(): GoalSnapshotStore = GoalSnapshotStore(null)
    }
}
