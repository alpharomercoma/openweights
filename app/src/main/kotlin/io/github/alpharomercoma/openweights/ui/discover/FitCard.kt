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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.R
import io.github.alpharomercoma.openweights.core.common.model.CompiledBackend
import io.github.alpharomercoma.openweights.core.common.model.GgufFileType
import io.github.alpharomercoma.openweights.core.common.model.GgufMetadata
import io.github.alpharomercoma.openweights.core.common.model.ModelFormat
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
fun FitCard(
    inspected: InspectedFile,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * How far this file's download has got, or null when it is not being fetched.
     *
     * Without it this card offered "Download" for a file already downloading. Starting one
     * pushes the installed list, so the progress was there to see, but coming back to this
     * screen showed a button that invited the same download a second time and said nothing
     * about the one already running.
     */
    downloadFraction: Float? = null,
    /**
     * Stops the download this card started.
     *
     * This is where somebody changes their mind: they tapped Download a second ago, saw a
     * gigabyte begin to move, and are looking at the card that started it. Sending them to
     * the installed-models screen to stop it is asking them to find the thing they just did
     * somewhere else.
     */
    onCancelDownload: () -> Unit = {},
) {
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
                // Stated, not offered. A compiled model's processor was decided when it
                // was exported — the file holds delegate identifiers and loading resolves
                // those exact ones — so this is a fact about the download rather than
                // something the user can change afterwards.
                inspected.compiledFor?.let { Caption(stringResource(it)) }
            }

            when {
                inspected.isDownloaded -> Caption(stringResource(R.string.on_this_device))
                inspected.isInspecting -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )

                // Before the fit verdict, because it outranks it: a file this engine
                // cannot parse will not run at any context length, and offering the
                // slider as a way out would be a lie.
                inspected.cannotRun -> Unit
                inspected.fit?.verdict == FitVerdict.WONT_RUN -> Unit
                downloadFraction != null -> Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        progress = { downloadFraction },
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        // The word goes and the number stays. "Downloading 43%" beside a
                        // ring that is 43% full says the same thing twice, and the room it
                        // took is what the way out needed: this row shares one line with a
                        // file name that is often long enough to wrap on its own.
                        text = stringResource(
                            R.string.percent_complete,
                            (downloadFraction * 100).toInt(),
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(
                        onClick = onCancelDownload,
                        modifier = Modifier.size(CANCEL_TARGET),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.cancel_download),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(CANCEL_ICON),
                        )
                    }
                }

                else -> AccentButton(onClick = onDownload) {
                    Text(stringResource(R.string.download))
                }
            }
        }

        WhyItCannotRun(inspected)

        inspected.inspectionError?.let { error ->
            Text(
                text = stringResource(R.string.header_read_failed, error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // Only when the file could be loaded at all. "Runs comfortably" under "this
        // version cannot load bailingmoe3 models" is the card contradicting itself, and
        // the memory arithmetic is beside the point once the engine cannot read the
        // format: there is no context length at which it becomes true.
        if (!inspected.cannotRun) {
            inspected.fit?.let { fit -> VerdictLine(fit) }
        }
        // One line each, and each short enough to be one line. All three of these used to
        // run past the card and wrap, which on a list of eight files turned a scannable
        // column into a wall: the eye cannot compare files down a page whose rows are
        // different heights. The quantisation itself is dropped here rather than given a
        // line of its own: it is already the filename in the row above, and repeating it
        // was the same word twice for a card that is short on lines to spend.
        val window = inspected.metadata?.rememberLine()
        val needs = inspected.fit?.memoryLine()
        listOfNotNull(window, needs).takeIf { it.isNotEmpty() }?.let { parts ->
            Metric(parts.joinToString(" · "), maxLines = 1)
        }
        inspected.fit?.speedLine()?.let { speed -> Metric(speed, maxLines = 1) }
    }
}

@Composable
private fun VerdictLine(fit: FitReport) {
    val dark = LocalIsDarkTheme.current
    val (label, color) = when (fit.verdict) {
        FitVerdict.COMFORTABLE ->
            stringResource(R.string.fit_comfortable) to
                signal(OpenWeightsColors.SignalGood, OpenWeightsColors.PaperSignalGood, dark)

        FitVerdict.TIGHT ->
            stringResource(R.string.fit_tight) to
                signal(OpenWeightsColors.SignalPlain, OpenWeightsColors.PaperSignalPlain, dark)

        FitVerdict.WONT_RUN ->
            stringResource(R.string.fit_wont_run) to
                signal(OpenWeightsColors.SignalPoor, OpenWeightsColors.PaperSignalPoor, dark)

        FitVerdict.NO_ROOM_TO_DOWNLOAD ->
            stringResource(R.string.fit_no_storage) to
                signal(OpenWeightsColors.SignalPoor, OpenWeightsColors.PaperSignalPoor, dark)
    }

    Text(text = label, style = MaterialTheme.typography.bodyMedium, color = color)
}

/**
 * Whether this file is one the engine will not run, whatever the device has free.
 *
 * Two reasons, and they are one question to everything that reads them: the download is
 * withheld, the memory verdict is suppressed, and one sentence is shown instead. Asked as a
 * property so the card does not grow a branch per reason — the two below already cost it
 * detekt's complexity ceiling.
 */
/**
 * The processor this file was compiled for, or null when it was not compiled at all.
 *
 * Read from the name, because a `.pte` carries no metadata and the runtime has no API
 * reporting which delegates a loaded model uses. A GGUF has no answer here by design: it
 * is interpreted at load, so where it runs really is a setting.
 */
private val InspectedFile.compiledFor: Int?
    get() {
        if (ModelFormat.of(file.fileName) != ModelFormat.PTE) return null
        return when (CompiledBackend.of(file.path).processor) {
            CompiledBackend.Processor.CPU -> R.string.compiled_runs_on_cpu
            CompiledBackend.Processor.GPU -> R.string.compiled_runs_on_gpu
            CompiledBackend.Processor.NPU -> R.string.compiled_runs_on_npu
        }
    }

private val InspectedFile.cannotRun: Boolean
    get() = unsupportedArchitecture != null || draftArchitecture != null

/**
 * The sentence for a file that will not run, or nothing when it will.
 *
 * The two reasons are kept apart because they ask for opposite things. An architecture this
 * build does not know is a reason to update the app and will work one day. A
 * speculative-decoding draft head never will: it carries no vocabulary and no output layer,
 * borrowing both from the model it drafts for, so telling somebody to update would send
 * them after a release that is never coming.
 */
@Composable
private fun WhyItCannotRun(inspected: InspectedFile) {
    val text = when {
        inspected.draftArchitecture != null -> stringResource(R.string.draft_model_not_a_model)
        inspected.unsupportedArchitecture != null ->
            stringResource(R.string.needs_newer_version, inspected.unsupportedArchitecture)
        else -> return
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = signal(
            OpenWeightsColors.SignalPoor,
            OpenWeightsColors.PaperSignalPoor,
            LocalIsDarkTheme.current,
        ),
    )
}

private fun signal(dark: Color, light: Color, isDark: Boolean) = if (isDark) dark else light

/**
 * How much the model can hold, which used to read "trained to 128000 tokens" and is a
 * sentence about the training run rather than about what you get.
 *
 * The quantisation used to open this line, but it is already the filename in the row above
 * it, and this card is short on lines to spend repeating it.
 */
private fun GgufMetadata.rememberLine(): String? =
    trainingContextLength.takeIf { it > 0 }?.let { "remembers ${it.asTokens()}" }

/**
 * What running it costs, in words the verdict line above has already framed: "Runs
 * comfortably" is the answer that this arithmetic is towards.
 */
private fun FitReport.memoryLine(): String = "needs ${formatBytes(requiredMemoryBytes)}"

/**
 * What it gives back, kept off the memory line rather than trailing it: cost and throughput
 * are two different questions, and cramming both onto one line either wraps the card or
 * shrinks the type past the point either number is worth reading.
 */
private fun FitReport.speedLine(): String? {
    val prefill = estimatedPrefillTokensPerSecond
        ?.let { String.format(Locale.getDefault(), "≈%.0f tok/s prefill", it) }
    val decode = estimatedDecodeTokensPerSecond
        ?.let { String.format(Locale.getDefault(), "≈%.0f tok/s decode", it) }
    return listOfNotNull(prefill, decode).takeIf { it.isNotEmpty() }?.joinToString(" · ")
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

/** The touch target for the cancel, at the accessibility minimum rather than the icon's size. */
private val CANCEL_TARGET = 40.dp

/** The X itself, sized to sit beside a percentage rather than to compete with it. */
private val CANCEL_ICON = 18.dp
