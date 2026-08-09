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

package io.github.alpharomercoma.openweights.core.hub.gguf

import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.common.model.GgufFileType
import kotlinx.coroutines.test.runTest
import org.junit.Test

class GgufHeaderParserTest {
    @Test
    fun `reads the fields the fit estimator needs`() = runTest {
        val header = GgufBuilder()
            .string("general.architecture", "llama")
            .string("general.name", "Test Model")
            .uint32("general.file_type", GgufFileType.Q4_K_M.id)
            .uint32("llama.block_count", 32)
            .uint32("llama.embedding_length", 4096)
            .uint32("llama.attention.head_count", 32)
            .uint32("llama.attention.head_count_kv", 8)
            .uint32("llama.context_length", 8192)
            .build()

        val metadata = GgufHeaderParser(header.asSource()).parse()

        assertThat(metadata.architecture).isEqualTo("llama")
        assertThat(metadata.name).isEqualTo("Test Model")
        assertThat(metadata.blockCount).isEqualTo(32)
        assertThat(metadata.headDimension).isEqualTo(128)
        assertThat(metadata.fileType).isEqualTo(GgufFileType.Q4_K_M)
        assertThat(metadata.trainingContextLength).isEqualTo(8192)
    }

    @Test
    fun `expands a single kv head count across every block`() = runTest {
        val header = GgufBuilder()
            .string("general.architecture", "llama")
            .uint32("llama.block_count", 4)
            .uint32("llama.embedding_length", 512)
            .uint32("llama.attention.head_count", 8)
            .uint32("llama.attention.head_count_kv", 2)
            .build()

        val metadata = GgufHeaderParser(header.asSource()).parse()

        assertThat(metadata.keyValueHeadsPerLayer).containsExactly(2, 2, 2, 2)
        assertThat(metadata.totalKeyValueHeads).isEqualTo(8)
    }

    @Test
    fun `keeps per-layer kv head counts for hybrid architectures`() = runTest {
        // LFM2 runs attention in only a third of its blocks. Treating the count as uniform
        // would overstate the KV cache, and therefore the RAM needed, about threefold.
        val header = GgufBuilder()
            .string("general.architecture", "lfm2")
            .uint32("lfm2.block_count", 6)
            .uint32("lfm2.embedding_length", 2048)
            .uint32("lfm2.attention.head_count", 32)
            .int32Array("lfm2.attention.head_count_kv", listOf(0, 0, 8, 0, 0, 8))
            .build()

        val metadata = GgufHeaderParser(header.asSource()).parse()

        assertThat(metadata.keyValueHeadsPerLayer).containsExactly(0, 0, 8, 0, 0, 8).inOrder()
        assertThat(metadata.totalKeyValueHeads).isEqualTo(16)
    }

    @Test
    fun `treats a missing kv head count as multi-head attention`() = runTest {
        val header = GgufBuilder()
            .string("general.architecture", "llama")
            .uint32("llama.block_count", 2)
            .uint32("llama.embedding_length", 256)
            .uint32("llama.attention.head_count", 4)
            .build()

        val metadata = GgufHeaderParser(header.asSource()).parse()

        assertThat(metadata.keyValueHeadsPerLayer).containsExactly(4, 4)
    }

    @Test
    fun `stops before the tokenizer, which is where the megabytes are`() = runTest {
        val header = GgufBuilder()
            .string("general.architecture", "llama")
            .uint32("llama.block_count", 2)
            .uint32("llama.embedding_length", 256)
            .uint32("llama.attention.head_count", 4)
            .string("tokenizer.ggml.model", "gpt2")
            .hugeStringArray("tokenizer.ggml.tokens", count = 20_000)
            .build()

        val counting = CountingSource(header)
        GgufHeaderParser(counting, windowBytes = 4096).parse()

        // The vocabulary alone is far larger than this; reading it would defeat the point
        // of inspecting a model before downloading it.
        assertThat(counting.bytesRead).isLessThan(16 * 1024)
    }

    @Test
    fun `rejects bytes that are not a GGUF file`() = runTest {
        val notGguf = ByteArray(64) { 0 }

        val failure = runCatching { GgufHeaderParser(notGguf.asSource()).parse() }

        assertThat(failure.exceptionOrNull()).isInstanceOf(GgufParseException::class.java)
    }

    @Test
    fun `rejects a truncated header rather than inventing values`() = runTest {
        val truncated = GgufBuilder()
            .string("general.architecture", "llama")
            .uint32("llama.block_count", 32)
            .build()
            .copyOf(40)

        val failure = runCatching { GgufHeaderParser(truncated.asSource()).parse() }

        assertThat(failure.exceptionOrNull()).isInstanceOf(GgufParseException::class.java)
    }

    private fun ByteArray.asSource() = ByteWindowSource { offset, length ->
        if (offset >= size) {
            ByteArray(0)
        } else {
            copyOfRange(offset.toInt(), minOf(size.toLong(), offset + length).toInt())
        }
    }

    private class CountingSource(private val bytes: ByteArray) : ByteWindowSource {
        var bytesRead = 0
            private set

        override suspend fun read(offset: Long, length: Int): ByteArray {
            if (offset >= bytes.size) return ByteArray(0)
            val end = minOf(bytes.size.toLong(), offset + length).toInt()
            bytesRead += end - offset.toInt()
            return bytes.copyOfRange(offset.toInt(), end)
        }
    }
}
