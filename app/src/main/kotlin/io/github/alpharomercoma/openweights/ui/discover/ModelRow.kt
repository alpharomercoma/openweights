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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsColors
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius
import io.github.alpharomercoma.openweights.core.hub.HubModel
import java.util.Locale

/**
 * One search result.
 *
 * Two lines: what it is called and how big it is on the first, who published it and how
 * many people have taken it on the second. It was three, with a tile of initials down the
 * left, which made a list of five models as tall as a screen and gave every row the shape
 * of a contact card.
 */
@Composable
fun ModelRow(
    model: HubModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    avatarUrl: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Only when there is a real one. The slot used to hold the publisher's initials in
        // a tinted square until the picture arrived, which is the shape an address book
        // uses for a person with no photograph, and it read as one: five grey tiles saying
        // M, B, U, S, L down the left of a list of language models. A logo is worth the
        // column and a letter is not, so the letter went and the column goes with it when
        // there is nothing to put in it.
        avatarUrl?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(TILE_SIZE)
                    .clip(RoundedCornerShape(Radius.xs))
                    // Publishers upload logos on white, which on the light theme's card is
                    // a picture with no edge: the row looked like it had no avatar at all.
                    .border(
                        width = Dp.Hairline,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(Radius.xs),
                    ),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = model.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // Beside the name rather than under it. Size is the first question anybody
                // asks about a model on a phone, and on its own line it cost a third of the
                // row's height to answer a question that fits in four characters.
                model.parameterHint?.let { Badge(text = it, emphasis = true) }
                if (model.isVision) Badge(text = "Vision", icon = Icons.Rounded.Visibility)
                if (model.isAudio) Badge(text = "Audio", icon = Icons.Rounded.GraphicEq)
                if (model.isGated) Badge(text = "Gated", icon = Icons.Rounded.Lock)
            }

            Text(
                text = listOfNotNull(
                    model.owner.takeIf { it.isNotEmpty() },
                    "${model.downloads.compact()} downloads",
                    "${model.likes.compact()} likes".takeIf { model.likes > 0 },
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Badge(text: String, icon: ImageVector? = null, emphasis: Boolean = false) {
    // The same recipe as a selected chip and an accent button: lime carrying ink, the same
    // in both themes. Emphasis is for the parameter count, which is the number that decides
    // whether a model is worth opening at all.
    val container = if (emphasis) {
        OpenWeightsColors.Lime
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = if (emphasis) {
        OpenWeightsColors.Ink
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.xs))
            .background(container)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(12.dp),
            )
        }
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = content)
    }
}

/**
 * The repository name with the noise taken out.
 *
 * Nearly every quantized repository ends in `-GGUF`, so the suffix carries no information
 * in a list where all of them are GGUF, and it costs the width that would otherwise show
 * the part of the name that differs.
 */
private val HubModel.displayName: String
    get() = name.removeSuffix("-GGUF").removeSuffix("-gguf").ifEmpty { name }

private val TILE_SIZE = 36.dp

/** Download counts run to seven figures, and nobody reads seven figures. */
internal fun Int.compact(): String = when {
    this >= MILLION -> String.format(Locale.US, "%.1fM", this / MILLION.toDouble())
    this >= THOUSAND -> String.format(Locale.US, "%.1fk", this / THOUSAND.toDouble())
    else -> toString()
}

private const val THOUSAND = 1_000
private const val MILLION = 1_000_000
