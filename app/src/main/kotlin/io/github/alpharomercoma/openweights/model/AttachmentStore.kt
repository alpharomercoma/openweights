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

package io.github.alpharomercoma.openweights.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.graphics.scale
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.alpharomercoma.openweights.core.common.model.MediaKind
import io.github.alpharomercoma.openweights.core.common.model.MessagePart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.Reader
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A text document waiting in the composer.
 *
 * Held as characters rather than as a file, because that is all it ever becomes: unlike an
 * image, which needs a projector and a path on disk, a document is read into the question.
 */
data class StagedDocument(val name: String, val text: String, val wasTrimmed: Boolean)

/** Reads at most [limit] characters plus one character that proves more content exists. */
internal fun Reader.readDocumentWindow(limit: Int): String {
    val bounded = limit.coerceAtLeast(0)
    val buffer = CharArray(bounded + 1)
    var filled = 0
    while (filled < buffer.size) {
        val read = read(buffer, filled, buffer.size - filled)
        if (read <= 0) return String(buffer, 0, filled)
        filled += read
    }
    return String(buffer, 0, filled)
}

/**
 * Keeps attachments alongside the conversations that refer to them.
 *
 * Picked media arrives as a `content://` URI whose read permission lasts only as long as
 * the activity that was granted it. A conversation outlives that by months, and the
 * projector wants a plain file to read, so every attachment is copied into app-private
 * storage on the way in. Uninstalling the app takes them with it, which is the behaviour a
 * local-only app should have.
 */
