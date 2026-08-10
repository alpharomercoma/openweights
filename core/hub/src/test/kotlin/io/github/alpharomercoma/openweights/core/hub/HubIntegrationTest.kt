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
import io.github.alpharomercoma.openweights.core.hub.gguf.ByteWindowSource
import io.github.alpharomercoma.openweights.core.hub.gguf.GgufHeaderParser
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises the real Hugging Face API.
 *
 * The other unit tests prove the parser understands GGUF; this proves the Hub still serves
 * what the parser expects, that its CDN honours range requests, and that inspecting a
 * model really is as cheap as the design assumes.
 *
 * It talks to the network, so it stays opt-in and CI remains hermetic:
 * `OPENWEIGHTS_NETWORK_TESTS=1 ./gradlew :core:hub:test`.
 */
class HubIntegrationTest {
    @Before
    fun requireNetworkTestsEnabled() {
        assumeTrue(
            "set OPENWEIGHTS_NETWORK_TESTS=1 to run tests that call Hugging Face",
            System.getenv("OPENWEIGHTS_NETWORK_TESTS") == "1",
        )
    }

    private val httpClient = OkHttpClient()

    /** These tests only read public repositories, so no token is involved. */
    private val anonymous = HubTokenSource { null }
    private val client = HuggingFaceClient(httpClient, anonymous)

    @Test
    fun searchReturnsRunnableModels() = runBlocking {
        val results = client.search(HubQuery(text = "LFM2.5"))

        println("search returned ${results.size} repositories")
        assertThat(results).isNotEmpty()
        // The llama.cpp app filter is what keeps the results loadable by this app.
        assertThat(results.map { it.id }).contains(REPO)
    }

    @Test
    fun theLlamaCppFilterExcludesGgufThatIsNotALanguageModel() = runBlocking {
        // A video diffusion model packaged as GGUF for ComfyUI. It carries the gguf tag,
        // so the tag filter offers it, and it is 14 GB of weights this app cannot load.
        val results = client.search(HubQuery(text = "Wan2.1-I2V-14B-480P"), limit = 20)

        println("search returned ${results.map { it.id }}")
        assertThat(results.map { it.id }).doesNotContain(VIDEO_GGUF_REPO)
    }

    @Test
    fun theSizeFilterIsAppliedByTheHub() = runBlocking {
        val small = client.search(HubQuery(parameters = ParameterRange.TINY), limit = 20)
        val large = client.search(HubQuery(parameters = ParameterRange.HUGE), limit = 20)

        println("under 2B: ${small.take(3).map { it.id }}")
        println("over 16B: ${large.take(3).map { it.id }}")
        assertThat(small).isNotEmpty()
        assertThat(large).isNotEmpty()
        // Different bands, different repositories. A filter the Hub ignored would return
        // the same page twice and nothing here would notice.
        assertThat(small.map { it.id }.toSet().intersect(large.map { it.id }.toSet())).isEmpty()
    }

    @Test
    fun everySortOrderIsAcceptedByTheHub() = runBlocking {
        HubSort.entries.forEach { sort ->
            val results = client.search(HubQuery(sort = sort), limit = 3)
            println("${sort.parameter}: ${results.firstOrNull()?.id}")
            assertThat(results).isNotEmpty()
        }
    }

    @Test
    fun detailListsDownloadableFilesWithSizes() = runBlocking {
        val detail = client.detail(REPO)

        println("${detail.model.id}: ${detail.files.size} GGUF files, license ${detail.license}")
        assertThat(detail.files).isNotEmpty()
        assertThat(detail.files.all { it.sizeBytes > 0 }).isTrue()
        assertThat(detail.architecture).isEqualTo("lfm2")
    }

    @Test
    fun readsAGgufHeaderWithoutDownloadingTheModel() = runBlocking {
        val url = client.downloadUrl(REPO, FILE)
        val counting = CountingRangeSource(httpClient, url)

        val metadata = GgufHeaderParser(counting).parse()

        println(
            "parsed ${metadata.architecture}: ${metadata.blockCount} blocks, " +
                "kv heads ${metadata.keyValueHeadsPerLayer}, ${metadata.fileType.label}, " +
                "read ${counting.bytesRead} bytes in ${counting.requests} range requests",
        )

        assertThat(metadata.architecture).isEqualTo("lfm2")
        assertThat(metadata.blockCount).isEqualTo(EXPECTED_BLOCKS)
        // LFM2 is a hybrid: attention runs in only a third of its blocks, and the KV cache
        // must be sized from that rather than from the block count.
        assertThat(metadata.keyValueHeadsPerLayer.count { it > 0 })
            .isLessThan(metadata.blockCount)

        // A 1.7 GB file inspected for the price of a small web page.
        assertThat(counting.bytesRead).isLessThan(MAX_INSPECTION_BYTES)
    }

    @Test
    fun kvCacheMathMatchesTheHybridLayout() = runBlocking {
        val metadata = GgufHeaderParser(
            CountingRangeSource(httpClient, client.downloadUrl(REPO, FILE)),
        ).parse()

        val attending = metadata.keyValueHeadsPerLayer.count { it > 0 }
        val uniformEstimate =
            2L * metadata.blockCount * HEADS_PER_ATTENDING_BLOCK * metadata.headDimension *
                CONTEXT * 2

        println(
            "KV cache at $CONTEXT tokens: ${metadata.kvCacheBytes(CONTEXT) / 1024 / 1024} MB " +
                "across $attending attending blocks, versus " +
                "${uniformEstimate / 1024 / 1024} MB if every block were charged",
        )
        assertThat(metadata.kvCacheBytes(CONTEXT)).isLessThan(uniformEstimate)
    }

    private class CountingRangeSource(
        private val httpClient: OkHttpClient,
        private val url: HttpUrl,
    ) : ByteWindowSource {
        var bytesRead = 0
            private set
        var requests = 0
            private set

        override suspend fun read(offset: Long, length: Int): ByteArray {
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=$offset-${offset + length - 1}")
                .build()

            return httpClient.newCall(request).execute().use { response ->
                requests++
                val bytes = if (response.code == HTTP_PARTIAL_CONTENT) {
                    response.body.bytes()
                } else {
                    ByteArray(0)
                }
                bytesRead += bytes.size
                bytes
            }
        }

        private companion object {
            const val HTTP_PARTIAL_CONTENT = 206
        }
    }

    private companion object {
        const val REPO = "LiquidAI/LFM2.5-2.6B-GGUF"
        const val FILE = "LFM2.5-2.6B-Q4_K_M.gguf"

        /** GGUF, and nothing llama.cpp can load. The tag filter offers it; the app filter does not. */
        const val VIDEO_GGUF_REPO = "city96/Wan2.1-I2V-14B-480P-gguf"
        const val EXPECTED_BLOCKS = 30
        const val HEADS_PER_ATTENDING_BLOCK = 8
        const val CONTEXT = 4096

        /** One window is 128 KB; needing more than two means the layout assumption broke. */
        const val MAX_INSPECTION_BYTES = 512 * 1024
    }
}
