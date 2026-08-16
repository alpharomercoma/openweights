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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.core.common.context.TaskPlan
import io.github.alpharomercoma.openweights.core.common.context.readPlan
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius

/**
 * The steps the model proposed, with a box each.
 *
 * Above the composer, where the approval card sits and where the eye already is. It is not
 * part of the transcript because it is not something that was said: it is the state of the
 * work, it changes as steps are done, and a transcript entry that rewrote itself would be
 * the one thing in the chat that is not a record.
 *
 * The boxes are real. The model ticks them through `advance` and the user ticks them here,
 * and neither is a special case, because the plan belongs to the app rather than to either
 * of them. Watching a model claim a step is done that plainly is not, and being able to say
 * so, is the difference between a plan and a paragraph.
 */
@Composable
fun PlanCard(plan: TaskPlan, onTick: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(Radius.sm))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp)
            .semantics { contentDescription = "Plan" },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = if (plan.isFinished) "Plan, all done" else "Plan",
            style = MaterialTheme.typography.titleSmall,
        )
        plan.steps.forEachIndexed { index, step ->
            Row(
                // The whole row, not just the box. A checklist row is read as one thing and
                // tapped as one thing: the label is the wide part and the obvious target,
                // and it did nothing, so the only way to tick a step was to hit a box a
                // finger's width across. Toggleable rather than clickable so a screen reader
                // is told this is a checkbox and what state it is in.
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = step.done,
                        // Ticking is one way. A model that has done something cannot undo
                        // it, and a person who ticks a box has decided it is done; offering
                        // to untick would invite a disagreement the plan has no way to
                        // settle.
                        enabled = !step.done,
                        role = Role.Checkbox,
                        onValueChange = { onTick(index) },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Checkbox(
                    checked = step.done,
                    // Null, so the row above owns the tap and announces it. A box that
                    // handled its own clicks here would be a second target inside the
                    // first, and a second node for a screen reader to read out.
                    onCheckedChange = null,
                )
                Text(
                    text = step.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (step.done) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    textDecoration = if (step.done) TextDecoration.LineThrough else null,
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0E10)
@Composable
private fun PlanCardPreview() {
    val plan = readPlan("1. Find the notes file\n2. Read the budget section\n3. Save a summary")
    OpenWeightsTheme(dynamicColor = false) {
        PlanCard(plan = plan!!.ticked(0), onTick = {})
    }
}
