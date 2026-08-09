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

import io.github.alpharomercoma.openweights.core.common.model.GgufFileType
import io.github.alpharomercoma.openweights.core.common.model.GgufMetadata

/** Supplies bytes from an arbitrary offset. An HTTP range request, or a local file. */
fun interface ByteWindowSource {
    /**
     * Reads up to [length] bytes starting at [offset]. May return fewer bytes at the end
     * of the source; returning empty means there is nothing left.
     */
    suspend fun read(offset: Long, length: Int): ByteArray
}

/** Raised when the bytes are not a GGUF file, or the header is truncated or malformed. */
class GgufParseException(message: String) : Exception(message)

/**
 * Reads a GGUF file's header without downloading the file.
 *
 * This is what lets the app answer "will this run on my phone?" before spending a gigabyte
 * of someone's mobile data. The header layout makes it cheap: llama.cpp writes
 * `general.*` and the architecture's own keys first, and only then the tokenizer, whose
 * vocabulary and merge arrays are the bulk of the header. Parsing stops at the first
 * `tokenizer.` key, which on a real 2.6 B model means reading about 1.3 KB instead of 8 MB.
 *
 * The one thing that costs is `general.file_type`: llama.cpp writes it after the
 * tokenizer, so it is usually absent here and [GgufFileType.UNKNOWN] is the honest answer.
 * The quantization is in the filename, which is where callers should read it.
 *
 * Format reference: GGUF v3, little-endian throughout.
 */
class GgufHeaderParser(
    private val source: ByteWindowSource,
    private val windowBytes: Int = DEFAULT_WINDOW_BYTES,
) {
    suspend fun parse(): GgufMetadata {
        val reader = WindowedReader(source, windowBytes)

        val keyValueCount = reader.readFileHeader()

        val values = mutableMapOf<String, Any>()
        var architecture: String? = null

        var remaining = keyValueCount
        while (remaining > 0) {
            remaining--
            val key = reader.readString()
            // Everything the estimator needs precedes the tokenizer, and the tokenizer is
            // where the megabytes are. Stopping here is the whole optimisation.
            if (key.startsWith(TOKENIZER_PREFIX)) break

            val value = reader.readValue(reader.readUInt32())
            values[key] = value
            if (key == KEY_ARCHITECTURE) architecture = value as? String
        }

        val arch = architecture
            ?: throw GgufParseException("GGUF header declares no architecture")

        return GgufMetadata(
            architecture = arch,
            blockCount = values.int("$arch.block_count")
                ?: throw GgufParseException("GGUF header declares no block count"),
            embeddingLength = values.int("$arch.embedding_length") ?: 0,
            headCount = values.int("$arch.attention.head_count") ?: 0,
            keyValueHeadsPerLayer = values.keyValueHeads(arch),
            trainingContextLength = values.int("$arch.context_length") ?: 0,
            fileType = GgufFileType.fromId(values.int(KEY_FILE_TYPE) ?: -1),
            name = values[KEY_NAME] as? String,
        )
    }

    /** Validates the magic and version, and returns the metadata entry count. */
    private suspend fun WindowedReader.readFileHeader(): Long {
        if (!readBytes(MAGIC.size).contentEquals(MAGIC)) {
            throw GgufParseException("not a GGUF file")
        }
        val version = readUInt32()
        if (version !in SUPPORTED_VERSIONS) {
            throw GgufParseException("unsupported GGUF version $version")
        }
        readUInt64() // tensor count, not needed for fit estimation
        return readUInt64()
    }

    private fun Map<String, Any>.int(key: String): Int? = when (val value = this[key]) {
        is Int -> value
        is Long -> value.toInt()
        else -> null
    }

    /**
     * Key/value head counts per block.
     *
     * Written either as one number for uniform architectures or as an array for hybrids
     * that only run attention in some blocks. Both spellings normalise to a per-block list
     * so callers never have to care which one a model used.
     */
    private fun Map<String, Any>.keyValueHeads(architecture: String): List<Int> {
        val blocks = int("$architecture.block_count") ?: 0
        return when (val value = this["$architecture.attention.head_count_kv"]) {
            is List<*> -> value.map { (it as? Number)?.toInt() ?: 0 }
            is Int -> List(blocks) { value }
            is Long -> List(blocks) { value.toInt() }
            // No entry means multi-head attention: as many KV heads as query heads.
            else -> List(blocks) { int("$architecture.attention.head_count") ?: 0 }
        }
    }

    private companion object {
        val MAGIC =
            byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())
        val SUPPORTED_VERSIONS = 1..3
        const val DEFAULT_WINDOW_BYTES = 128 * 1024
        const val TOKENIZER_PREFIX = "tokenizer."
        const val KEY_ARCHITECTURE = "general.architecture"
        const val KEY_FILE_TYPE = "general.file_type"
        const val KEY_NAME = "general.name"
    }
}
