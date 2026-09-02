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

/**
 * Sealed storage for the one secret this module holds, a search proxy's credentials.
 *
 * The cipher, and the Keystore key behind it, belong to the token vault in `:core:data`,
 * which this module does not see, so the app wires this to it. Both halves answer null
 * rather than throw when the key cannot be had, and there is no plain fallback behind
 * them: a secret that cannot be sealed is not stored, and one that cannot be opened is
 * not there.
 */
interface SecretSealer {
    /** [value] as one storable string, or null when nothing can seal it right now. */
    fun seal(value: String): String?

    /** What [stored] was sealed from, or null when it cannot be read. */
    fun open(stored: String): String?

    /**
     * The answer when there is no key store to draw on, which is what every host test and
     * device probe has. It seals nothing and opens nothing, so credentials typed against
     * it are dropped rather than kept in the clear.
     */
    object Unavailable : SecretSealer {
        override fun seal(value: String): String? = null

        override fun open(stored: String): String? = null
    }
}
