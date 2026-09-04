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

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Test

/**
 * Turning what somebody pasted into an address that can be dialled.
 *
 * The case this exists for was reported from a real conversation: "what do you think of this
 * application? github.com/alpharomercoma/openweights". The model passed the address on
 * exactly as it was given, which is the form with no scheme, and the tool answered that it
 * was not an address that could be read. Nobody types a scheme, so the tool declined the one
 * call it most obviously should have made.
 */
class AddressOfTest {
    @Test
    fun `the parser alone refuses an address with no scheme`() {
        // Pinned rather than assumed, because it is the whole reason this function exists.
        // Everything below is built on OkHttp answering null here.
        assertThat("github.com/alpharomercoma/openweights".toHttpUrlOrNull()).isNull()
    }

    @Test
    fun `an address with no scheme is read as https`() {
        assertThat(addressOf("github.com/alpharomercoma/openweights").toString())
            .isEqualTo("https://github.com/alpharomercoma/openweights")
    }

    @Test
    fun `a bare host works, and so does one with a subdomain`() {
        assertThat(addressOf("example.com").toString()).isEqualTo("https://example.com/")
        assertThat(addressOf("docs.example.co.uk/guide").toString())
            .isEqualTo("https://docs.example.co.uk/guide")
    }

    @Test
    fun `an address that already has a scheme is left as it is`() {
        assertThat(addressOf("https://example.com/a").toString())
            .isEqualTo("https://example.com/a")
        // http parses and is refused later, by name, which is a better message than this
        // returning null and the model being told its address was unreadable.
        assertThat(addressOf("http://example.com/a").toString())
            .isEqualTo("http://example.com/a")
    }

    @Test
    fun `the full stop that ended the sentence is not part of the address`() {
        assertThat(addressOf("Read example.com/docs.")).isNull()
        assertThat(addressOf("example.com/docs.").toString())
            .isEqualTo("https://example.com/docs")
        assertThat(addressOf("example.com/docs,").toString())
            .isEqualTo("https://example.com/docs")
    }

    @Test
    fun `an address copied out of markup loses its wrapping`() {
        assertThat(addressOf("<https://example.com/a>").toString())
            .isEqualTo("https://example.com/a")
        assertThat(addressOf("\"example.com/a\"").toString())
            .isEqualTo("https://example.com/a")
    }

    @Test
    fun `a scheme this tool cannot fetch is refused rather than rebuilt`() {
        // Prefixing https onto this would invent an address and dial it.
        assertThat(addressOf("ftp://files.example.com/x")).isNull()
    }

    @Test
    fun `a filename is not quietly turned into a hostname`() {
        // A model calling fetch_url with a path has already made a mistake, and answering
        // "that is not an address" is a better mistake than resolving a hostname from it.
        assertThat(addressOf("notes")).isNull()
        assertThat(addressOf("/home/user/notes.txt")).isNull()
        assertThat(addressOf("1.5")).isNull()
        assertThat(addressOf("192.168.1.1")).isNull()
    }

    @Test
    fun `nothing at all is nothing`() {
        assertThat(addressOf("")).isNull()
        assertThat(addressOf("   ")).isNull()
        assertThat(addressOf("what do you think")).isNull()
    }

    @Test
    fun `a private address written with a scheme still reaches the guard that refuses it`() {
        // This must not start refusing here: the refusal that matters says why, and it runs
        // on every redirect hop as well as on the address the user approved.
        val parsed = addressOf("https://192.168.1.1/admin")

        assertThat(parsed).isNotNull()
        assertThat(refuseAddress(parsed!!)).isNotNull()
    }
}
