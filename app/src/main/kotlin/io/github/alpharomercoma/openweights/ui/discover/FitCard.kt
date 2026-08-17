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
import io.github.alpharomercoma.openweights.core.designsystem.component.AccentButton
import io.github.alpharomercoma.openweights.core.designsystem.component.Caption
import io.github.alpharomercoma.openweights.core.designsystem.component.Metric
import io.github.alpharomercoma.openweights.core.designsystem.component.formatBytes
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
 * arithmetic" before someone spends a gigabyte of mobile data is the part that matters
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
                inspected.isDownloaded -> Caption("On this device")
                inspected.isInspecting -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )

                inspected.fit?.verdict == FitVerdict.WONT_RUN -> Unit
                else -> AccentButton(onClick = onDownload) { Text("Download") }
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
        // One line each, and each short enough to be one line. Both of these used to run
        // past the card and wrap, which on a list of eight files turned a scannable column
        // into a wall: the eye cannot compare quantisations down a page whose rows are
        // different heights.
        inspected.metadata?.let { metadata ->
            Metric(metadata.summaryLine(inspected.file.quantizationLabel), maxLines = 1)
        }
        inspected.fit?.let { fit -> Metric(fit.memoryLine(), maxLines = 1) }
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
            "Runs, but tight: other apps may be closed" to
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

/**
 * What this particular file is, in the two facts that decide between one file and the next.
 *
 * It used to open with the architecture and the block count, on a screen whose title is
 * already the repository: "lfm2" under a heading saying LFM2.5 is the same word twice, and
 * "16 blocks" is a number nobody can act on, since a user comparing Q4_K_M against Q5_K_M is
 * not weighing layer counts. What is left is the quantisation, which is the actual choice,
 * and how much the model can hold, which used to read "trained to 128000 tokens" and is a
 * sentence about the training run rather than about what you get.
 */
private fun GgufMetadata.summaryLine(quantizationFromName: String): String {
    val quantization = fileType.takeIf { it != GgufFileType.UNKNOWN }?.label ?: quantizationFromName
    val window = trainingContextLength.takeIf { it > 0 }?.let { " · remembers ${it.asTokens()}" }
    return quantization + window.orEmpty()
}

/**
 * What running it costs and what it gives back.
 *
 * The comparison against usable memory went, and the verdict line directly above says the
 * same thing in words a person can act on: "Runs comfortably" is the answer that "1.12 GB of
 * 7.15 GB usable" was arithmetic towards. The KV cache went with it, because it is already
 * inside the number beside it and the slider moves both.
 */
private fun FitReport.memoryLine(): String {
    val speed = estimatedDecodeTokensPerSecond
        ?.let { String.format(Locale.getDefault(), " · about %.0f tokens a second", it) }
        .orEmpty()
    return "needs ${formatBytes(requiredMemoryBytes)}$speed"
}

/** 128000 as "128k tokens", because six digits is a number nobody reads. */
private fun Int.asTokens(): String {
    if (this < THOUSAND) return "$this tokens"
    return "${this / THOUSAND}k tokens"
}

private const val THOUSAND = 1_000

@Preview(showBackground = true, backgroundColor = 0xFF0D0E10)
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
