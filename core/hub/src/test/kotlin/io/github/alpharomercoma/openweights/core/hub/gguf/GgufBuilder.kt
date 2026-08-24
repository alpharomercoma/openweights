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

import java.io.ByteArrayOutputStream

/**
 * Writes syntactically real GGUF headers for tests.
 *
 * Building the bytes by hand keeps the parser tests offline and lets them cover shapes
 * that are awkward to find in the wild: hybrid per-layer head counts, absent keys,
 * truncated files.
 */
internal class GgufBuilder {
    private val entries = mutableListOf<ByteArray>()

    fun string(key: String, value: String) = apply {
        entries += buildEntry(key, TYPE_STRING) { writeString(value) }
    }

    fun uint32(key: String, value: Int) = apply {
        entries += buildEntry(key, TYPE_UINT32) { writeUInt32(value) }
    }

    fun int32Array(key: String, values: List<Int>) = apply {
        entries += buildEntry(key, TYPE_ARRAY) {
            writeUInt32(TYPE_INT32)
            writeUInt64(values.size.toLong())
            values.forEach { writeUInt32(it) }
        }
    }

    /** A vocabulary-sized string array. The thing the parser must never read. */
    fun hugeStringArray(key: String, count: Int) = apply {
        entries += buildEntry(key, TYPE_ARRAY) {
            writeUInt32(TYPE_STRING)
            writeUInt64(count.toLong())
            repeat(count) { writeString("token$it") }
        }
    }

    /**
     * An array of arrays of arrays, [depth] deep, holding nothing at the bottom.
     *
     * Twelve bytes per level buys one more frame of parser recursion, so a header a browser
     * would call small describes a nesting no stack survives. GGUF does not have nested
     * arrays; a file with them is a file somebody built by hand.
     */
    fun nestedArray(key: String, depth: Int) = apply {
        entries += buildEntry(key, TYPE_ARRAY) {
            repeat(depth) {
                writeUInt32(TYPE_ARRAY)
                writeUInt64(1)
            }
            writeUInt32(TYPE_INT32)
            writeUInt64(0)
        }
    }

    /**
     * An array whose declared size in bytes wraps to exactly zero.
     *
     * Eight bytes an element and two to the sixty-first elements multiply to two to the
     * sixty-fourth, which is zero in a Long. Chosen over a count that wraps to something
     * negative because negative is already caught: zero is the one that looks like a
     * perfectly ordinary array of no bytes at all.
     */
    fun arrayWithOverflowingSize(key: String) = apply {
        entries += buildEntry(key, TYPE_ARRAY) {
            writeUInt32(TYPE_UINT64)
            writeUInt64(1L shl 61)
        }
    }

    fun build(): ByteArray = ByteArrayOutputStream().apply {
        write("GGUF".toByteArray())
        writeUInt32(GGUF_VERSION)
        writeUInt64(0) // tensor count
        writeUInt64(entries.size.toLong())
        entries.forEach { write(it) }
    }.toByteArray()

    private fun buildEntry(
        key: String,
        type: Int,
        writeValue: ByteArrayOutputStream.() -> Unit,
    ): ByteArray = ByteArrayOutputStream().apply {
        writeString(key)
        writeUInt32(type)
        writeValue()
    }.toByteArray()

    private companion object {
        const val GGUF_VERSION = 3
        const val TYPE_UINT32 = 4
        const val TYPE_INT32 = 5
        const val TYPE_UINT64 = 10
        const val TYPE_STRING = 8
        const val TYPE_ARRAY = 9
    }
}

private fun ByteArrayOutputStream.writeUInt32(value: Int) {
    repeat(Int.SIZE_BYTES) { index -> write((value shr (index * Byte.SIZE_BITS)) and 0xFF) }
}

private fun ByteArrayOutputStream.writeUInt64(value: Long) {
    repeat(Long.SIZE_BYTES) { index ->
        write(((value shr (index * Byte.SIZE_BITS)) and 0xFF).toInt())
    }
}

private fun ByteArrayOutputStream.writeString(value: String) {
    val bytes = value.toByteArray()
    writeUInt64(bytes.size.toLong())
    write(bytes)
}
