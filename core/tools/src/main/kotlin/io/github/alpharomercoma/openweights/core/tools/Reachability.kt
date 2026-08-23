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
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the internet is reachable, asked fresh each time.
 *
 * The tools that need it are described to the model on every turn, and until now they were
 * described whether or not they could work. That costs more than a failed call. Measured
 * against the shipped catalogue on eight ordinary questions, with the web tools offered the
 * 2.6B answered from its own knowledge three times and called a tool the other five; with
 * them absent it called one twice. The 1.2B went from five to one. A tool in the prompt is
 * an invitation, and the model accepts it for questions no search would have helped.
 *
 * So offering them offline is the worst of both: the invitation is made, accepted, the call
 * fails, and the reply is either empty or an apology. Not offering them when they cannot
 * work is the same rule [FileTools] already follow with a folder nobody has shared.
 *
 * Cached for a few seconds rather than per call, because `isAvailable` is read once per tool
 * per turn and a system call per read is wasteful. Short enough that stepping into a lift
 * costs one turn.
 */
fun interface Reachability {
    /** True when a search would have something to talk to. */
    fun isOnline(): Boolean
}

/**
 * The real one, reading the platform and remembering the answer for a few seconds.
 *
 * An interface in front of it because `isAvailable` is read on every turn by every tool and
 * a test for a tool should not need a phone to say whether the internet is up.
 */
@Singleton
class AndroidReachability @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : Reachability {
    private var lastAnswer = false
    private var lastAsked = 0L

    override fun isOnline(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastAsked < CACHE_MS) return lastAnswer
        lastAsked = now
        lastAnswer = look()
        return lastAnswer
    }

    /**
     * True when a network says it can reach the internet.
     *
     * `NET_CAPABILITY_VALIDATED` rather than merely connected, because a captive portal is
     * connected and answers every request with a login page, which to a search tool looks
     * like results that are not results.
     */
    private fun look(): Boolean {
        val manager = context.getSystemService<ConnectivityManager>() ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private companion object {
        const val CACHE_MS = 5_000L
    }
}
