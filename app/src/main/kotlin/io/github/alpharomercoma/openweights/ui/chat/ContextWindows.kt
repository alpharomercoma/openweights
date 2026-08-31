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

package io.github.alpharomercoma.openweights.ui.chat

import android.util.Log
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.device.DeviceProfiler
import io.github.alpharomercoma.openweights.core.device.FitEstimator
import io.github.alpharomercoma.openweights.core.hub.gguf.ByteWindowSource
import io.github.alpharomercoma.openweights.core.hub.gguf.GgufHeaderParser
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How wide a window to open a model with when nobody has chosen one.
 *
 * Its own object rather than another method on the runtime, which is at the size static
 * analysis will accept and is about the loaded model rather than about files on disk. What
 * this needs is three things that appear nowhere else together: the file's header, the
 * phone's memory, and the arithmetic that turns both into a number of tokens.
 */
@Singleton
class ContextWindows @Inject constructor(
    private val estimator: FitEstimator,
    private val profiler: DeviceProfiler,
) {
    /**
     * Reads the model's own header and returns the window to open it with.
     *
     * Before the load, which is the only order that works: the trained length is a property
     * of the model and llama.cpp will not say it until the weights are mapped, by which time
     * the window has already been fixed. The same parser Discover uses over the network reads
     * it here off local storage, so the number the app opens with is the number the fit card
     * promised before the download.
     *
     * Anything unreadable falls back to what the app always did. A header that will not parse
     * is a reason to be careful, not a reason to refuse a model that may run perfectly well.
     */
    suspend fun defaultFor(model: File, projector: File?): Int = runCatching {
        // A compiled model's window is baked into the export and enforced by the runtime;
        // there is no header here to read and parsing one as GGUF only put a stack trace
        // in the log on every load. The engine clamps to the export's own limit either way.
        if (model.extension.equals("pte", ignoreCase = true)) {
            return ModelLoadParams.DEFAULT_CONTEXT_LENGTH
        }
        val metadata = GgufHeaderParser(model.readHeadOnce()).parse()
        estimator.defaultContextLength(
            device = profiler.profile(),
            metadata = metadata,
            fileSizeBytes = model.length(),
            projectorSizeBytes = projector?.length() ?: 0,
        )
    }.getOrElse { failure ->
        Log.w("OpenWeights", "could not size the window for ${model.name}", failure)
        ModelLoadParams.DEFAULT_CONTEXT_LENGTH
    }
}

/**
 * The front of the file, once, as something the GGUF parser can read windows out of.
 *
 * The same interface an HTTP range request satisfies, which is the point: one parser reads a
 * model on the Hub and a model on disk, and the fit shown before a download is computed the
 * same way as the window opened after it.
 *
 * Read in one go rather than a handle per window. The parser asks for many small slices and
 * the first shape of this opened, seeked and closed a `RandomAccessFile` for each of them,
 * which is dozens of syscalls on the path a cold start always takes. A header stops at the
 * first tokenizer key, about 1.3 KB on a real model, so [HEAD_BYTES] covers every file this
 * will meet with room to spare and costs one read.
 */
private fun File.readHeadOnce(): ByteWindowSource {
    val head = RandomAccessFile(this, "r").use { handle ->
        val size = minOf(handle.length(), HEAD_BYTES).toInt()
        ByteArray(size).also { handle.readFully(it) }
    }
    return ByteWindowSource { offset, length ->
        if (offset >= head.size) {
            ByteArray(0)
        } else {
            head.copyOfRange(offset.toInt(), minOf(offset.toInt() + length, head.size))
        }
    }
}

/** Comfortably past where a GGUF header stops, and small enough to read without thinking. */
private const val HEAD_BYTES = 1L * 1024 * 1024
