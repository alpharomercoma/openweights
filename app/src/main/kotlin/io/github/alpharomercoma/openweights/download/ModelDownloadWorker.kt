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

package io.github.alpharomercoma.openweights.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.alpharomercoma.openweights.R
import io.github.alpharomercoma.openweights.core.designsystem.component.formatBytes
import io.github.alpharomercoma.openweights.core.hub.DownloadException
import io.github.alpharomercoma.openweights.core.hub.DownloadProgress
import io.github.alpharomercoma.openweights.core.hub.HubException
import io.github.alpharomercoma.openweights.core.hub.HubFile
import io.github.alpharomercoma.openweights.core.hub.ModelDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import java.io.File
import java.io.IOException

/**
 * Fetches one model file, and keeps fetching it once the app is off screen.
 *
 * A model is one to eight gigabytes. On a phone connection that is minutes at best, and
 * nobody watches a progress bar for minutes: they switch apps, and Android is then free to
 * kill the process. In a view model scope that ends the download, silently, with the user
 * returning later to a bar that has not moved and no explanation.
 *
 * WorkManager survives that. The work is recorded in its own database before this class is
 * ever constructed, so process death pauses the download rather than ending it, and the
 * `.part` file [ModelDownloader] leaves behind means the retry resumes rather than restarts.
 * While it runs it holds a `dataSync` foreground service, because that is the honest
 * description of what it is doing and the only way Android will let it keep the socket open
 * with the app in the background.
 */
