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

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The files this session's model has itself created, which changes what needs asking.
 *
 * The write tool asks before replacing a file and the delete tool asks before removing
 * one, because those destroy something of the user's that nothing here can put back. A
 * file the model created ten seconds ago in this same session is not that: rewriting
 * style.css eight times is the loop an agent building a site *is*, and asking eight times
 * teaches the user to stop reading the question. So creations are remembered here, and
 * the asking rules wave through what the session made while still guarding what it found.
 *
 * In memory only, on purpose. After a restart everything on disk is the user's again.
 */
@Singleton
class SessionArtifacts @Inject constructor() {
    private val created = mutableSetOf<String>()

    @Synchronized
    fun created(path: String) {
        created += path.lowercase()
    }

    @Synchronized
    fun isOwn(path: String): Boolean = path.lowercase() in created
}
