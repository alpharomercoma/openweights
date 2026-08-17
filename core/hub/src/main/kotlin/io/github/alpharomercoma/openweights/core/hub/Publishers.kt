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
 *
 * That same lookup answers a second question at no extra cost, which is why this is no
 * longer called PublisherAvatars: whether the publisher is an organisation or a person is
 * decided by which of the two paths replied, and it is what the Official filter is built
 * on. One request, two facts, cached together.
 */
@Singleton
class Publishers @Inject constructor(private val client: HuggingFaceClient) {
    private val known = mutableMapOf<String, Publisher>()
    private val lock = Mutex()

    /**
     * Who published under this name, as far as the Hub will say.
     *
     * An unknown publisher covers a name the Hub does not know and the network being
     * down. Neither is worth telling the user about: the row draws without a picture, and
     * an unknown publisher is not an organisation, so the Official filter leaves it out
     * rather than guessing it in.
     */
    suspend fun lookUp(owner: String): Publisher {
        if (owner.isEmpty()) return Publisher()
        lock.withLock { known[owner]?.let { return it } }

        val publisher = client.publisher(owner)
        lock.withLock { known[owner] = publisher }
        return publisher
    }
}
