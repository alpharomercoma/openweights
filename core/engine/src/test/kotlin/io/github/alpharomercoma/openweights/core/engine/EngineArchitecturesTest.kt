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

package io.github.alpharomercoma.openweights.core.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The list is generated from the submodule, so this tests the generator, not a hand list.
 *
 * A silent parse failure is the failure mode worth guarding: if `LLM_ARCH_NAMES` is
 * reformatted upstream and the regex stops matching, the set comes back empty, every model
 * reads as unsupported, and Discover refuses every download in the app while the build
 * stays green. The size floor in the Gradle task catches the total wipeout; these catch the
 * subtler shapes, a table read but read wrongly.
 */
class EngineArchitecturesTest {
    @Test
    fun `the table parsed into something the size of llama cpp's actual support`() {
        // A floor, not the exact count. The generator already requires its matches to
        // equal the entries the table declares, which is the real structural check;
        // repeating today's number here would only mean every submodule bump fails this
        // test until somebody edits the literal, and a number people are trained to
        // update without reading has stopped testing anything.
        assertThat(EngineArchitectures.SUPPORTED.size).isGreaterThan(100)
    }

    @Test
    fun `architectures this app is built around are present`() {
        // The four families the shortlist ships and the one that started this: Ling 3.0 is
        // bailingmoe3, which arrived upstream after the previous pin and is the reason the
        // submodule moved.
        assertThat(EngineArchitectures.SUPPORTED)
            .containsAtLeast("llama", "qwen3", "lfm2", "bailingmoe2", "bailingmoe3")
    }

    @Test
    fun `a speculative-decoding draft head is not a model this app can run`() {
        // `dflash` and `eagle3` load without complaint and then fail at the last moment,
        // because a draft carries no vocabulary and no output layer: it borrows both from
        // the model it drafts for. Dropped from SUPPORTED for the same reason `clip` is,
        // and named in DRAFT so the reason given can be the true one rather than "update
        // the app", which would send somebody after a release that is never coming.
        assertThat(EngineArchitectures.SUPPORTED).containsNoneOf("dflash", "eagle3")
        assertThat(EngineArchitectures.supports("dflash")).isFalse()
        assertThat(EngineArchitectures.isDraft("dflash")).isTrue()
        assertThat(EngineArchitectures.isDraft("DFlash")).isTrue()
        assertThat(EngineArchitectures.isDraft("lfm2")).isFalse()
    }

    @Test
    fun `the table's own placeholders are not treated as architectures`() {
        // `clip` is a dummy the table carries for llama-quantize and `(unknown)` is the
        // name of the absent value. A GGUF declaring neither can be loaded, so counting
        // them as supported would wave through a file the engine cannot read.
        assertThat(EngineArchitectures.SUPPORTED).containsNoneOf("clip", "(unknown)")
    }

    @Test
    fun `an architecture released after this build is not supported`() {
        assertThat(EngineArchitectures.supports("bailingmoe4")).isFalse()
    }

    @Test
    fun `case does not decide the answer`() {
        // GGUF writers are not consistent about it and a capital letter is not a reason to
        // tell somebody their model will not run.
        assertThat(EngineArchitectures.supports("BailingMoE3")).isTrue()
    }

    @Test
    fun `an architecture nobody could read is not a refusal`() {
        // Fail open. The check exists to save a download, not to become a second way for
        // the app to say no when it does not actually know anything.
        assertThat(EngineArchitectures.supports("")).isTrue()
    }
}
