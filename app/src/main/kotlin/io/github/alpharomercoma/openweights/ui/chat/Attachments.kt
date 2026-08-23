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

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.alpharomercoma.openweights.core.common.model.MediaKind
import io.github.alpharomercoma.openweights.core.common.model.MessagePart
import io.github.alpharomercoma.openweights.core.designsystem.component.Metric
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius
import io.github.alpharomercoma.openweights.core.engine.MediaSupport
import io.github.alpharomercoma.openweights.model.StagedDocument
import java.io.File

/**
 * The attachment menu, raised by the button beside the message field.
 *
 * Only offers what the loaded model can actually read. Every other chat app can show all
 * three choices because the model behind them is fixed; here the model changes with each
 * download, and offering a photo to a text-only model would produce a confident answer
 * about a picture it never saw.
 */
/** What can be read as text without a parser. */
private val DOCUMENT_TYPES = arrayOf("text/*", "application/json", "application/xml")

/**
 * The document waiting to be sent, and the way to change your mind about it.
 *
 * Says how much of it is going, because that is the part nobody expects: a phone's context
 * window holds a few pages, and a document that was quietly cut in half would be answered
 * about as though it were whole.
 */
@Composable
fun StagedDocumentChip(
    document: StagedDocument,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.sm))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
            Text(
                text = document.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Metric(
                if (document.wasTrimmed) {
                    "${document.text.length} characters, cut to fit the context"
                } else {
                    "${document.text.length} characters"
                },
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Remove ${document.name}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentSheet(
    support: MediaSupport,
    newCaptureUri: () -> Uri,
    onPicked: (Uri) -> Unit,
    onPickedDocument: (Uri) -> Unit,
    onDismiss: () -> Unit,
) {
    val captureUri = remember { newCaptureUri() }

    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(onPickedDocument)
        onDismiss()
    }

    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        uri?.let(onPicked)
        onDismiss()
    }

    val openFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(onPicked)
        onDismiss()
    }

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved ->
        if (saved) {
            onPicked(captureUri)
        }
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            if (support.vision) {
                AttachmentChoice(
                    icon = Icons.Rounded.PhotoCamera,
                    label = "Take a photo",
                    detail = "Opens the camera app. Nothing is uploaded.",
                    onClick = { takePicture.launch(captureUri) },
                )
                AttachmentChoice(
                    icon = Icons.Rounded.Image,
                    label = "Photos",
                    detail = if (support.video) "Pick an image or a video" else "Pick an image",
                    onClick = {
                        val request = PickVisualMediaRequest(
                            if (support.video) {
                                ActivityResultContracts.PickVisualMedia.ImageAndVideo
                            } else {
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            },
                        )
                        pickMedia.launch(request)
                    },
                )
            }
            if (support.audio) {
                AttachmentChoice(
                    icon = Icons.Rounded.AudioFile,
                    label = "Audio",
                    detail = "A recording for the model to listen to",
                    onClick = { openFile.launch(arrayOf("audio/*")) },
                )
            }
            // Every model can read a document, including one that accepts no media at all,
            // which is why this row is unconditional where the ones above it are not. It
            // used to be a second icon in the composer with its own picker and its own
            // filter, sitting beside the plus and doing a job the plus claimed to do.
            AttachmentChoice(
                icon = Icons.Rounded.Description,
                label = "Document",
                detail = "Text, markdown, JSON or XML the model can read",
                onClick = { openDocument.launch(DOCUMENT_TYPES) },
            )
            if (support.any) {
                AttachmentChoice(
                    icon = Icons.Rounded.AttachFile,
                    label = "Files",
                    detail = "Anything else this model accepts",
                    onClick = { openFile.launch(support.acceptedMimeTypes()) },
                )
            }
        }
    }
}

@Composable
private fun AttachmentChoice(
    icon: ImageVector,
    label: String,
    detail: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Staged attachments, shown above the message field until the message is sent. */
@Composable
fun StagedAttachments(
    attachments: List<MessagePart.File>,
    onRemove: (MessagePart.File) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(attachments, key = { it.path }) { attachment ->
            Box {
                AttachmentThumbnail(attachment, size = STAGED_SIZE)
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Remove ${attachment.describe()}",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable { onRemove(attachment) }
                        .padding(2.dp),
                )
            }
        }
    }
}

/** Attachments as they appear on a sent message, above its text. */
@Composable
fun SentAttachments(attachments: List<MessagePart.File>, modifier: Modifier = Modifier) {
    // What a tap opens, or null. A thumbnail is 64dp and the attachment is what the turn is
    // about, so until this existed a picture could only be looked at in the app it came from.
    var opened by remember { mutableStateOf<MessagePart.File?>(null) }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        attachments.forEach { attachment ->
            AttachmentThumbnail(
                attachment = attachment,
                size = SENT_SIZE,
                onClick = { opened = attachment },
            )
        }
    }

    opened?.let { MediaViewer(attachment = it, onDismiss = { opened = null }) }
}

/**
 * One attachment.
 *
 * Images show themselves. Everything else shows its kind and name, because a waveform
 * thumbnail for an audio file communicates less than the filename does.
 */
@Composable
private fun AttachmentThumbnail(
    attachment: MessagePart.File,
    size: Int,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(Radius.sm)
    // Only where a caller offered one. The composer's own row uses these too, and there the
    // tap belongs to removing the attachment rather than to looking at it.
    val tappable = onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier
    if (attachment.kind == MediaKind.IMAGE) {
        AsyncImage(
            model = File(attachment.path),
            contentDescription = attachment.describe(),
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size.dp).clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .then(tappable),
        )
        return
    }

    Column(
        modifier = Modifier
            .size(size.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(tappable)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = when (attachment.kind) {
                MediaKind.AUDIO -> Icons.Rounded.AudioFile
                MediaKind.VIDEO -> Icons.Rounded.Videocam
                else -> Icons.Rounded.Description
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = attachment.describe(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The document types to offer, given what the model can read. */
private fun MediaSupport.acceptedMimeTypes(): Array<String> = buildList {
    if (vision) add("image/*")
    if (audio) add("audio/*")
    if (video) add("video/*")
    if (isEmpty()) add("*/*")
}.toTypedArray()

private const val STAGED_SIZE = 64
private const val SENT_SIZE = 76

@Preview(showBackground = true, backgroundColor = 0xFF0D0E10)
@Composable
private fun StagedAttachmentsPreview() {
    OpenWeightsTheme(dynamicColor = false) {
        StagedAttachments(
            attachments = listOf(
                MessagePart.File("/tmp/a.jpg", "image/jpeg", "photo.jpg"),
                MessagePart.File("/tmp/b.wav", "audio/wav", "note.wav"),
            ),
            onRemove = {},
        )
    }
}
