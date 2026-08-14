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

/**
 * Sequential little-endian reader over a [ByteWindowSource], buffering one window at a
 * time and refilling only when the cursor runs past it.
 *
 * [skip] moves the cursor without fetching, which is how large fixed-width arrays cost
 * nothing to step over.
 */
internal class WindowedReader(private val source: ByteWindowSource, private val windowBytes: Int) {
    private var buffer = ByteArray(0)
    private var bufferStart = -1L
    private var position = 0L

    /**
     * Total string bytes retained so far.
     *
     * A header is untrusted input from a stranger's repository. Without a ceiling, a
     * crafted file declaring thousands of multi-megabyte strings would make simply
     * *inspecting* a model exhaust memory.
     */
    private var retainedStringBytes = 0L

    private suspend fun ensure(length: Int) {
        val withinBuffer = bufferStart >= 0 &&
            position >= bufferStart &&
            position + length <= bufferStart + buffer.size
        if (withinBuffer) return

        val request = maxOf(length, windowBytes)
        buffer = source.read(position, request)
        bufferStart = position
        if (buffer.size < length) {
            throw GgufParseException("GGUF header ends unexpectedly at byte $position")
        }
    }

    suspend fun readBytes(length: Int): ByteArray {
        if (length < 0) throw GgufParseException("negative length in GGUF header")
        ensure(length)
        val offset = (position - bufferStart).toInt()
        position += length
        return buffer.copyOfRange(offset, offset + length)
    }

    fun skip(byteCount: Long) {
        if (byteCount < 0) throw GgufParseException("negative skip in GGUF header")
        position += byteCount
    }

    suspend fun readUInt32(): Int = readBytes(UINT32_BYTES).toLittleEndianLong(UINT32_BYTES).toInt()

    /**
     * GGUF counts are unsigned 64-bit. Anything that does not fit in a signed Long is a
     * corrupt header rather than a real file, so it is rejected instead of wrapping.
     */
    suspend fun readUInt64(): Long {
        val value = readBytes(UINT64_BYTES).toLittleEndianLong(UINT64_BYTES)
        if (value < 0) throw GgufParseException("GGUF header declares an implausible count")
        return value
    }

    suspend fun readString(): String {
        val length = readUInt64()
        if (length > MAX_STRING_BYTES) {
            throw GgufParseException("GGUF header declares a $length-byte string")
        }
        retainedStringBytes += length
        if (retainedStringBytes > MAX_TOTAL_STRING_BYTES) {
            throw GgufParseException("GGUF header declares an implausible amount of text")
        }
        return readBytes(length.toInt()).decodeToString()
    }

    /** Reads one metadata value of the given GGUF type. */
    suspend fun readValue(type: Int): Any = when (type) {
        TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> readBytes(1)[0].toInt()
        TYPE_UINT16, TYPE_INT16 -> readBytes(2).toLittleEndianLong(2).toInt()
        TYPE_UINT32, TYPE_INT32 -> readUInt32()
        TYPE_FLOAT32 -> Float.fromBits(readUInt32())
        TYPE_UINT64, TYPE_INT64 -> readUInt64()
        TYPE_FLOAT64 -> Double.fromBits(readBytes(UINT64_BYTES).toLittleEndianLong(UINT64_BYTES))
        TYPE_STRING -> readString()
        TYPE_ARRAY -> readArray()
        else -> throw GgufParseException("unknown GGUF value type $type")
    }

    private suspend fun readArray(): Any {
        val elementType = readUInt32()
        val count = readUInt64()
        val elementBytes = FIXED_WIDTHS[elementType]

        if (elementBytes == null) {
            // Strings have no fixed width, so they must be walked one at a time.
            // Clamped as a Long before it becomes an Int. A count above Int.MAX wrapped
            // negative, and List(negative) throws IllegalArgumentException rather than the
            // parse exception every caller here is written to expect.
            return List(count.coerceIn(0, MAX_ARRAY_ELEMENTS.toLong()).toInt()) {
                readValue(elementType)
            }
        }
        // Small integer arrays carry real information, per-layer KV head counts, for one,
        // so read them. Anything larger is vocabulary-sized and irrelevant here: skip it,
        // which costs no bytes at all.
        if (count <= MAX_ARRAY_ELEMENTS) {
            return List(count.toInt()) { readValue(elementType) }
        }
        skip(elementBytes * count)
        return SkippedArray(elementType, count)
    }

    /** Placeholder for an array not read, so callers can tell it apart from absent. */
    data class SkippedArray(val elementType: Int, val count: Long)

    private fun ByteArray.toLittleEndianLong(byteCount: Int): Long {
        var value = 0L
        for (index in byteCount - 1 downTo 0) {
            value = (value shl Byte.SIZE_BITS) or (this[index].toLong() and BYTE_MASK)
        }
        return value
    }

    private companion object {
        const val BYTE_MASK = 0xFFL
        const val UINT32_BYTES = 4
        const val UINT64_BYTES = 8

        const val TYPE_UINT8 = 0
        const val TYPE_INT8 = 1
        const val TYPE_UINT16 = 2
        const val TYPE_INT16 = 3
        const val TYPE_UINT32 = 4
        const val TYPE_INT32 = 5
        const val TYPE_FLOAT32 = 6
        const val TYPE_BOOL = 7
        const val TYPE_STRING = 8
        const val TYPE_ARRAY = 9
        const val TYPE_UINT64 = 10
        const val TYPE_INT64 = 11
        const val TYPE_FLOAT64 = 12

        val FIXED_WIDTHS = mapOf(
            TYPE_UINT8 to 1L, TYPE_INT8 to 1L, TYPE_BOOL to 1L,
            TYPE_UINT16 to 2L, TYPE_INT16 to 2L,
            TYPE_UINT32 to 4L, TYPE_INT32 to 4L, TYPE_FLOAT32 to 4L,
            TYPE_UINT64 to 8L, TYPE_INT64 to 8L, TYPE_FLOAT64 to 8L,
        )

        /** Beyond this an array is vocabulary data, not architecture data. */
        const val MAX_ARRAY_ELEMENTS = 4096

        /** No legitimate metadata string is this large; a bigger one means a corrupt header. */
        const val MAX_STRING_BYTES = 1L * 1024 * 1024

        /** Real headers use a few kilobytes of text before the tokenizer; this is generous. */
        const val MAX_TOTAL_STRING_BYTES = 4L * 1024 * 1024
    }
}
