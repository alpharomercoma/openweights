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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.R
import io.github.alpharomercoma.openweights.core.common.context.Goal
import io.github.alpharomercoma.openweights.core.common.context.GoalState
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius

/** Visible ownership and controls for work that continues across ordinary turns. */
@Composable
fun GoalCard(
    goal: Goal,
    onStop: () -> Unit,
    onSteer: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var steering by remember(goal.task) { mutableStateOf("") }
    val state = when (goal.state) {
        GoalState.PLANNING -> stringResource(R.string.goal_planning)
        GoalState.WORKING -> stringResource(R.string.goal_working)
        GoalState.DONE -> stringResource(R.string.goal_done)
        GoalState.STOPPED -> stringResource(R.string.goal_stopped)
        GoalState.HALTED -> stringResource(R.string.goal_halted)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(Radius.sm),
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = state, style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(R.string.goal_step_count, goal.stepsTaken, Goal.MAX_STEPS),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = goal.task,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        goal.currentStep?.let {
            Text(
                text = stringResource(R.string.goal_current_step, it.text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        goal.note?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (goal.isRunning) {
            OutlinedTextField(
                value = steering,
                onValueChange = { steering = it.take(MAX_STEERING_CHARS) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.goal_steer_label)) },
                supportingText = { Text(stringResource(R.string.goal_steer_support)) },
                maxLines = 3,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onStop) {
                    Text(stringResource(R.string.goal_stop))
                }
                Button(
                    onClick = {
                        onSteer(steering.trim())
                        steering = ""
                    },
                    enabled = steering.isNotBlank(),
                ) {
                    Text(stringResource(R.string.goal_send_steering))
                }
            }
        } else {
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.goal_dismiss))
            }
        }
    }
}

private const val MAX_STEERING_CHARS = 500
