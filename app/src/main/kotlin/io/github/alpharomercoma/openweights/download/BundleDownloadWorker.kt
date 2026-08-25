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
import io.github.alpharomercoma.openweights.core.generation.GenerationCatalog
import io.github.alpharomercoma.openweights.core.hub.DownloadException
import io.github.alpharomercoma.openweights.core.hub.DownloadProgress
import io.github.alpharomercoma.openweights.core.hub.HubFile
import io.github.alpharomercoma.openweights.core.hub.ModelDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import java.io.File
import java.io.IOException

/**
 * Downloads a multi-file generation bundle (such as MNN Stable Diffusion or Supertonic TTS)
 * in the background, maintaining overall bundle progress, resumability, and atomicity.
 */
@HiltWorker
class BundleDownloadWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val downloader: ModelDownloader,
) : CoroutineWorker(context, params) {
    private val bundleId = inputData.getString(KEY_BUNDLE_ID).orEmpty()
    private val repoId = inputData.getString(KEY_REPO_ID).orEmpty()
    private val targetDir = File(inputData.getString(KEY_TARGET_DIR).orEmpty())
    private val spec = GenerationCatalog.findById(bundleId)
    private val label = spec?.displayName ?: targetDir.name
    private val totalSizeBytes = spec?.totalSizeBytes ?: 0L

    private var lastPercent = -1

    private val notificationId =
        id.hashCode().let { if (it == REPLY_NOTIFICATION_ID) it + 1 else it }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(bytesDone = 0, bytesTotal = totalSizeBytes, isVerifying = false)

    override suspend fun doWork(): Result {
        if (bundleId.isEmpty() || repoId.isEmpty() || targetDir.path.isEmpty() || spec == null) {
            return Result.failure(
                errorData("The bundle download was queued without a valid specification."),
            )
        }

        runCatching { setForeground(getForegroundInfo()) }

        targetDir.mkdirs()
        var completedFilesBytes = 0L

        for (fileSpec in spec.files) {
            val destination = File(targetDir, fileSpec.name)

            val assetPath = fileSpec.assetPath
            if (assetPath != null) {
                val assetFailure = copyFromAssets(assetPath, destination)
                if (assetFailure != null) {
                    return Result.failure(errorData(assetFailure.message ?: "Could not install ${fileSpec.name}."))
                }
                completedFilesBytes += destination.length().coerceAtLeast(fileSpec.sizeBytes)
                reportProgress(completedFilesBytes.coerceAtMost(totalSizeBytes), totalSizeBytes, isVerifying = false)
                continue
            }

            val hubFile = HubFile(
                path = fileSpec.remotePath,
                sizeBytes = fileSpec.sizeBytes,
                sha256 = fileSpec.sha256,
            )

            var fileFailure: Throwable? = null

            downloader.download(repoId, hubFile, destination)
                .catch {
                    if (it is CancellationException) throw it
                    fileFailure = it
                }
                .collect { progress ->
                    when (progress) {
                        is DownloadProgress.Downloading -> {
                            val totalDone = (completedFilesBytes + progress.bytesDone)
                                .coerceAtMost(totalSizeBytes)
                            reportProgress(totalDone, totalSizeBytes, isVerifying = false)
                        }
                        DownloadProgress.Verifying -> {
                            reportProgress(completedFilesBytes, totalSizeBytes, isVerifying = true)
                        }
                        is DownloadProgress.Finished -> Unit
                    }
                }

            fileFailure?.let { error ->
                val retryable =
                    error is IOException || (error as? DownloadException)?.isRetryable == true
                return if (retryable && runAttemptCount < MAX_ATTEMPTS) {
                    Result.retry()
                } else {
                    Result.failure(
                        errorData(error.message ?: "Could not download ${fileSpec.name}."),
                    )
                }
            }

            completedFilesBytes += destination.length().coerceAtLeast(fileSpec.sizeBytes)
        }

        // Write completion sentinel file once all files are in place.
        val sentinel = File(targetDir, SENTINEL_COMPLETE)
        sentinel.writeText("complete")

        reportProgress(totalSizeBytes, totalSizeBytes, isVerifying = false)
        return Result.success()
    }

    private suspend fun reportProgress(bytesDone: Long, bytesTotal: Long, isVerifying: Boolean) {
        setProgress(
            workDataOf(
                KEY_BYTES_DONE to bytesDone,
                KEY_BYTES_TOTAL to bytesTotal,
                KEY_VERIFYING to isVerifying,
            ),
        )
        val percent = percentOf(bytesDone, bytesTotal)
        if (percent != lastPercent) {
            lastPercent = percent
            runCatching {
                setForeground(foregroundInfo(bytesDone, bytesTotal, isVerifying))
            }
        }
    }

    private fun foregroundInfo(
        bytesDone: Long,
        bytesTotal: Long,
        isVerifying: Boolean,
    ): ForegroundInfo {
        ensureChannel()

        val text = when {
            isVerifying -> "Checking bundle integrity"
            bytesTotal > 0 -> "${formatBytes(bytesDone)} of ${formatBytes(bytesTotal)}"
            else -> "Downloading bundle"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(label)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(PROGRESS_MAX, percentOf(bytesDone, bytesTotal), isVerifying)
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
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress while a model or bundle is being fetched"
            },
        )
    }

    private fun errorData(message: String): Data = workDataOf(KEY_ERROR to message)

    /** Copies a file the app ships itself rather than fetches, e.g. [BundleFileSpec.assetPath]. */
    private fun copyFromAssets(assetPath: String, destination: File): Throwable? = runCatching {
        if (destination.isFile && destination.length() > 0L) return@runCatching
        val temp = File(destination.parentFile, destination.name + ".part")
        context.assets.open(assetPath).use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        }
        if (!temp.renameTo(destination)) {
            temp.delete()
            error("Could not move ${destination.name} into place.")
        }
    }.exceptionOrNull()

    companion object {
        const val KEY_BUNDLE_ID = "bundleId"
        const val KEY_REPO_ID = "repoId"
        const val KEY_TARGET_DIR = "targetDir"

        const val KEY_BYTES_DONE = "bytesDone"
        const val KEY_BYTES_TOTAL = "bytesTotal"
        const val KEY_VERIFYING = "verifying"
        const val KEY_ERROR = "error"

        const val SENTINEL_COMPLETE = ".complete"

        private const val CHANNEL = "downloads"
        private const val PROGRESS_MAX = 100
        private const val MAX_ATTEMPTS = 5
        private const val REPLY_NOTIFICATION_ID = 1

        internal fun percentOf(done: Long, total: Long): Int =
            if (total > 0) ((done * PROGRESS_MAX) / total).toInt().coerceIn(0, PROGRESS_MAX) else 0
    }
}
