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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.R
import io.github.alpharomercoma.openweights.core.designsystem.component.AccentButton
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius
import io.github.alpharomercoma.openweights.core.tools.UserQuestion

/**
 * The question the model is waiting on, with whatever it suggested.
 *
 * Three ways to answer, in the order they cost the reader anything: tap one chip, tap
 * several, or type. The third is always there. A model that offered no options, or offered
 * them malformed, still gets a question with a box under it, because a feature that only
 * works when a 1B model produces a clean array is a feature that mostly does not work.
 *
 * A single-choice question answers on the tap, because asking someone to choose and then
 * confirm is asking twice. A multiple-choice one has a button, because there is no other
 * moment at which the person is finished.
 */
@Composable
fun QuestionCard(
    question: UserQuestion,
    onAnswer: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var chosen by remember(question) { mutableStateOf(emptySet<String>()) }
    var typed by remember(question) { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(Radius.sm))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp)
            .semantics { contentDescription = "Question" },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = question.text, style = MaterialTheme.typography.titleSmall)

        if (question.options.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                question.options.forEach { option ->
                    FilterChip(
                        selected = option in chosen,
                        onClick = {
                            if (question.multiple) {
                                chosen = if (option in chosen) chosen - option else chosen + option
                            } else {
                                onAnswer(option)
                            }
                        },
                        label = { Text(option) },
                        leadingIcon = {
                            if (option in chosen) {
                                Icon(Icons.Rounded.Check, contentDescription = null)
                            }
                        },
                    )
                }
            }
        }

        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text(stringResource(R.string.say_own_words)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Answer" },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccentButton(
                onClick = { onAnswer(answerFrom(chosen, typed, question.options)) },
                enabled = chosen.isNotEmpty() || typed.isNotBlank(),
            ) {
                Text(stringResource(R.string.answer))
            }
        }
    }
}

/**
 * One string for the model, whichever way the person answered.
 *
 * Chips and typing are joined rather than one winning, because someone who ticks two boxes
 * and then adds "but not the archived ones" means both halves, and dropping either would
 * answer a question they did not answer.
 */
internal fun answerFrom(chosen: Set<String>, typed: String, options: List<String>): String =
    listOfNotNull(
        options.filter { it in chosen }.joinToString(", ").takeIf { it.isNotEmpty() },
        typed.trim().takeIf { it.isNotEmpty() },
    ).joinToString(". ")

@Preview(showBackground = true, backgroundColor = 0xFF0D0E10)
@Composable
private fun QuestionCardPreview() {
    OpenWeightsTheme(dynamicColor = false) {
        QuestionCard(
            question = UserQuestion(
                text = stringResource(R.string.which_folder_did_mean),
                options = listOf("Notes", "Documents", "Downloads"),
            ),
            onAnswer = {},
        )
    }
}
