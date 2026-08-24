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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Where a downloaded file is allowed to land.
 *
 * The name comes from a stranger's repository listing and is joined to the models directory,
 * so it decides a path on the device. Stripping directories is not enough on its own: a last
 * segment that is itself `..` survives that and resolves to the parent.
 */
class HubFileNameTest {
    private fun file(path: String) = HubFile(path = path, sizeBytes = 1, sha256 = null)

    @Test
    fun `an ordinary name is kept`() {
        assertThat(file("LFM2.5-1.2B-Q4_0.gguf").fileName).isEqualTo("LFM2.5-1.2B-Q4_0.gguf")
    }

    @Test
    fun `directories are stripped`() {
        assertThat(file("quants/q4/model.gguf").fileName).isEqualTo("model.gguf")
    }

    @Test
    fun `a name that is a parent reference is refused`() {
        // File(modelsDirectory, "..") is the parent directory, so this would put a download
        // outside the folder meant to hold it.
        assertThat(file("..").fileName).isEmpty()
        assertThat(file("a/b/..").fileName).isEmpty()
        assertThat(file(".").fileName).isEmpty()
    }

    @Test
    fun `an empty or blank name is refused`() {
        assertThat(file("").fileName).isEmpty()
        assertThat(file("quants/").fileName).isEmpty()
        assertThat(file("   ").fileName).isEmpty()
    }

    @Test
    fun `a backslash is refused, because a name gets copied around`() {
        assertThat(file("..\\evil.gguf").fileName).isEmpty()
    }

    @Test
    fun `a name with dots and unicode is still a name`() {
        // The guard is a denylist on purpose: real model files carry dots, dashes and
        // non-Latin scripts, and an allowlist would refuse them.
        assertThat(file("modelo-ñ.v2.gguf").fileName).isEqualTo("modelo-ñ.v2.gguf")
    }
}
