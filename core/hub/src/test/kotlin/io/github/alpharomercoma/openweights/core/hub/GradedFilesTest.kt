/*
 * Copyright 2026 Alpha Romer Coma
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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The measured-file map only ever points inside the recommendation: a graded file for a
 * repository that is not recommended would mark a card nobody is sent to, and a
 * recommended GGUF row with no graded file is a row whose numbers cover no download.
 */
class GradedFilesTest {
    @Test
    fun `every graded file belongs to a recommended repository`() {
        assertThat(RECOMMENDED).containsAtLeastElementsIn(GRADED.keys)
        GRADED.values.forEach { assertThat(it).endsWith(".gguf") }
    }
}
