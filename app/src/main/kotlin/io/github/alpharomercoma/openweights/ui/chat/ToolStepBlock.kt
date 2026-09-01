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

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import io.github.alpharomercoma.openweights.R
import io.github.alpharomercoma.openweights.core.designsystem.component.MarkdownText
import io.github.alpharomercoma.openweights.core.designsystem.theme.MetricTextStyle
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius
import io.github.alpharomercoma.openweights.core.tools.AgentStep
import io.github.alpharomercoma.openweights.core.tools.FoundPicture
import io.github.alpharomercoma.openweights.core.tools.SearchMediaTool
import io.github.alpharomercoma.openweights.core.tools.scriptSource
import java.util.Locale

/**
 * What the model did, in the order it did it.
 *
 * An agent whose actions you cannot inspect is one you cannot trust on your own phone, and
 * the first version of this stored every step and rendered none of them: replies that had
 * searched and replies that had guessed looked identical. Collapsed by default like the
 * reasoning block above it, because the interesting thing is usually that a search
 * happened, not what came back; expanded when it is not.
 */
@Composable
fun ToolStepBlock(step: AgentStep, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val detail = step.detail()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(Radius.xs))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            // A hairline, because this sits inside a reply rather than on the canvas: at
            // one step of raise off a near-black background it had no edge at all and read
            // as a smudge under the sentence above it.
            .border(
                width = Dp.Hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(Radius.xs),
            )
            .clickable(enabled = detail.isNotBlank()) { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                // A refused call is not a done call. The tick on every Ran step, refusals
                // included, sent a whole debugging session the wrong way: "show slides ·
                // 0.0s ✓" was a rejection wearing a success mark. Refusals get the same
                // block mark a skip does, because to the user they are the same news -
                // this did not happen.
                imageVector = when {
                    step is AgentStep.Skipped -> Icons.Rounded.Block
                    step is AgentStep.Ran && !step.successful -> Icons.Rounded.Block
                    else -> Icons.Rounded.Check
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = step.headline(),
                style = MetricTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (detail.isNotBlank()) {
                Icon(
                    imageVector = if (expanded) {
                        Icons.Rounded.ExpandLess
                    } else {
                        Icons.Rounded.ExpandMore
                    },
                    contentDescription = if (expanded) {
                        "Hide what it returned"
                    } else {
                        "Show what it returned"
                    },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        // Outside the disclosure, unlike everything else here. A grid of pictures is the
        // answer rather than the working: somebody who asked to see something should see it
        // without first being told a tool ran and having to tap.
        val pictures = step.pictures()
        if (pictures.isNotEmpty()) {
            MediaGrid(pictures = pictures, modifier = Modifier.padding(top = 8.dp))
        }

        AnimatedVisibility(visible = expanded) {
            val program = step.scriptSource()
            if (program == null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                // A program is not a tool result and should not read as one. Rendered
                // through the same Markdown path the model's own replies use, which brings
                // the language header, the syntax colours and the copy button with it: the
                // point of showing generated code at all is that somebody might want to
                // keep it, and a paragraph of monospaced prose cannot be kept.
                CodeStep(program = program, output = detail)
            }
        }
    }
}

/**
 * A program and what running it produced.
 *
 * Two blocks rather than one, because they are two different things and the model gets the
 * first one wrong often enough that the second is usually the interesting half.
 */
@Composable
private fun CodeStep(program: String, output: String) {
    Column(modifier = Modifier.padding(top = 6.dp)) {
        MarkdownText(content = "```javascript\n$program\n```")
        if (output.isNotBlank()) {
            Text(
                text = stringResource(R.string.result),
                style = MetricTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
            )
            MarkdownText(content = "```text\n$output\n```")
        }
    }
}

/**
 * One line saying what happened, with the argument that makes it specific.
 *
 * The tool is named in words rather than by its identifier. `web_search("Manila weather")`
 * is a function call; "Searched the web" is what actually happened, and this row is the
 * whole of the disclosure that anything left the phone. An app whose claim is that it
 * answers on the device cannot make that disclosure in an identifier the reader has to
 * decode, so the ones that reach the network say so in the verb.
 */
internal fun AgentStep.headline(): String = when (this) {
    is AgentStep.Requested -> "${call.name.asVerb()} requested"

    is AgentStep.Ran -> {
        val seconds = String.format(Locale.getDefault(), "%.1fs", millis / MILLIS_PER_SECOND)
        "${call.name.asVerb()} ${call.argumentsJson.summarise()} · $seconds"
    }

    is AgentStep.Skipped -> "${call.name.asVerb()} skipped · $why"
}

/**
 * What a tool did, in the past tense.
 *
 * Unknown names fall back to the identifier with its underscores opened out, so a tool
 * added later reads roughly right rather than not at all.
 */
private fun String.asVerb(): String = VERBS[this] ?: replace('_', ' ')

private val VERBS = mapOf(
    "web_search" to "Searched the web for",
    // Both names, so a chip stored before the rename reads the same as one after it —
    // and both say the search left the device, which is the disclosure the chip is for.
    SearchMediaTool.NAME to "Searched the web for pictures of",
    SearchMediaTool.LEGACY_NAME to "Searched the web for pictures of",
    "fetch_url" to "Opened",
    "find_files" to "Looked through your folder for",
    "search_files" to "Looked through your folder for",
    "read_file" to "Read",
    "write_file" to "Saved",
    "run_script" to "Worked out",
    "save_memory" to "Remembered",
    "update_memory" to "Updated a saved memory:",
    "forget_memory" to "Forgot the memory",
)

/** What it returned, shown only when asked for. */
private fun AgentStep.detail(): String = when (this) {
    is AgentStep.Requested -> ""
    is AgentStep.Ran -> result
    is AgentStep.Skipped -> ""
}

/**
 * The arguments, short enough for one line.
 *
 * Values only, without their keys. It used to print the object with its braces taken off,
 * so a search read `web_search("query":"Manila weath…` : a key nobody needed, eating the
 * width of the one thing that says which search this was, and then a cut through the middle
 * of a word. The whole JSON is in the expanded view for anything that ran.
 *
 * Cut at a space rather than mid-word, because a truncation that lands inside a word reads
 * as an overflow bug rather than as an ellipsis.
 */
private fun String.summarise(): String {
    val values = VALUES.findAll(this).map { it.groupValues[1] }.filter { it.isNotBlank() }
    val joined = values.joinToString(", ").ifEmpty { trim().removeSurrounding("{", "}").trim() }
    if (joined.length <= ARGUMENT_CHARS) return joined
    val cut = joined.take(ARGUMENT_CHARS)
    return (cut.substringBeforeLast(' ', cut).trimEnd()) + "…"
}

/** Each value in a flat arguments object, quoted strings kept whole, escapes included. */
private val VALUES = Regex(""":\s*("(?:[^"\\]|\\.)*"|[^,}\s]+)""")

private const val ARGUMENT_CHARS = 44
private const val MILLIS_PER_SECOND = 1000.0

/**
 * The pictures one step found, or nothing for a step that found none.
 *
 * Read back out of the tool's own text, because a tool returns a string and there is no
 * other channel. See `SearchMediaTool.MEDIA` for the marker and why it is a prefix rather
 * than a block of JSON.
 */
private fun AgentStep.pictures(): List<FoundPicture> = when (this) {
    // The legacy name too: rows stored before the rename hold the same grid.
    is AgentStep.Ran -> if (
        call.name == SearchMediaTool.NAME || call.name == SearchMediaTool.LEGACY_NAME
    ) {
        SearchMediaTool.picturesIn(result)
    } else {
        emptyList()
    }
    else -> emptyList()
}

/**
 * Results as pictures, the way every assistant that can show them does it.
 *
 * A row that scrolls sideways rather than a grid that wraps, and the reason is the container:
 * this sits inside a transcript that scrolls vertically, and a wrapping grid inside a
 * vertical scroller either has to be measured to its full height, which makes a long result
 * push the reply off screen, or nested, which Compose will not do. A row has one obvious
 * gesture and takes fixed height whatever the count.
 */
@Composable
private fun MediaGrid(pictures: List<FoundPicture>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(pictures) { picture ->
            AsyncImage(
                model = picture.thumbnail,
                // Named rather than described. Nothing here knows what is in the picture,
                // and inventing a description for a screen reader is worse than admitting
                // there is one to look at.
                contentDescription = stringResource(R.string.search_result_picture),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(THUMBNAIL)
                    .clip(RoundedCornerShape(Radius.xs))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable {
                        // The page, not the picture. Out to the browser rather than into a
                        // viewer of our own: the thumbnail is a thumbnail, and the page it
                        // came from is what has the licence, the caption and the context.
                        //
                        // This opened the thumbnail until a reviewer read the comment beside
                        // it and then read the code, which is the only reason a comment that
                        // describes the opposite of what happens ever gets caught.
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, picture.source.toUri()),
                            )
                        }
                    },
            )
        }
    }
}

/** Big enough to recognise a subject, small enough that eight fit a phone's width in two. */
private val THUMBNAIL = 96.dp