@Singleton
class AttachmentStore @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val directory: File
        get() = File(context.filesDir, DIRECTORY).apply { mkdirs() }

    /**
     * Copies [uri] into storage and describes it as message parts.
     *
     * Usually one part. A video becomes several, one per sampled frame, because the model
     * reads pictures and not footage.
     *
     * Returns an empty list when the file cannot be read, which is the normal outcome of a
     * picker result whose permission was already revoked.
     */
    suspend fun store(uri: Uri): List<MessagePart.File> = withContext(Dispatchers.IO) {
        val mediaType = context.contentResolver.getType(uri) ?: FALLBACK_MEDIA_TYPE
        val displayName = displayName(uri)

        if (MediaKind.of(mediaType) == MediaKind.VIDEO) {
            return@withContext runCatching { sampleFrames(uri, displayName) }
                .onFailure { Log.w(TAG, "could not read frames from the chosen video", it) }
                .getOrDefault(emptyList())
        }

        val target = File(directory, "${UUID.randomUUID()}${extensionFor(displayName, mediaType)}")
        val copied = runCatching {
            if (MediaKind.of(mediaType) == MediaKind.IMAGE) {
                copyImageDownscaled(uri, target)
            } else {
                copyVerbatim(uri, target)
            }
        }.getOrDefault(false)

        if (!copied) {
            target.delete()
            return@withContext emptyList()
        }
        listOf(MessagePart.File(target.absolutePath, mediaType, displayName))
    }

    /**
     * Turns a video into a handful of stills.
     *
     * libmtmd can nominally read video, but only by shelling out to an `ffmpeg` binary in
     * PATH, something an Android app cannot provide, so the frames are extracted here
     * instead and sent as ordinary pictures.
     *
     * The frame count is the whole design. Each frame is a full vision encode, which on a
     * phone is measured in seconds, so a handful of evenly spaced frames is the difference
     * between "what happens in this clip" answered in a minute and answered in ten.
     */
    private fun sampleFrames(uri: Uri, displayName: String?): List<MessagePart.File> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: return emptyList()

            (0 until VIDEO_FRAMES).mapNotNull { index ->
                // Sampled at the midpoint of each equal slice, so the first frame is not
                // the black one videos so often open on.
                val atMs = durationMs * (2 * index + 1) / (2 * VIDEO_FRAMES)
                // OPTION_CLOSEST, not OPTION_CLOSEST_SYNC: sync frames are keyframes, and a
                // short clip can have exactly one, which makes every request return the
                // same picture. Decoding to the requested time costs more and is the only
                // way the frames actually differ.
                val frame = retriever.getScaledFrameAtTime(
                    atMs * MICROS_PER_MILLI,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    MAX_IMAGE_EDGE,
                    MAX_IMAGE_EDGE,
                ) ?: return@mapNotNull null

                val target = File(directory, "${UUID.randomUUID()}.jpg")
                try {
                    target.outputStream().use { output ->
                        frame.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
                    }
                } finally {
                    frame.recycle()
                }
                MessagePart.File(
                    path = target.absolutePath,
                    mediaType = "image/jpeg",
                    name = "${displayName ?: "Video"} · frame ${index + 1}",
                )
            }
        } finally {
            retriever.release()
        }
    }

    /** Deletes the files behind attachments that are no longer referenced. */
    suspend fun discard(attachments: List<MessagePart.File>) = withContext(Dispatchers.IO) {
        val ours = directory
        attachments.forEach { attachment ->
            val file = File(attachment.path)
            // Only ever delete inside our own folder: a path from an older build, or from a
            // conversation exported elsewhere, could point anywhere, and this runs with no
            // confirmation behind it.
            if (file.parentFile == ours) file.delete()
        }
    }

    suspend fun discard(attachment: MessagePart.File) = discard(listOf(attachment))

    /** Gives a branched conversation files it can delete independently of its source. */
    suspend fun duplicate(attachments: List<MessagePart.File>): List<MessagePart.File> =
        withContext(Dispatchers.IO) {
            val made = mutableListOf<File>()
            try {
                attachments.map { attachment ->
                    val source = File(attachment.path)
                    check(source.parentFile == directory && source.isFile) {
                        "attachment is no longer available"
                    }
                    val suffix = source.extension
                        .takeIf { it.isNotEmpty() }
                        ?.let { ".$it" }
                        .orEmpty()
                    val target = File(directory, "${UUID.randomUUID()}$suffix")
                    source.copyTo(target, overwrite = false)
                    made += target
                    attachment.copy(path = target.absolutePath)
                }
            } catch (failure: Throwable) {
                made.forEach(File::delete)
                throw failure
            }
        }

    private fun copyVerbatim(uri: Uri, target: File): Boolean =
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use(input::copyTo)
            true
        } ?: false

    /**
     * Copies an image, shrinking it to something a phone can actually encode.
     *
     * A 12-megapixel photo is split into hundreds of patches by the vision encoder, and
     * prompt processing is the slowest part of a local reply. A full-resolution photo can
     * take minutes where a downscaled one takes seconds. Models are trained on inputs
     * around this size anyway, so the detail being dropped is detail the encoder would have
     * discarded itself.
     */
    private fun copyImageDownscaled(uri: Uri, target: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }

        val longestEdge = max(bounds.outWidth, bounds.outHeight)
        if (longestEdge <= 0) return false
        if (longestEdge <= MAX_IMAGE_EDGE) return copyVerbatim(uri, target)

        // inSampleSize halves in powers of two, which is the cheap path in the decoder;
        // the exact size is reached by scaling the result afterwards.
        val options = BitmapFactory.Options().apply {
            inSampleSize = Integer.highestOneBit(longestEdge / MAX_IMAGE_EDGE)
        }
        val decoded = context.contentResolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return false

        val scale = MAX_IMAGE_EDGE.toFloat() / max(decoded.width, decoded.height)
        val resized = if (scale < 1f) {
            decoded.scale(
                (decoded.width * scale).roundToInt().coerceAtLeast(1),
                (decoded.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
        } else {
            decoded
        }

        // Bitmaps are native allocations that the collector frees late. Releasing them in
        // a finally block matters most on the failure path, which is exactly when memory
        // is already scarce.
        try {
            target.outputStream().use { output ->
                resized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            }
        } finally {
            if (resized !== decoded) resized.recycle()
            decoded.recycle()
        }
        return true
    }

    /**
     * Reads a document as text, for models that cannot see.
     *
     * Nothing is copied and nothing is stored. A document is not media: it becomes part of
     * the sentence the model is asked, so the only thing worth keeping is its characters,
     * and the file it came from can stay where it is.
     *
     * Trimmed to [limit] here rather than at the point of sending. A phone's context window
     * is four thousand tokens, roughly sixteen thousand characters, and the whole
     * conversation and the reply have to fit beside whatever this returns. Cutting it
     * silently would be worse than not attaching it, so the caller is told.
     */
    suspend fun readDocument(uri: Uri, limit: Int): StagedDocument? = withContext(Dispatchers.IO) {
        val bounded = limit.coerceIn(0, MAX_DOCUMENT_CHARS)
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader().use { it.readDocumentWindow(bounded) }
            }
        }.getOrNull() ?: return@withContext null

        if (text.isBlank()) return@withContext null
        StagedDocument(
            name = displayName(uri) ?: "document",
            text = text.take(bounded),
            wasTrimmed = text.length > bounded,
        )
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment

    /**
     * The extension to save under.
     *
     * Downscaled images are re-encoded as JPEG, so their original extension would be a lie
     *, and libmtmd sniffs the contents rather than trusting the name, so a wrong one would
     * be confusing without being harmful.
     */
    private fun extensionFor(displayName: String?, mediaType: String): String = when {
        MediaKind.of(mediaType) == MediaKind.IMAGE -> ".jpg"
        displayName?.substringAfterLast('.', "")?.isNotEmpty() == true ->
            ".${displayName.substringAfterLast('.')}"

        else -> ""
    }

    /**
     * A private file for the camera app to write into.
     *
     * Older captures are swept first. The camera writes here directly and [store] copies
     * out of it, so without this the originals would accumulate: including the ones from
     * captures the user cancelled, which nothing else would ever hear about.
     */
    fun newCaptureUri(): Uri {
        val captures = File(context.filesDir, CAPTURES).apply { mkdirs() }
        captures.listFiles()?.forEach { it.delete() }
        val target = File(captures, "capture-${UUID.randomUUID()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.files", target)
    }

    private companion object {
        const val TAG = "AttachmentStore"
        const val CAPTURES = "captures"
        const val DIRECTORY = "attachments"
        const val FALLBACK_MEDIA_TYPE = "application/octet-stream"
        const val MAX_DOCUMENT_CHARS = 1_000_000

        /** Longest edge in pixels. Above this, prompt processing dominates the reply. */
        const val MAX_IMAGE_EDGE = 1024
        const val JPEG_QUALITY = 90

        /**
         * Frames taken from a video.
         *
         * Four, because each one costs a full vision encode: around thirteen seconds on
         * the dev device. Eight would double the wait for a marginal gain in coverage.
         */
        const val VIDEO_FRAMES = 4
        const val MICROS_PER_MILLI = 1000L
    }
}