@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val downloader: ModelDownloader,
    private val arrivals: ModelArrivals,
) : CoroutineWorker(context, params) {
    private val repoId = inputData.getString(KEY_REPO_ID).orEmpty()
    private val path = inputData.getString(KEY_PATH).orEmpty()
    private val destination = File(inputData.getString(KEY_DESTINATION).orEmpty())
    private val sizeBytes = inputData.getLong(KEY_SIZE_BYTES, 0L)
    private val label = destination.name.removeSuffix(".gguf")

    /** The last whole percent shown, so the notification is rebuilt about a hundred times. */
    private var lastPercent = -1

    /**
     * One notification per unit of work, kept clear of the reply notifier's id of 1.
     *
     * Two downloads run at once when a multimodal model brings its projector, and sharing
     * an id would have the second overwrite the first's progress. The work id is used
     * rather than the filename because a filename hash was folded to sixteen bits, which is
     * few enough for two ordinary model names to collide.
     */
    private val notificationId =
        id.hashCode().let { if (it == REPLY_NOTIFICATION_ID) it + 1 else it }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(bytesDone = 0, bytesTotal = sizeBytes, isVerifying = false)

    override suspend fun doWork(): Result {
        if (repoId.isEmpty() || path.isEmpty() || destination.path.isEmpty()) {
            return Result.failure(errorData("The download was queued without a source."))
        }

        // Documented to throw when the app is not allowed to start a foreground service, and
        // that is survivable: the work still runs as an ordinary job, it just loses the
        // notification and the promise of not being stopped. Failing the download over a
        // missing notification would be the worse trade.
        runCatching { setForeground(getForegroundInfo()) }

        var failure: Throwable? = null
        val file = HubFile(path, sizeBytes, inputData.getString(KEY_SHA256))

        downloader.download(repoId, file, destination)
            .catch {
                // WorkManager cancellation must remain cancellation. Turning it into a
                // terminal failure leaves a cancelled row visible and can schedule a retry
                // after the user explicitly stopped a multi-gigabyte transfer.
                if (it is CancellationException) throw it
                failure = it
            }
            .collect { progress -> report(progress) }

        val error = failure ?: return Result.success()

        val retryable = error.isWorthRetrying()
        val message = error.message ?: error::class.simpleName ?: "unknown failure"
        Log.w(
            "OpenWeights",
            "model download failed: file=${destination.name}, " +
                "attempt=${runAttemptCount + 1}, retries=$runAttemptCount/$MAX_RETRIES, " +
                "retryable=$retryable, reason=$message",
            error,
        )
        return if (retryable && runAttemptCount < MAX_RETRIES) {
            // WorkManager keeps progress while it waits in backoff. Preserve the reason there
            // because outputData is only available after the work is finally failed, and a
            // row that says merely "retrying" makes a network error indistinguishable from a
            // storage error for the entire wait.
            setProgress(
                workDataOf(
                    KEY_ERROR to message,
                    KEY_BYTES_DONE to 0L,
                    KEY_BYTES_TOTAL to sizeBytes,
                ),
            )
            Result.retry()
        } else {
            Result.failure(errorData(message))
        }
    }

    private suspend fun report(progress: DownloadProgress) {
        when (progress) {
            is DownloadProgress.Downloading -> {
                setProgress(
                    workDataOf(
                        KEY_BYTES_DONE to progress.bytesDone,
                        KEY_BYTES_TOTAL to progress.bytesTotal,
                    ),
                )
                val percent = percentOf(progress.bytesDone, progress.bytesTotal)
                if (percent != lastPercent) {
                    lastPercent = percent
                    runCatching {
                        setForeground(
                            foregroundInfo(progress.bytesDone, progress.bytesTotal, false),
                        )
                    }
                }
            }

            DownloadProgress.Verifying -> {
                setProgress(workDataOf(KEY_VERIFYING to true))
                runCatching { setForeground(foregroundInfo(sizeBytes, sizeBytes, true)) }
            }

            // The one moment the app knows a model is complete on disk and nobody is
            // waiting on it. What the first turn costs is decided here: warmed now, while
            // the user is still reading the models screen, or paid in front of them the
            // first time they send anything. See [ModelArrivals].
            is DownloadProgress.Finished -> arrivals.announce(progress.file)
        }
    }

    private fun foregroundInfo(
        bytesDone: Long,
        bytesTotal: Long,
        isVerifying: Boolean,
    ): ForegroundInfo {
        ensureChannel()

        val text = when {
            // Hashing several gigabytes is slow enough that a bar frozen at full would read
            // as a hang. Saying what it is doing costs one line and answers the question.
            isVerifying -> "Checking the file is intact"
            bytesTotal > 0 -> "${formatBytes(bytesDone)} of ${formatBytes(bytesTotal)}"
            else -> "Downloading"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(label)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(PROGRESS_MAX, percentOf(bytesDone, bytesTotal), isVerifying)
            // The one control a download notification has to offer. Without it the only way
            // to stop a download from outside the app is to force stop the app.
            .addAction(
                0,
                "Cancel",
                WorkManager.getInstance(context).createCancelPendingIntent(id),
            )
            .build()

        return ForegroundInfo(
            notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun ensureChannel() {
        val manager = context.getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            // Low, not default: a download reports for minutes, and a channel that makes a
            // sound every time it does is one the user turns off, taking the progress bar
            // with it.
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress while a model is being fetched"
            },
        )
    }

    private fun errorData(message: String): Data = workDataOf(KEY_ERROR to message)

    companion object {
        const val KEY_REPO_ID = "repoId"
        const val KEY_PATH = "path"
        const val KEY_DESTINATION = "destination"
        const val KEY_SIZE_BYTES = "sizeBytes"
        const val KEY_SHA256 = "sha256"

        const val KEY_BYTES_DONE = "bytesDone"
        const val KEY_BYTES_TOTAL = "bytesTotal"
        const val KEY_VERIFYING = "verifying"
        const val KEY_ERROR = "error"

        /** Every model download carries this, so one query watches all of them. */
        const val TAG_DOWNLOAD = "model-download"

        /**
         * What the row on screen needs before the worker has run once.
         *
         * Tags rather than a table of our own: they are written with the request, they come
         * back with every [androidx.work.WorkInfo], and they outlive the process, which is
         * the whole point of moving the download out of the view model.
         */
        const val TAG_REPO = "repo:"
        const val TAG_PATH = "path:"
        const val TAG_KEY = "key:"
        const val TAG_SIZE = "size:"
        const val TAG_SHA256 = "sha256:"

        private const val CHANNEL = "downloads"
        private const val PROGRESS_MAX = 100

        /** Maximum automatic retries after the first transfer attempt. */
        const val MAX_RETRIES = 5

        /** What ReplyNotifier posts on, which this must never land on. */
        private const val REPLY_NOTIFICATION_ID = 1

        internal fun percentOf(done: Long, total: Long): Int =
            if (total > 0) ((done * PROGRESS_MAX) / total).toInt().coerceIn(0, PROGRESS_MAX) else 0
    }
}

/**
 * Whether the same download is worth putting back on the queue.
 *
 * A dropped connection is, because the partial file means the attempt resumes where it
 * stopped. So is a stream that stopped short, which arrives as a retryable
 * [DownloadException]: its own message promises the download will resume, and treating it
 * as terminal made that message a lie the user had to act on by hand.
 *
 * And so is a Hub that said it was busy. A rate limit or a 5xx from the CDN is the most
 * retryable thing that happens here and used to be the least: it arrived as a plain
 * [HubException], matched nothing above, and failed at the first attempt, while a dropped
 * socket (a strictly smaller problem) got all five. Permission, a missing file and a
 * checksum that did not match stay terminal: those answer the same however often they are
 * asked, and the five-attempt countdown in front of them is only a wait before the same
 * sentence.
 */
internal fun Throwable.isWorthRetrying(): Boolean = when (this) {
    is IOException -> true
    is DownloadException -> isRetryable
    is HubException -> isRetryable
    else -> false
}
