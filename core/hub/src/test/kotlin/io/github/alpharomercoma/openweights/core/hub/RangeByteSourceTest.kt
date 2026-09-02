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
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Test

/**
 * The one read this app makes against a file it has not downloaded.
 *
 * Inspecting a model asks for a couple of kilobytes out of the front of something that may
 * be several gigabytes, which only works while the server is willing to serve a range. What
 * it does when the server is not willing is the whole of this file: everything on the other
 * side of that request decides how much memory a phone spends, and a header the parser never
 * sees cannot be bounded by the parser.
 */
class RangeByteSourceTest {
    private val server = MockWebServer().apply { start() }
    private val client = OkHttpClient()

    @After
    fun tearDown() = server.close()

    private fun source(token: String? = null) =
        RangeByteSource(client, server.url("/model.gguf"), { token })

    @Test
    fun `a served range comes back as the bytes that were asked for`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(HubHttp.PARTIAL_CONTENT)
                .body("GGUF-header")
                .build(),
        )

        assertThat(String(source().read(offset = 0, length = 11))).isEqualTo("GGUF-header")
    }

    @Test
    fun `the request says which bytes it wants`() = runTest {
        server.enqueue(MockResponse.Builder().code(HubHttp.PARTIAL_CONTENT).body("xxxx").build())

        source().read(offset = 4096, length = 4)

        val range = server.takeRequest().headers["Range"]
        assertThat(range).isEqualTo("bytes=4096-4099")
    }

    @Test
    fun `a server that ignores the range is refused rather than streamed`() = runTest {
        // A 200 means the whole file is on its way. Reading it to find a header at the
        // front would be several gigabytes into a phone's memory for a couple of kilobytes
        // of answer.
        server.enqueue(MockResponse.Builder().code(200).body("the entire file").build())

        val failure = runCatching { source().read(0, 16) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(HubException::class.java)
        assertThat(failure).hasMessageThat().contains("did not serve a byte range")
    }

    @Test
    fun `a range served from the wrong place is refused rather than parsed`() = runTest {
        // The right status with the wrong bytes: read as the header, these parse as a
        // broken file, and the message would blame the model rather than the server.
        server.enqueue(
            MockResponse.Builder()
                .code(HubHttp.PARTIAL_CONTENT)
                .addHeader("Content-Range", "bytes 512-522/100000")
                .body("elsewhere..")
                .build(),
        )

        val failure = runCatching { source().read(offset = 0, length = 11) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(HubException::class.java)
    }

    @Test
    fun `a range answered with far more than was asked for is cut to the ask`() = runTest {
        // The refusal above is not enough on its own. A 206 carrying a body of any size at
        // all was read whole, so a server had only to agree with the range header and then
        // send whatever it liked.
        val huge = Buffer().apply { write(ByteArray(8 * 1024 * 1024)) }
        server.enqueue(
            MockResponse.Builder().code(HubHttp.PARTIAL_CONTENT).body(huge).build(),
        )

        assertThat(source().read(offset = 0, length = 64)).hasLength(64)
    }

    @Test
    fun `a range answered with less than was asked for is what there was`() = runTest {
        // The end of the file, which is ordinary. The reader above decides whether what
        // came back was enough for the field it wanted.
        server.enqueue(MockResponse.Builder().code(HubHttp.PARTIAL_CONTENT).body("tail").build())

        assertThat(source().read(offset = 0, length = 4096)).hasLength(4)
    }

    @Test
    fun `a model that needs a token says so rather than looking malformed`() = runTest {
        // Reported as an empty read, this arrived at the user as "malformed GGUF", which
        // describes the file rather than the one thing they could actually go and fix.
        server.enqueue(MockResponse.Builder().code(401).build())

        val failure = runCatching { source().read(0, 16) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(HubException::class.java)
        assertThat((failure as HubException).isAuthFailure).isTrue()
        assertThat(failure).hasMessageThat().contains("access token")
    }

    @Test
    fun `a token that was rejected is named as the token rather than the model`() = runTest {
        server.enqueue(MockResponse.Builder().code(401).build())

        val failure = runCatching { source("hf_a_token").read(0, 16) }.exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("rejected your access token")
    }

    @Test
    fun `a model whose terms have not been accepted says which terms`() = runTest {
        server.enqueue(MockResponse.Builder().code(403).build())

        val failure = runCatching { source("hf_a_token").read(0, 16) }.exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("Accept its terms")
    }

    @Test
    fun `a token is attached only when there is one`() = runTest {
        server.enqueue(MockResponse.Builder().code(HubHttp.PARTIAL_CONTENT).body("x").build())
        source().read(0, 1)
        assertThat(server.takeRequest().headers["Authorization"]).isNull()

        server.enqueue(MockResponse.Builder().code(HubHttp.PARTIAL_CONTENT).body("x").build())
        source("hf_a_token").read(0, 1)
        assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer hf_a_token")
    }
}
