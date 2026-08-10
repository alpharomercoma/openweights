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
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius
import io.github.alpharomercoma.openweights.core.hub.HubModel
import java.util.Locale

/**
 * One search result.
 *
 * A model repository has no picture, so the row has to make its own shape. The publisher's
 * initials in a tinted tile give the list a left edge to scan down, and the size badge
 * answers the first question anyone asks about a model on a phone before they have to open
 * anything. Everything else is one quiet line underneath.
 */
@Composable
fun ModelRow(model: HubModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        PublisherTile(model.owner)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = model.displayName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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

/**
 * The publisher's initials in a tile tinted from their name.
 *
 * Not an avatar: the Hub does not return one with search results, and fetching one per row
 * would be thirty requests to decorate a list. The hue is derived from the name, so the
 * same publisher is the same colour every time and a list of eight repositories from four
 * publishers reads as four groups.
 */
@Composable
private fun PublisherTile(owner: String) {
    val tint = ownerTint(owner)
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(Radius.xs))
            .background(tint.copy(alpha = TILE_FILL_ALPHA))
            .border(1.dp, tint.copy(alpha = TILE_EDGE_ALPHA), RoundedCornerShape(Radius.xs))
            // One label for the row is enough; the initials are decoration for the name
            // already read out below.
            .clearAndSetSemantics { contentDescription = "" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = owner.initials(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun Badge(text: String, icon: ImageVector? = null, emphasis: Boolean = false) {
    val container = if (emphasis) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = if (emphasis) {
        MaterialTheme.colorScheme.onPrimaryContainer
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

/** Up to two initials, which is what fits in the tile at any font scale. */
private fun String.initials(): String = split('-', '_', '.', ' ')
    .filter { it.isNotEmpty() }
    .take(2)
    .map { it.first().uppercaseChar() }
    .joinToString("")
    .ifEmpty { "?" }

/**
 * A stable colour per publisher, taken from the signal scale rather than invented.
 *
 * The accent is reserved for things that can be tapped, so these come from the palette's
 * measurement hues at low saturation: enough to tell publishers apart, not enough to read
 * as a control.
 */
private fun ownerTint(owner: String): Color {
    if (owner.isEmpty()) return TILE_HUES.first()
    return TILE_HUES[(owner.hashCode().mod(TILE_HUES.size))]
}

private val TILE_HUES = listOf(
    Color(0xFF4FC08D),
    Color(0xFF5B8DEF),
    Color(0xFFB07CE8),
    Color(0xFFE8A0C0),
    Color(0xFF8C99A4),
)

private const val TILE_FILL_ALPHA = 0.18f
private const val TILE_EDGE_ALPHA = 0.45f

/** Download counts run to seven figures, and nobody reads seven figures. */
internal fun Int.compact(): String = when {
    this >= MILLION -> String.format(Locale.US, "%.1fM", this / MILLION.toDouble())
    this >= THOUSAND -> String.format(Locale.US, "%.1fk", this / THOUSAND.toDouble())
    else -> toString()
}

private const val THOUSAND = 1_000
private const val MILLION = 1_000_000
