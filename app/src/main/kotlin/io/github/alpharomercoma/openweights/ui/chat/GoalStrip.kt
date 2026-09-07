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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.R
import io.github.alpharomercoma.openweights.core.common.context.Goal
import io.github.alpharomercoma.openweights.core.common.context.GoalState
import io.github.alpharomercoma.openweights.core.common.context.TaskPlan
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius

/**
 * One line about the goal that is running, above the composer.
 *
 * The transcript is the screen. This used to be a card with the task, the current step, a
 * note, its own text field with a label and a supporting line, and two buttons, sitting
 * above a second card with the whole plan, above the real composer, disabled. On a phone the
 * three of them left the transcript with no height at all, so the reply being written for
 * the step, the one thing a person waiting on a goal wants to see, was invisible
 * (2026-09-07, `/deep-research` on a 6.7 inch screen). Everything the card said is still
 * available: the strip says the state and the step in one line, and tapping it opens a
 * sheet with the rest. The plan lives in the transcript, where it was made. Steering goes
 * through the composer, which is the only text field on the screen.
 */
@Composable
fun GoalStrip(
    goal: Goal,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
    onTick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDetails by remember { mutableStateOf(false) }
    val stateLabel = goal.stateLabel()
    val detail = when {
        goal.isRunning && goal.currentStep != null ->
            stringResource(
                R.string.goal_strip_step,
                goal.stepsTaken + 1,
                Goal.MAX_STEPS,
                goal.currentStep!!.text,
            )
        goal.note != null -> goal.note!!
        else -> goal.task
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(Radius.sm),
            )
            .clickable(onClickLabel = stringResource(R.string.goal_details)) { showDetails = true }
            .heightIn(min = STRIP_HEIGHT)
            .padding(start = 12.dp, end = 4.dp)
            .semantics { contentDescription = "Goal" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stateLabel,
                style = MaterialTheme.typography.labelMedium,
                color = if (goal.state == GoalState.HALTED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (goal.isRunning) {
            TextButton(onClick = onStop) { Text(stringResource(R.string.goal_stop)) }
        } else {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.goal_dismiss)) }
        }
        Icon(
            imageVector = Icons.Rounded.KeyboardArrowUp,
            contentDescription = stringResource(R.string.goal_details),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (showDetails) {
        GoalSheet(
            goal = goal,
            onStop = onStop,
            onDismiss = onDismiss,
            onTick = onTick,
            onClose = { showDetails = false },
        )
    }
}

/**
 * Everything the strip does not have room for: the task in full, the plan with its boxes,
 * the note, and what typing into the composer does while the goal runs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalSheet(
    goal: Goal,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
    onTick: (Int) -> Unit,
    onClose: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = goal.stateLabel(), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = stringResource(
                        R.string.goal_step_count,
                        goal.stepsTaken,
                        Goal.MAX_STEPS,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(text = goal.task, style = MaterialTheme.typography.bodyMedium)
            goal.note?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            goal.plan?.let { plan: TaskPlan -> PlanCard(plan = plan, onTick = onTick) }
            if (goal.isRunning) {
                Text(
                    text = stringResource(R.string.goal_steer_label) + ". " +
                        stringResource(R.string.goal_steer_support),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (goal.isRunning) {
                    TextButton(
                        onClick = {
                            onStop()
                            onClose()
                        },
                    ) {
                        Text(stringResource(R.string.goal_stop))
                    }
                } else {
                    TextButton(
                        onClick = {
                            onDismiss()
                            onClose()
                        },
                    ) {
                        Text(stringResource(R.string.goal_dismiss))
                    }
                }
            }
        }
    }
}

@Composable
private fun Goal.stateLabel(): String = when (state) {
    GoalState.PLANNING -> stringResource(R.string.goal_planning)
    GoalState.WORKING -> stringResource(R.string.goal_working)
    GoalState.DONE -> stringResource(R.string.goal_done)
    GoalState.STOPPED -> stringResource(R.string.goal_stopped)
    GoalState.HALTED -> stringResource(R.string.goal_halted)
}

/** One line of label and one of detail, and no more: the strip must never grow. */
private val STRIP_HEIGHT = 48.dp
