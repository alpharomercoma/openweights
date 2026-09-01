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
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which tools the user has switched on.
 *
 * Kept out of the model preferences on purpose: a tool being available is a property of
 * the app, not of whichever model happens to be loaded, and someone who turned off page
 * fetching does not expect it back when they switch models.
 *
 * On by default. A tool that ships switched off is a feature nobody discovers.
 */
@Singleton
class ToolSwitches @Inject constructor(@param:ApplicationContext context: Context) {
    private val store = context.getSharedPreferences("tool_switches", Context.MODE_PRIVATE)

    private val revisions = MutableStateFlow(0)

    /**
     * Bumped on every [setEnabled], so whoever keeps the KV cache warm can hear about it.
     *
     * A toggle rewrites the tool block at the head of every future prompt, and the warm
     * machinery compares bytes it is handed rather than watching preferences. Before this
     * existed the recompute waited for the next send and ran in front of the user — the
     * one place the whole warm design exists to keep it out of.
     */
    val changes: StateFlow<Int> = revisions.asStateFlow()

    fun isEnabled(name: String): Boolean = store.getBoolean(name, true)

    /**
     * The same question asked of a tool, which is the only way to honour [Tool.defaultsOn].
     *
     * Taking a name alone cannot: the default has to come from somewhere, this used to
     * assume true, and a tool that must start off would have started on for everybody who
     * never opened the screen.
     */
    fun isEnabled(tool: Tool): Boolean {
        // A family of verbs over one decision shares one switch; see [Tool.switchName].
        val name = tool.switchName
        if (store.contains(name)) return store.getBoolean(name, tool.defaultsOn)
        // A choice made under the tool's old name still stands. Without this, renaming a
        // tool silently turned it back on for anyone who had switched it off — or, for a
        // tool that starts off, silently off for anyone who had switched it on.
        val legacy = LEGACY_TOOL_NAMES.entries.firstOrNull { it.value == name }?.key
        if (legacy != null && store.contains(legacy)) {
            return store.getBoolean(legacy, tool.defaultsOn)
        }
        return tool.defaultsOn
    }

    fun setEnabled(name: String, enabled: Boolean) {
        store.edit { putBoolean(name, enabled) }
        revisions.value += 1
    }

    /** The names that are on, for filtering the registry before a turn. */
    fun enabled(all: List<String>): Set<String> = all.filter(::isEnabled).toSet()

    /** The same, asked of tools, so each one's own default is used. */
    fun enabledAmong(all: List<Tool>): Set<Tool> = all.filter(::isEnabled).toSet()
}
