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

package io.github.alpharomercoma.openweights.core.hub

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds the picture a lab or a person publishes under.
 *
 * The Hub has these, but not in the search response: `expand[]` accepts no field that
 * carries one, so each publisher costs a lookup of its own. Two things make that
 * affordable. The response is about 120 bytes, and GGUF publishing is concentrated enough
 * that a screen of thirty results is usually a handful of distinct names, most of them
 * already resolved.
 *
 * A publisher is either an organisation or a person and the id does not say which, so
 * organisations are tried first and people second. Both answers are remembered, including
 * "there is no picture", which is what stops a publisher without one from being looked up
 * again on every scroll.
 */
@Singleton
class PublisherAvatars @Inject constructor(private val client: HuggingFaceClient) {
    private val known = mutableMapOf<String, String?>()
    private val lock = Mutex()

    /**
     * The avatar URL for a publisher, or null when there is not one to be had.
     *
     * Null covers a publisher with no picture, a name the Hub does not know, and the
     * network being down. None of those are worth telling the user about: the caller falls
     * back to drawing initials, which is a complete answer on its own.
     */
    suspend fun urlFor(owner: String): String? {
        if (owner.isEmpty()) return null
        lock.withLock { if (known.containsKey(owner)) return known[owner] }

        val url = client.avatarUrl(owner)
        lock.withLock { known[owner] = url }
        return url
    }
}
