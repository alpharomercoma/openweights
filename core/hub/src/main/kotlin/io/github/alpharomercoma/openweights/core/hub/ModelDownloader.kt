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
import java.io.IOException
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

/**
 * Raised when a download cannot complete or arrives corrupt.
 *
 * [isRetryable] separates the two kinds. A stream that stopped short can be picked up from
 * the partial file and is worth another attempt; a file that failed its checksum will fail
 * it again, and retrying would spend gigabytes of someone's data allowance rediscovering
 * that. Deliberately not an IOException, so a caller that retries every IOException does
 * not quietly retry a corrupt download too.
 */
class DownloadException(
    message: String,
    val isRetryable: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)

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
    // This is a Flow state machine with intentionally explicit resume/provenance guards.
    @Suppress("CyclomaticComplexMethod")
    fun download(repoId: String, file: HubFile, destination: File): Flow<DownloadProgress> = flow {
        destination.parentFile?.mkdirs()
        val identity = downloadIdentity(repoId, file)
        val destinationSource = destination.sourceFile()

        // A file already there at the right length is normally the same file, and rehashing
        // several gigabytes to prove it would make every reopen of the models screen cost a
        // minute. So the length is trusted, unless the Hub published a checksum, in which
        // case it is the cheaper of two wrong answers to check.
        //
        // The case that matters is a publisher replacing a file with different bytes under
        // the same name and the same size. Without the hash, the app reports "Finished" and
        // keeps running the old weights forever, and nothing on screen ever disagrees.
        if (destination.isFile && adoptExisting(destination, destinationSource, file, identity)) {
            emit(DownloadProgress.Finished(destination))
            return@flow
        }

        val partial = File(destination.parentFile, destination.name + PARTIAL_SUFFIX)
        val partialSource = partial.sourceFile()
        if (partial.isFile && partialSource.readTextOrNull() != identity) {
            // A basename is not an identity. Without this, cancelling repo A/model.gguf and
            // starting repo B/model.gguf appends B to A and can install the hybrid when no
            // checksum was published.
            partial.delete()
        }
        partialSource.writeText(identity)

        // A partial longer than the file it is meant to become can never be resumed: the
        // range request starts past the end, the server answers 416, and every retry
        // repeats that forever with nothing on screen offering a way out. It happens when a
        // publisher replaces a file with a smaller one under the same name.
        if (file.sizeBytes > 0 && partial.length() > file.sizeBytes) {
            partial.delete()
        }

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
                isRetryable = true,
            )
        }
        verify(file, partial)

        // Commit provenance first. If this write fails, the resumable bytes remain under
        // .part; publishing the model first could leave a valid no-checksum download that
        // its own retry refuses because its identity was never durably recorded.
        destinationSource.writeText(identity)
        if (!partial.renameTo(destination)) {
            destinationSource.delete()
            throw DownloadException("Could not move the finished download into place.")
        }
        partialSource.delete()
        emit(DownloadProgress.Finished(destination))
    }.flowOn(ioDispatcher)

    /**
     * Decides what to do about a file already sitting at the destination.
     *
     * A file at the right length is normally the same file, and rehashing several
     * gigabytes to prove it would make every reopen of the models screen cost a minute. So
     * the length is trusted, unless the Hub published a checksum, in which case it is the
     * cheaper of two wrong answers to check. The case that matters is a publisher
     * replacing a file with different bytes under the same name: without the hash the app
     * reports "Finished" and keeps running the old weights forever.
     *
     * @return true when the file on disk is the one being asked for and the download is
     * already done. False means it has been cleared out of the way for a fresh transfer.
     */
    private suspend fun adoptExisting(
        destination: File,
        destinationSource: File,
        file: HubFile,
        identity: String,
    ): Boolean {
        val expected = file.sha256
        // Reading what is already there can fail on its own terms: a half-written file
        // from a killed process, storage that has gone bad, a path the app can no longer
        // open. That is not the transfer failing, and no number of retries can change it —
        // the same unreadable bytes are there next time.
        //
        // Left as a bare IOException it was classified as transient by
        // `ModelDownloadWorker`, so the work went back on the queue and sat there:
        // observed live as **"Downloading 0%" through four doomed attempts with a thirty
        // second backoff between each**, no bytes moving, nothing on screen saying why,
        // and the only cancel on a screen the user had navigated away from. Named
        // unretryable here, it fails once and says what to do.
        val (hashMatches, sourceMatches) = try {
            val hashed = expected != null &&
                destination.length() == file.sizeBytes &&
                destination.sha256(ioDispatcher).equals(expected, true)
            hashed to (destinationSource.readTextOrNull() == identity)
        } catch (failure: IOException) {
            // Caught by type rather than with runCatching, so a cancelled download stays
            // cancelled: CancellationException is not an IOException and passes straight
            // through, which is the behaviour WorkManager relies on.
            throw DownloadException(
                "${destination.name} is already on this device but could not be read " +
                    "(${failure.message ?: "no reason given"}). Delete it in Models and " +
                    "download it again.",
                isRetryable = false,
                cause = failure,
            )
        }

        if (destination.length() == file.sizeBytes && (hashMatches || sourceMatches)) {
            destinationSource.writeText(identity)
            return true
        }
        // Said as what is known rather than as an accusation. Without a published checksum
        // and without a recorded source there is no way to tell whether this is the same
        // file under the same name or somebody else's, and the two have very different
        // costs: adopting the wrong one runs the wrong weights forever, and deleting the
        // right one is gigabytes over a phone connection. So neither is chosen here, and
        // the person who knows which it is decides.
        if (expected == null && !sourceMatches) {
            throw DownloadException(
                "${destination.name} is already on this device and this repository " +
                    "publishes no checksum, so it cannot be confirmed as the same file. " +
                    "Delete it in Models to download this one.",
            )
        }
        // Different bytes under the same name. Removed rather than resumed: a resume would
        // append to content that is already wrong.
        destination.delete()
        destinationSource.delete()
        return false
    }

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
                if (response.saysAlreadyComplete(alreadyHave)) return
                throw response.toHubException(hasToken = tokenSource.token() != null)
            }
            // A server that ignores the range header restarts the file, so partial bytes
            // must be discarded rather than appended to. Trusting the 206 alone is not
            // enough: the range served has to actually start where we asked.
            val resuming = response.code == HubHttp.PARTIAL_CONTENT &&
                alreadyHave > 0 &&
                response.servesRangeFrom(alreadyHave)
            val total = file.sizeBytes.takeIf { it > 0 }
                ?: response.body.contentLength().takeIf { it >= 0L }
                    ?.saturatedPlus(if (resuming) alreadyHave else 0L)
                ?: -1L

            copyTo(partial, response.body.byteStream(), resuming, alreadyHave, total)
        }
    }

    /**
     * A 416 for a file we already hold all of, which is a finished download rather than a
     * failure: the server cannot serve a range that starts at the end of the file.
     */
    private fun Response.saysAlreadyComplete(alreadyHave: Long): Boolean {
        if (code != HubHttp.RANGE_NOT_SATISFIABLE || alreadyHave <= 0) return false
        val totalFromRange = header("Content-Range")
            ?.substringAfter("*/", "")
            ?.toLongOrNull()
        // A 416 that does not say how long the file is says nothing about whether this one
        // is complete, and reading it as complete verified and installed a partial whenever
        // the size was unknown as well.
        return totalFromRange != null && alreadyHave >= totalFromRange
    }

    private suspend fun FlowCollector<DownloadProgress>.copyTo(
        partial: File,
        input: InputStream,
        resuming: Boolean,
        alreadyHave: Long,
        total: Long,
    ) {
        val startAt = if (resuming) alreadyHave else 0L
        ensureStorageFor(partial, total, startAt)
        emit(DownloadProgress.Downloading(startAt, total))

        val ceiling = if (total > 0) {
            total
        } else {
            val free = partial.parentFile?.usableSpace ?: 0L
            if (free <= STORAGE_RESERVE_BYTES) {
                throw DownloadException("There is not enough free storage to download this file.")
            }
            minOf(
                MAX_UNKNOWN_DOWNLOAD_BYTES,
                startAt.saturatedPlus(free - STORAGE_RESERVE_BYTES),
            )
        }

        input.use { source ->
            FileOutputStream(partial, resuming).use { output ->
                pump(source, output, partial, startAt, total, ceiling)
            }
        }
    }

    /** Refuses a known-size transfer before it can consume the device's last usable space. */
    private fun ensureStorageFor(partial: File, total: Long, bytesKept: Long) {
        if (total <= 0L) return
        val remaining = (total - bytesKept).coerceAtLeast(0L)
        val free = partial.parentFile?.usableSpace ?: return
        if (free < remaining.saturatedPlus(STORAGE_RESERVE_BYTES)) {
            throw DownloadException(
                "There is not enough free storage to download this file while keeping " +
                    "${STORAGE_RESERVE_BYTES / BYTES_PER_MEBIBYTE} MB free for the device.",
            )
        }
    }

    /** Moves bytes across, reporting progress about once a megabyte. */
    private suspend fun FlowCollector<DownloadProgress>.pump(
        source: InputStream,
        output: FileOutputStream,
        partial: File,
        startAt: Long,
        total: Long,
        ceiling: Long,
    ) {
        val buffer = ByteArray(BUFFER_BYTES)
        var written = startAt
        var lastReported = written
        var lastSpaceCheck = written
        var read = source.readInto(buffer)

        while (read >= 0) {
            // Stopped at the advertised size rather than at end of stream. A server that
            // keeps sending filled the disk, because the length was only checked once the
            // transfer had finished. Zero means the size was not published, which is the
            // one case there is nothing to check against.
            if (written > ceiling - read) {
                throw DownloadException(
                    if (total > 0) {
                        "The server sent more than the $total bytes this file claims to be."
                    } else {
                        "The server sent an unbounded file, so the download was stopped " +
                            "before it could fill the device."
                    },
                )
            }
            output.write(buffer, 0, read)
            written += read

            if (written - lastReported >= PROGRESS_INTERVAL_BYTES) {
                lastReported = written
                emit(DownloadProgress.Downloading(written, total))
            }
            if (written - lastSpaceCheck >= PROGRESS_INTERVAL_BYTES) {
                lastSpaceCheck = written
                val free = partial.parentFile?.usableSpace ?: Long.MAX_VALUE
                if (free < STORAGE_RESERVE_BYTES.saturatedPlus(PROGRESS_INTERVAL_BYTES)) {
                    throw DownloadException(
                        "The download was stopped before it used the device's storage reserve.",
                    )
                }
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
        const val PARTIAL_SUFFIX = DOWNLOAD_PARTIAL_SUFFIX
        const val BUFFER_BYTES = 1 shl 16
        const val PROGRESS_INTERVAL_BYTES = 1L shl 20
        const val BYTES_PER_MEBIBYTE = 1024L * 1024L
        const val STORAGE_RESERVE_BYTES = 256L * 1024 * 1024
        const val MAX_UNKNOWN_DOWNLOAD_BYTES = 32L * 1024 * 1024 * 1024
    }
}

/**
 * What an unfinished download is called on disk, until it is renamed into place.
 *
 * Public because the presence of one of these files is the cheapest true answer to "is
 * anything still downloading", and the model store asks that before opening a model
 * nobody has requested yet.
 */
const val DOWNLOAD_PARTIAL_SUFFIX = ".part"

/** Stable provenance for files whose repository-controlled basenames may collide. */
internal fun downloadIdentity(repoId: String, file: HubFile): String =
    listOf(repoId, file.path, file.sizeBytes.toString(), file.sha256.orEmpty()).joinToString("\n")

private fun File.sourceFile(): File = File(parentFile, "$name.source")

private fun File.readTextOrNull(): String? = runCatching {
    takeIf(File::isFile)?.readText()
}.getOrNull()

private fun Long.saturatedPlus(other: Long): Long =
    if (this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other
