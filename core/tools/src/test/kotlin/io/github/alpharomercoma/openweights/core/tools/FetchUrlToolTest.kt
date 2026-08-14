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
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Test

/**
 * What the one tool that dials an address of the model's choosing may be talked into.
 *
 * [PublicOnlyDns] covers the case where a name resolves somewhere it should not. These are
 * the cases that never reach a resolver at all, which is the gap that makes the resolver an
 * incomplete defence on its own. Every address here is refused before any socket is opened,
 * so none of these tests touch the network.
 */
class FetchUrlToolTest {
    private val tool = FetchUrlTool(OkHttpClient())

    private suspend fun fetch(url: String): String =
        tool.run(ToolCall(id = "1", name = "fetch_url", argumentsJson = """{"url":"$url"}"""))

    @Test
    fun `reading an address the model composed always asks first`() {
        // Tool.alwaysAsk exists for exactly this tool and says so: searching is bounded
        // whatever the model asks for, and fetching an address it composed is not. Auto
        // mode is about removing pointless taps, not the only check on an open primitive,
        // and the address can come from a page the model has just read.
        assertThat(tool.alwaysAsk).isTrue()
    }

    @Test
    fun `an address written as a bare loopback literal is refused`() = runTest {
        // OkHttp routes a hostname through Dns and an IP literal straight to a socket, so
        // PublicOnlyDns is never consulted about this one and cannot refuse it. A page that
        // said "now read https://127.0.0.1:8080/" reached whatever was listening.
        assertThat(fetch("https://127.0.0.1:8080/admin")).contains("not on the public internet")
    }

    @Test
    fun `the home router written as a literal is refused`() = runTest {
        assertThat(fetch("https://192.168.1.1/")).contains("not on the public internet")
    }

    @Test
    fun `a public looking name in front of a private literal is refused`() = runTest {
        // The host is the literal; everything before the @ is userinfo and goes nowhere. A
        // check on the string rather than on the parsed host reads this as example.com.
        assertThat(fetch("https://example.com@10.0.0.1/")).contains("not on the public internet")
    }

    @Test
    fun `link local metadata addresses are refused`() = runTest {
        assertThat(fetch("https://169.254.169.254/latest/meta-data/"))
            .contains("not on the public internet")
    }

    @Test
    fun `an https address in capitals is not refused for its scheme`() = runTest {
        // Schemes are case insensitive, and the check was startsWith("https://"). A model
        // writing HTTPS was told the app only reads https, which reads as nonsense.
        assertThat(fetch("HTTPS://example.invalid/")).doesNotContain("Only https")
    }

    @Test
    fun `plain http is refused for its scheme`() = runTest {
        assertThat(fetch("http://example.invalid/")).contains("Only https")
    }

    @Test
    fun `a file address is not an address that can be read`() = runTest {
        // Not an http address at all, so it never becomes a request. The app's own database
        // lives under a path like this one.
        assertThat(fetch("file:///data/data/io.github.alpharomercoma.openweights/databases"))
            .contains("not an address that can be read")
    }

    @Test
    fun `something that is not an address at all is refused rather than dialled`() = runTest {
        assertThat(fetch("not a url")).isNotEmpty()
    }
}
