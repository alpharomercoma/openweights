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

package io.github.alpharomercoma.openweights.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.core.common.model.GgufFileType
import io.github.alpharomercoma.openweights.core.common.model.GgufMetadata
import io.github.alpharomercoma.openweights.core.designsystem.component.Metric
import io.github.alpharomercoma.openweights.core.designsystem.theme.LocalIsDarkTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsColors
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius
import io.github.alpharomercoma.openweights.core.device.FitReport
import io.github.alpharomercoma.openweights.core.device.FitVerdict
import io.github.alpharomercoma.openweights.core.hub.HubFile
import java.util.Locale

/**
 * One downloadable file, and a straight answer about whether it runs here.
 *
 * Every on-device app can list models. Saying "this one will not load, and here is the
 * arithmetic" before someone spends a gigabyte of mobile data is the part that is worth
 * building, so the verdict leads and the numbers behind it are right underneath.
 */
@Composable
fun FitCard(inspected: InspectedFile, onDownload: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = inspected.file.path.substringAfterLast('/'),
                    style = MaterialTheme.typography.titleSmall,
                )
                Metric(formatBytes(inspected.file.sizeBytes))
            }

            when {
                inspected.isDownloaded -> Metric("On this device")
                inspected.isInspecting -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )

                inspected.fit?.verdict == FitVerdict.WONT_RUN -> Unit
                else -> Button(onClick = onDownload) { Text("Download") }
            }
        }

        inspected.inspectionError?.let { error ->
            Text(
                text = "Could not read this file's header: $error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        inspected.fit?.let { fit -> VerdictLine(fit) }
        inspected.metadata?.let { metadata ->
            Metric(metadata.summaryLine(inspected.file.quantizationLabel))
        }
        inspected.fit?.let { fit -> Metric(fit.memoryLine()) }
    }
}

@Composable
private fun VerdictLine(fit: FitReport) {
    val dark = LocalIsDarkTheme.current
    val (label, color) = when (fit.verdict) {
        FitVerdict.COMFORTABLE ->
            "Runs comfortably" to
                signal(OpenWeightsColors.SignalGood, OpenWeightsColors.PaperSignalGood, dark)

        FitVerdict.TIGHT ->
            "Runs, but tight — other apps may be closed" to
                signal(OpenWeightsColors.SignalPlain, OpenWeightsColors.PaperSignalPlain, dark)

        FitVerdict.WONT_RUN ->
            "Will not run at this context length" to
                signal(OpenWeightsColors.SignalPoor, OpenWeightsColors.PaperSignalPoor, dark)

        FitVerdict.NO_ROOM_TO_DOWNLOAD ->
            "Not enough free storage to download" to
                signal(OpenWeightsColors.SignalPoor, OpenWeightsColors.PaperSignalPoor, dark)
    }

    Text(text = label, style = MaterialTheme.typography.bodyMedium, color = color)
}

private fun signal(dark: Color, light: Color, isDark: Boolean) = if (isDark) dark else light

private fun GgufMetadata.summaryLine(quantizationFromName: String): String {
    val quantization = fileType.takeIf { it != GgufFileType.UNKNOWN }?.label ?: quantizationFromName
    return "$architecture · $blockCount blocks · $quantization · " +
        "trained to $trainingContextLength tokens"
}

private fun FitReport.memoryLine(): String =
    "needs ${formatBytes(requiredMemoryBytes)} of ${formatBytes(usableMemoryBytes)} usable " +
        "· KV cache ${formatBytes(kvCacheBytes)}" +
        (
            estimatedDecodeTokensPerSecond?.let {
                String.format(Locale.getDefault(), " · ~%.0f tok/s", it)
            } ?: ""
            )

private const val BYTES_PER_MIB = 1024.0 * 1024.0
private const val BYTES_PER_GIB = BYTES_PER_MIB * 1024.0

/** Sizes here are always storage or memory, so binary units are the honest ones. */
internal fun formatBytes(bytes: Long): String {
    val locale = Locale.getDefault()
    val gigabytes = bytes / BYTES_PER_GIB
    return if (gigabytes >= 1) {
        String.format(locale, "%.2f GB", gigabytes)
    } else {
        String.format(locale, "%.0f MB", bytes / BYTES_PER_MIB)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0D0F)
@Composable
private fun FitCardPreview() {
    OpenWeightsTheme(dynamicColor = false) {
        FitCard(
            inspected = InspectedFile(
                file = HubFile("LFM2.5-2.6B-Q4_K_M.gguf", 1_674_454_848, null),
                metadata = GgufMetadata(
                    architecture = "lfm2",
                    blockCount = 30,
                    embeddingLength = 2048,
                    headCount = 32,
                    keyValueHeadsPerLayer = List(30) { if (it % 3 == 2) 8 else 0 },
                    trainingContextLength = 128_000,
                    fileType = GgufFileType.Q4_K_M,
                    name = "LFM2.5-2.6B",
                ),
                fit = FitReport(
                    verdict = FitVerdict.COMFORTABLE,
                    requiredMemoryBytes = 2_200_000_000,
                    usableMemoryBytes = 7_600_000_000,
                    kvCacheBytes = 83_886_080,
                    estimatedDecodeTokensPerSecond = 18.0,
                    maxContextLength = 128_000,
                ),
            ),
            onDownload = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
