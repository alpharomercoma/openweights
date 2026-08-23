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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.core.designsystem.component.Caption
import io.github.alpharomercoma.openweights.core.designsystem.theme.Motion
import io.github.alpharomercoma.openweights.core.tools.AgentStep
import java.util.Locale

/**
 * What the model did before it answered: open on the newest turn, one line once it is old.
 *
 * A turn that uses tools is not one block of text. It reasons, says something, calls
 * something, reads the result, says something else, and only then answers. All of that was
 * rendered at full height and left there, and rendered at full height it is most of a phone.
 * Measured on the 360 x 640dp canvas the listing shots use, a turn at the agent's round cap
 * of four put 1,056 of 1,920 pixels of preamble above the answer and started the answer 85%
 * of the way down the screen. The reply to the question the user actually asked was below
 * the fold on the turn they were waiting for.
 *
 * The first version folded them on the frame the turn finished, which the adversarial review
 * called the status strip's layout shift moved somewhere worse, and it was right: measured on
 * the phone canvas, the answer jumped **987 pixels** upward at the instant the last token
 * arrived, which is half a screen taken out from under somebody who has just started reading.
 *
 * So the trigger is not "finished", it is "no longer the newest turn". While a turn is the
 * latest, streaming or not, the steps stay where they were and nothing moves. They fold when
 * the next question is asked, by which time the turn is above the fold and the collapse is
 * something the reader scrolls back to rather than something that happens under their eye. A
 * tap still wins in either direction, and nothing is thrown away.
 *
 * This is also the convention. ChatGPT and Claude both collapse a finished tool run behind a
 * one line disclosure, and both leave it open while it is running. The difference here is
 * only that this app has more to show, because the work happened on the phone.
 */
@Composable
fun WorkBlock(
    blocks: List<TurnBlock>,
    isStreaming: Boolean,
    isLatest: Boolean,
    modifier: Modifier = Modifier,
) {
    if (blocks.isEmpty()) return

    // Keyed to the number of blocks rather than the list, so a tap survives the next step
    // arriving mid-run. Keying it to the list itself would reset the override on every
    // frame that added a chip, which is exactly while somebody is most likely to have
    // tapped.
    var override by rememberSaveable(blocks.size) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(isLatest) { if (!isLatest) override = null }
    val open = isStreaming || isLatest
    val expanded = override ?: open

    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = Motion.quick(),
        label = "work",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        // No header on the newest turn. The steps are their own progress report and they say
        // more than a count of themselves does; a summary line above the list of the things
        // it is summarising is noise, and adding one when the turn finishes would put back
        // the shift this is here to avoid.
        if (!open) {
            Row(
                modifier = Modifier
                    .clickable { override = !expanded }
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Caption(text = blocks.workLabel(expanded))
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Hide the steps" else "Show the steps",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(16.dp)
                        .rotate(chevronRotation),
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column {
                blocks.forEach { block ->
                    when (block) {
                        is TurnBlock.Step -> ToolStepBlock(block.step)
                        is TurnBlock.Said -> IntermediateText(block.text)
                    }
                }
            }
        }
    }
}

/**
 * The one line a finished run folds to.
 *
 * One step reuses the chip's own headline, so a single search reads "Searched the web for
 * kv cache · 1.8s" rather than "Used 1 tool", which tells a reader nothing they could not
 * see from the fact that there is a line there at all. Past one it counts, because four
 * headlines do not fit on a phone and they are one tap away.
 *
 * A run that was refused says so rather than counting, because a declined call is the one
 * case where the reader needs to know without opening anything: the answer underneath was
 * written without whatever they said no to.
 *
 * The one place the summary is dropped is a single step opened up, because there the
 * summary and the only thing under it are the same sentence, printed twice, four lines
 * apart. A count of several is still worth having above the several it counts.
 */
internal fun List<TurnBlock>.workLabel(expanded: Boolean): String {
    val steps = filterIsInstance<TurnBlock.Step>().map { it.step }
    if (steps.isEmpty()) return "Worked on it"
    if (steps.size == 1) return if (expanded) "Hide the steps" else steps.first().headline()

    val skipped = steps.count { it is AgentStep.Skipped }
    val ran = steps.size - skipped
    val what = when {
        ran == 0 -> return "Skipped $skipped steps"
        skipped > 0 -> "Used $ran of ${steps.size} tools"
        else -> "Used $ran tools"
    }

    val millis = steps.filterIsInstance<AgentStep.Ran>().sumOf { it.millis }
    if (millis <= 0) return what
    return what + String.format(Locale.getDefault(), " · %.1fs", millis / MILLIS_PER_SECOND)
}

private const val MILLIS_PER_SECOND = 1000.0
