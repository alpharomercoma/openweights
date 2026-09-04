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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.R
import io.github.alpharomercoma.openweights.core.common.model.ConfidenceRun
import io.github.alpharomercoma.openweights.core.common.model.ReplyConfidence
import io.github.alpharomercoma.openweights.core.designsystem.component.Caption
import io.github.alpharomercoma.openweights.core.designsystem.component.Metric
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import java.util.Locale
import kotlin.math.roundToInt

/**
 * One answer with the model's hesitations marked.
 *
 * ### Why a sheet and not the reply itself
 *
 * The obvious build is to underline the reply where it stands. It cannot be done honestly.
 * A reply is rendered as Markdown by a library that turns source into its own composables,
 * so a token offset in the source has no address in what is on screen, and the answer is
 * full of syntax that is never drawn: fences, pipes, asterisks. Underlining a table cell
 * whose confidence belongs to the pipe beside it would be a worse lie than not drawing it.
 *
 * Here the answer is plain text, so a token and the characters it produced are the same
 * thing, and the marks are exactly where the measurement was. It also keeps a feature for
 * checking an answer out of the way of reading one.
 *
 * ### What the marks mean, and what they do not
 *
 * Underlined is a token the model gave less than [ReplyConfidence.UNCERTAIN_BELOW]: it was
 * choosing between options it rated nearly as good. That is where an answer is worth
 * checking, and it is not a claim that the answer is wrong. The caveat is on the screen
 * rather than in this comment, because the person who most needs it is the one reading the
 * underlines, and a confident invention is the failure this feature will otherwise be
 * trusted to catch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UncertaintySheet(entry: TranscriptEntry, onDismiss: () -> Unit) {
    val confidence = entry.confidence
    val locale = LocalConfigurationLocale()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.uncertainty_title),
                style = MaterialTheme.typography.titleMedium,
            )

            if (confidence.tokenCount == 0) {
                // Said rather than shown as an empty page. Nothing measured and a model
                // that was sure of everything look identical on screen and mean opposite
                // things, which is the mistake this whole feature exists to stop making.
                Caption(stringResource(R.string.uncertainty_unmeasured))
                return@Column
            }

            val threshold = (ReplyConfidence.UNCERTAIN_BELOW * PERCENT).roundToInt()
            val marked = confidence.uncertainRuns.size
            Caption(
                if (marked == 0) {
                    stringResource(R.string.uncertainty_none, threshold)
                } else {
                    stringResource(
                        R.string.uncertainty_count,
                        marked,
                        confidence.runs.size,
                        threshold,
                    )
                },
            )

            Text(
                text = confidence.annotated(MaterialTheme.colorScheme.error),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )

            Metric(
                stringResource(
                    R.string.uncertainty_perplexity,
                    String.format(locale, "%.2f", confidence.perplexity),
                    confidence.tokenCount,
                    ((confidence.leastProbable ?: 0.0) * PERCENT).roundToInt(),
                ),
            )
            // Under the numbers rather than over them, because it is the sentence somebody
            // reads on the way out, and it is the one that keeps the numbers honest.
            Caption(stringResource(R.string.uncertainty_caveat))
        }
    }
}

/**
 * The answer with its hesitations underlined.
 *
 * Underline rather than a background colour, which is what most logprob viewers use. A
 * coloured background reads as a highlight, which is a claim that the marked words are the
 * important ones; an underline reads as a query against them, which is what this is. It
 * also survives being read on a phone in sunlight, where a pale wash does not.
 *
 * No numbers beside the words. A per-token figure above or below each word is what a
 * research tool does, and it makes a paragraph unreadable as a paragraph: the reader stops
 * reading the answer and starts reading a table. The figures that are worth having, the
 * average and the worst, are one line underneath.
 */
private fun ReplyConfidence.annotated(marked: androidx.compose.ui.graphics.Color): AnnotatedString =
    buildAnnotatedString {
        for (run in runs) {
            if (run.uncertain) {
                withStyle(SpanStyle(color = marked, textDecoration = TextDecoration.Underline)) {
                    append(run.text)
                }
            } else {
                append(run.text)
            }
        }
    }

/** The composition's locale, so a formatted number follows a language change. */
@Composable
private fun LocalConfigurationLocale(): Locale =
    androidx.compose.ui.platform.LocalConfiguration.current.locales[0]

private const val PERCENT = 100

@Preview(showBackground = true, backgroundColor = 0xFF0D0E10)
@Composable
private fun UncertaintySheetPreview() {
    OpenWeightsTheme(dynamicColor = false) {
        Column(modifier = Modifier.padding(16.dp)) {
            val confidence = ReplyConfidence(
                runs = listOf(
                    ConfidenceRun("The tallest building in ", 0.97, uncertain = false),
                    ConfidenceRun("Manila", 0.11, uncertain = true),
                    ConfidenceRun(" is about ", 0.95, uncertain = false),
                    ConfidenceRun("318", 0.04, uncertain = true),
                    ConfidenceRun(" metres.", 0.99, uncertain = false),
                ),
                perplexity = 1.62,
                tokenCount = 14,
            )
            Text(confidence.annotated(MaterialTheme.colorScheme.error))
        }
    }
}
