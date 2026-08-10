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

import io.github.alpharomercoma.openweights.core.hub.HubHttp.withRangeFrom
import io.github.alpharomercoma.openweights.core.hub.HubHttp.withToken
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Progress of one model download. */
sealed interface DownloadProgress {
    data class Downloading(val bytesDone: Long, val bytesTotal: Long) : DownloadProgress {
        val fraction: Float get() = if (bytesTotal > 0) bytesDone.toFloat() / bytesTotal else 0f
    }

    /** Hashing a multi-gigabyte file takes a while, so it gets its own visible phase. */
    data object Verifying : DownloadProgress

    data class Finished(val file: File) : DownloadProgress
}

/** Raised when a download cannot complete or arrives corrupt. */
class DownloadException(message: String) : Exception(message)

/**
 * Downloads model files, resumably and verified.
 *
 * Model files are gigabytes over a phone connection, so an interrupted download must
 * resume rather than restart: partial data is written to a `.part` file and continued with
 * a range request. On completion the SHA-256 is checked against what the Hub reported,
 * because a silently truncated GGUF fails later as an unexplained load error.
 */
@Singleton
class ModelDownloader @Inject constructor(
    private val httpClient: OkHttpClient,
    private val client: HuggingFaceClient,
    private val tokenSource: HubTokenSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun download(repoId: String, file: HubFile, destination: File): Flow<DownloadProgress> = flow {
        destination.parentFile?.mkdirs()

        if (destination.isFile && destination.length() == file.sizeBytes) {
            emit(DownloadProgress.Finished(destination))
            return@flow
        }

        val partial = File(destination.parentFile, destination.name + PARTIAL_SUFFIX)

        // A .part of exactly the right length is a download that finished but died before
        // the rename. Asking the server to resume from the end returns 416, so skip
        // straight to verification.
        if (partial.length() != file.sizeBytes || file.sizeBytes <= 0L) {
            transfer(repoId, file, partial)
        }

        if (file.sizeBytes > 0 && partial.length() != file.sizeBytes) {
            throw DownloadException(
                "The download ended early: got ${partial.length()} bytes of ${file.sizeBytes}. " +
                    "Try again. It will resume from where it stopped.",
            )
        }
        verify(file, partial)

        if (!partial.renameTo(destination)) {
            throw DownloadException("Could not move the finished download into place.")
        }
        emit(DownloadProgress.Finished(destination))
    }.flowOn(ioDispatcher)

    /** Streams the file into [partial], continuing from whatever is already there. */
    private suspend fun FlowCollector<DownloadProgress>.transfer(
        repoId: String,
        file: HubFile,
        partial: File,
    ) {
        val alreadyHave = if (partial.isFile) partial.length() else 0L
        val request = Request.Builder()
            .url(client.downloadUrl(repoId, file.path))
            .apply {
                withToken(tokenSource.token())
                // Resume where the last attempt stopped instead of re-downloading gigabytes.
                if (alreadyHave > 0) withRangeFrom(alreadyHave)
            }
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw response.toHubException(hasToken = tokenSource.token() != null)
            }
            // A server that ignores the range header restarts the file, so partial bytes
            // must be discarded rather than appended to. Trusting the 206 alone is not
            // enough: the range served has to actually start where we asked.
            val resuming = response.code == HubHttp.PARTIAL_CONTENT &&
                alreadyHave > 0 &&
                response.servesRangeFrom(alreadyHave)
            val total = file.sizeBytes.takeIf { it > 0 }
                ?: (response.body.contentLength() + if (resuming) alreadyHave else 0L)

            copyTo(partial, response.body.byteStream(), resuming, alreadyHave, total)
        }
    }

    private suspend fun FlowCollector<DownloadProgress>.copyTo(
        partial: File,
        input: InputStream,
        resuming: Boolean,
        alreadyHave: Long,
        total: Long,
    ) {
        val startAt = if (resuming) alreadyHave else 0L
        emit(DownloadProgress.Downloading(startAt, total))

        input.use { source ->
            FileOutputStream(partial, resuming).use { output ->
                pump(source, output, startAt, total)
            }
        }
    }

    /** Moves bytes across, reporting progress about once a megabyte. */
    private suspend fun FlowCollector<DownloadProgress>.pump(
        source: InputStream,
        output: FileOutputStream,
        startAt: Long,
        total: Long,
    ) {
        val buffer = ByteArray(BUFFER_BYTES)
        var written = startAt
        var lastReported = written
        var read = source.readInto(buffer)

        while (read >= 0) {
            output.write(buffer, 0, read)
            written += read

            if (written - lastReported >= PROGRESS_INTERVAL_BYTES) {
                lastReported = written
                emit(DownloadProgress.Downloading(written, total))
            }
            read = source.readInto(buffer)
        }
    }

    /** Reads a chunk, first checking that the download has not been cancelled. */
    private suspend fun InputStream.readInto(buffer: ByteArray): Int {
        currentCoroutineContext().ensureActive()
        return read(buffer)
    }

    /** True when the response's Content-Range actually begins at [offset]. */
    private fun Response.servesRangeFrom(offset: Long): Boolean {
        val header = header("Content-Range") ?: return false
        return header.substringAfter("bytes ", "").substringBefore('-') == offset.toString()
    }

    /**
     * Checks the finished file against the Hub's checksum.
     *
     * A silently truncated GGUF fails much later as an unexplained load error, so it is
     * better to fail here on the hash, with a message that says what to do.
     */
    private suspend fun FlowCollector<DownloadProgress>.verify(file: HubFile, partial: File) {
        val expected = file.sha256 ?: return
        emit(DownloadProgress.Verifying)

        if (!partial.sha256(ioDispatcher).equals(expected, ignoreCase = true)) {
            partial.delete()
            throw DownloadException(
                "The downloaded file does not match the checksum Hugging Face published. " +
                    "It was deleted; try again.",
            )
        }
    }

    private suspend fun File.sha256(dispatcher: CoroutineDispatcher): String =
        withContext(dispatcher) {
            val digest = MessageDigest.getInstance("SHA-256")
            inputStream().use { stream ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = stream.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

    private companion object {
        const val PARTIAL_SUFFIX = ".part"
        const val BUFFER_BYTES = 1 shl 16
        const val PROGRESS_INTERVAL_BYTES = 1L shl 20
    }
}
