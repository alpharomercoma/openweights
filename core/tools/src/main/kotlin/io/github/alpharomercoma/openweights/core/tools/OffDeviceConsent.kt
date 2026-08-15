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
 * Whether anything may leave the device yet.
 *
 * An interface so the turn loop can be tested without a phone, and so a registry holding no
 * tool that leaves never has to think about it. [OffDeviceConsent] is the real one.
 */
interface ToolConsent {
    /** True once the question has been put and answered, whichever way. */
    val isSettled: Boolean

    /** Records the answer, and acts on a refusal. */
    fun settle(toolName: String, allowed: Boolean)

    /** Nothing left to ask: the state every turn after the first is in. */
    object Settled : ToolConsent {
        override val isSettled: Boolean = true
        override fun settle(toolName: String, allowed: Boolean) = Unit
    }
}

/**
 * Whether the user has agreed, once, that this app may send anything off the device.
 *
 * Everything else here is local, so the first search is the moment that stops being true, and
 * it used to happen with no prompt at all: `web_search` is on from the first run and runs
 * without asking in the default mode, so a question could reach a search engine before anybody
 * had opened the Tools tab. `fetch_url` never had this problem, because the address is the
 * model's to choose and it therefore asks every time.
 *
 * The obvious fix is to ship the tools switched off, and it is the wrong one: a tool that
 * ships off is a feature nobody finds, which is the reasoning [ToolSwitches] already carries.
 * Asking at install is worse still, since the question means nothing before there is anything
 * to ask about.
 *
 * So it is asked once, at the moment the first thing would actually leave, and the answer is
 * remembered. Discovery and consent become the same event, which is better than either alone:
 * the user learns the app can search at exactly the moment they can say no to it.
 *
 * A refusal switches the tool off rather than being kept as an invisible veto. That puts the
 * decision where it can be seen and changed, and it means turning the switch back on is
 * itself the consent, so nothing has to remember a second thing.
 */
@Singleton
class OffDeviceConsent @Inject constructor(
    @ApplicationContext context: Context,
    private val switches: ToolSwitches,
) : ToolConsent {
    private val store = context.getSharedPreferences("off_device_consent", Context.MODE_PRIVATE)

    override val isSettled: Boolean get() = store.getBoolean(KEY, false)

    /**
     * Records the answer, and acts on a refusal.
     *
     * Settled either way. A user who said no is not asked again on the next turn, which would
     * be the same nag the approval-per-call mode already loses people with.
     */
    override fun settle(toolName: String, allowed: Boolean) {
        store.edit { putBoolean(KEY, true) }
        if (!allowed) switches.setEnabled(toolName, false)
    }

    /** For a user who changes their mind by other means, and for tests. */
    fun forget() = store.edit { remove(KEY) }

    private companion object {
        const val KEY = "settled"
    }
}
