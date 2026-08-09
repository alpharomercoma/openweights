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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.data.ModelPreferences
import io.github.alpharomercoma.openweights.core.designsystem.component.ContextMeter
import io.github.alpharomercoma.openweights.core.designsystem.component.MarkdownText
import io.github.alpharomercoma.openweights.core.designsystem.component.Metric
import io.github.alpharomercoma.openweights.core.designsystem.component.ReasoningBlock
import io.github.alpharomercoma.openweights.core.designsystem.component.SpeedRail
import io.github.alpharomercoma.openweights.core.designsystem.component.rememberFollowTailState
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList")
fun ChatScreen(
    state: ChatUiState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onRegenerate: () -> Unit,
    onNewChat: () -> Unit,
    onCompact: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenModels: () -> Unit = {},
    onOpenConversation: (Long) -> Unit = {},
    onDeleteConversation: (Long) -> Unit = {},
    onSavePreferences: (ModelPreferences) -> Unit = {},
    onResetPreferences: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // The whole last entry, not just its text: finishing a reply adds a throughput line
    // and a reasoning header above the answer, which grows the item after the final token.
    // Keying on text alone would leave the reader looking at the middle of the reply.
    val followTail = rememberFollowTailState(
        listState = listState,
        contentSignal = state.transcript.lastOrNull() to state.transcript.size,
        scope = scope,
    )

    // Hold the id, not the entry: streaming replaces entries on every token, and a
    // captured copy would have Copy putting a half-finished reply on the clipboard.
    var actionsForId by remember { mutableStateOf<Long?>(null) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ConversationDrawer(
                conversations = state.conversations,
                activeId = state.activeConversationId,
                onOpen = {
                    onOpenConversation(it)
                    scope.launch { drawerState.close() }
                },
                onDelete = onDeleteConversation,
                onNewChat = {
                    onNewChat()
                    scope.launch { drawerState.close() }
                },
                nowMillis = System.currentTimeMillis(),
            )
        },
    ) {
        ChatContent(
            state = state,
            listState = listState,
            followTail = followTail,
            actionsForId = actionsForId,
            onActionsForId = { actionsForId = it },
            onSend = onSend,
            onStop = onStop,
            onRegenerate = onRegenerate,
            onNewChat = onNewChat,
            onCompact = onCompact,
            onOpenModels = onOpenModels,
            onOpenHistory = { scope.launch { drawerState.open() } },
            onSavePreferences = onSavePreferences,
            onResetPreferences = onResetPreferences,
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList")
private fun ChatContent(
    state: ChatUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    followTail: io.github.alpharomercoma.openweights.core.designsystem.component.FollowTailState,
    actionsForId: Long?,
    onActionsForId: (Long?) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onRegenerate: () -> Unit,
    onNewChat: () -> Unit,
    onCompact: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenHistory: () -> Unit,
    onSavePreferences: (ModelPreferences) -> Unit,
    onResetPreferences: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actionsFor = actionsForId?.let { id -> state.transcript.firstOrNull { it.id == id } }
    var showParameters by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { ModelChip(state = state, onClick = onOpenModels) },
                navigationIcon = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Rounded.Menu, contentDescription = "Past chats")
                    }
                },
                actions = {
                    if (state.modelName != null) {
                        IconButton(onClick = { showParameters = true }) {
                            Icon(Icons.Rounded.Tune, contentDescription = "Model settings")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (state.transcript.isEmpty()) {
                    EmptyState(
                        isLoadingModel = state.isLoadingModel,
                        hasModel = state.modelName != null,
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        items(state.transcript, key = { it.id }) { entry ->
                            entry.compactionNote?.let { note ->
                                CompactionMarker(note)
                            }
                            when (entry.role) {
                                ChatRole.USER -> UserTurn(
                                    entry = entry,
                                    onLongPress = { onActionsForId(entry.id) },
                                )

                                else -> AssistantTurn(
                                    entry = entry,
                                    onLongPress = { onActionsForId(entry.id) },
                                )
                            }
                        }
                    }
                }

                JumpToLatestButton(
                    visible = followTail.isDetached && state.transcript.isNotEmpty(),
                    onClick = followTail::jumpToLatest,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                )
            }

            state.error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (state.isCompacting) {
                Metric(
                    text = "Folding earlier turns into a summary…",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            ContextMeter(used = state.contextUsed, total = state.contextSize)

            Composer(
                enabled = state.canSend,
                isGenerating = state.isGenerating,
                onSend = onSend,
                onStop = onStop,
                onCommand = { command ->
                    when (command) {
                        SlashCommand.NEW_CHAT -> onNewChat()
                        SlashCommand.COMPACT -> onCompact()
                        SlashCommand.REGENERATE -> onRegenerate()
                    }
                },
            )
        }
    }

    ChatSheets(
        state = state,
        actionsFor = actionsFor,
        showParameters = showParameters,
        onDismissParameters = { showParameters = false },
        onSavePreferences = onSavePreferences,
        onResetPreferences = onResetPreferences,
        onRegenerate = onRegenerate,
        onDismissActions = { onActionsForId(null) },
    )
}

/** The modal sheets the chat screen can raise: model settings and message actions. */
@Composable
@Suppress("LongParameterList")
private fun ChatSheets(
    state: ChatUiState,
    actionsFor: TranscriptEntry?,
    showParameters: Boolean,
    onDismissParameters: () -> Unit,
    onSavePreferences: (ModelPreferences) -> Unit,
    onResetPreferences: () -> Unit,
    onRegenerate: () -> Unit,
    onDismissActions: () -> Unit,
) {
    if (showParameters && state.modelName != null) {
        ParameterSheet(
            modelName = state.modelName,
            preferences = state.preferences,
            onSave = {
                onSavePreferences(it)
                onDismissParameters()
            },
            onReset = {
                onResetPreferences()
                onDismissParameters()
            },
            onDismiss = onDismissParameters,
        )
    }

    actionsFor?.let { entry ->
        MessageActionsSheet(
            entry = entry,
            canRegenerate = entry.role == ChatRole.ASSISTANT && !state.isGenerating,
            onRegenerate = {
                onRegenerate()
                onDismissActions()
            },
            onDismiss = onDismissActions,
        )
    }
}

/**
 * Only offered when it is useful: the reader has scrolled away from live output. Tapping it
 * re-attaches, matching every other chat app.
 */
@Composable
private fun JumpToLatestButton(visible: Boolean, onClick: () -> Unit, modifier: Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(initialScale = 0.8f),
        exit = fadeOut() + scaleOut(targetScale = 0.8f),
        modifier = modifier,
    ) {
        SmallFloatingActionButton(
            onClick = onClick,
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Icon(
                imageVector = Icons.Rounded.ArrowDownward,
                contentDescription = "Jump to the latest message",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ModelChip(state: ChatUiState, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = state.modelName ?: "Choose a model",
            style = MaterialTheme.typography.titleMedium,
        )
        state.modelQuantization?.let { quantization -> Metric(quantization) }
    }
}

@Composable
private fun UserTurn(entry: TranscriptEntry, onLongPress: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Text(
            text = entry.text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(14.dp))
                .combinedClickable(onClick = {}, onLongClick = onLongPress)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/**
 * A model reply: reasoning collapsed above, markdown answer below, both beside a rail
 * coloured by how fast this reply was produced. The reply is the artifact, so it is not
 * boxed into a bubble the way the user's own message is.
 */
@Composable
private fun AssistantTurn(entry: TranscriptEntry, onLongPress: () -> Unit) {
    // Intrinsic height lets the rail match the exact height of the reply beside it.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        SpeedRail(tokensPerSecond = entry.tokensPerSecond)
        Column(
            modifier = Modifier
                .padding(start = 12.dp)
                .combinedClickable(onClick = {}, onLongClick = onLongPress),
        ) {
            if (!entry.isStreaming && entry.tokensPerSecond != null) {
                Metric(entry.readout())
            }
            entry.reasoning?.let { reasoning ->
                ReasoningBlock(
                    reasoning = reasoning,
                    isInProgress = entry.isReasoningInProgress,
                    durationMs = entry.reasoningMs,
                )
            }
            entry.toolCalls.forEach { call ->
                // A tool call is a step the model took, not prose. Showing the arguments
                // verbatim is the point: an agent whose actions you cannot inspect is one
                // you cannot trust on your own phone.
                Metric("→ ${call.name}(${call.argumentsJson})")
            }

            when {
                entry.answer.isNotEmpty() -> MarkdownText(entry.answer)
                entry.toolCalls.isNotEmpty() -> Unit
                entry.isReasoningInProgress -> Unit // the reasoning header is the status
                else -> Text(
                    text = "…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val MILLIS_PER_SECOND = 1000.0

private fun TranscriptEntry.readout(): String {
    val locale = Locale.getDefault()
    val speed = tokensPerSecond?.let { String.format(locale, "%.1f tok/s", it) }
    val ttft = timeToFirstTokenMs?.let {
        String.format(locale, "%.2fs to first token", it / MILLIS_PER_SECOND)
    }
    val tokens = generatedTokens?.let { "$it tokens" }
    return listOfNotNull(speed, ttft, tokens).joinToString(" · ")
}

@Composable
private fun EmptyState(isLoadingModel: Boolean, hasModel: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            isLoadingModel -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Loading the model into memory",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            hasModel -> Text(
                "Ready. Ask it anything — nothing leaves this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            else -> {
                Text("No model yet.", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Find one on Hugging Face and OpenWeights will tell you whether it " +
                        "runs on this phone before you download it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Marks where older turns were folded away, so the jump in the conversation is explained
 * rather than mysterious. The turns themselves are still above it.
 */
@Composable
private fun CompactionMarker(note: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Metric(note)
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Composer(
    enabled: Boolean,
    isGenerating: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onCommand: (SlashCommand) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val commands = SlashCommand.match(draft)

    if (commands != null) {
        SlashCommandPalette(
            commands = commands,
            onSelect = { command ->
                draft = ""
                onCommand(command)
            },
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message", style = MaterialTheme.typography.bodyLarge) },
            textStyle = MaterialTheme.typography.bodyLarge,
            maxLines = 6,
            shape = RoundedCornerShape(20.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                unfocusedIndicatorColor = MaterialTheme.colorScheme.outline,
            ),
        )

        FilledIconButton(
            onClick = {
                if (isGenerating) {
                    onStop()
                } else if (draft.isNotBlank()) {
                    onSend(draft)
                    draft = ""
                }
            },
            enabled = isGenerating || (enabled && draft.isNotBlank()),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(
                imageVector = if (isGenerating) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
                contentDescription = if (isGenerating) "Stop generating" else "Send message",
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0E11)
@Composable
private fun ChatScreenPreview() {
    OpenWeightsTheme(dynamicColor = false) {
        ChatScreen(
            state = ChatUiState(
                modelName = "LFM2.5-2.6B-Q4_K_M",
                modelQuantization = "lfm2 2.6B Q4_K - Medium",
                contextUsed = 1204,
                contextSize = 4096,
                transcript = listOf(
                    TranscriptEntry(id = 1, role = ChatRole.USER, text = "What is a KV cache?"),
                    TranscriptEntry(
                        id = 2,
                        role = ChatRole.ASSISTANT,
                        text = "<think>Keep it to one sentence.</think>It stores the key " +
                            "and value tensors already computed for previous tokens.",
                        tokensPerSecond = 16.4,
                        timeToFirstTokenMs = 274,
                        generatedTokens = 38,
                        reasoningMs = 1400,
                    ),
                ),
            ),
            onSend = {},
            onStop = {},
            onRegenerate = {},
            onNewChat = {},
            onCompact = {},
        )
    }
}
