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

    fun isEnabled(name: String): Boolean = store.getBoolean(name, true)

    fun setEnabled(name: String, enabled: Boolean) = store.edit { putBoolean(name, enabled) }

    /** The names that are on, for filtering the registry before a turn. */
    fun enabled(all: List<String>): Set<String> = all.filter(::isEnabled).toSet()
}
