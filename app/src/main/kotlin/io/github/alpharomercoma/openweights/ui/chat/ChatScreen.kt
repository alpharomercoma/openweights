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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.MessagePart
import io.github.alpharomercoma.openweights.core.data.ModelPreferences
import io.github.alpharomercoma.openweights.core.data.ReportReason
import io.github.alpharomercoma.openweights.core.designsystem.component.ContextMeter
import io.github.alpharomercoma.openweights.core.designsystem.component.FAST_TOKENS_PER_SECOND
import io.github.alpharomercoma.openweights.core.designsystem.component.MarkdownText
import io.github.alpharomercoma.openweights.core.designsystem.component.Metric
import io.github.alpharomercoma.openweights.core.designsystem.component.ReasoningBlock
import io.github.alpharomercoma.openweights.core.designsystem.component.rememberFollowTailState
import io.github.alpharomercoma.openweights.core.designsystem.theme.LocalIsDarkTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.MetricTextStyle
import io.github.alpharomercoma.openweights.core.designsystem.theme.Motion
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius
import io.github.alpharomercoma.openweights.core.designsystem.theme.signalColor
import io.github.alpharomercoma.openweights.core.tools.AgentMode
import io.github.alpharomercoma.openweights.model.DictationState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    onAttach: (Uri) -> Unit = {},
    onRemoveStaged: (MessagePart.File) -> Unit = {},
    onToggleReadAloud: (String) -> Unit = {},
    isSpeaking: Boolean = false,
    newCaptureUri: () -> Uri = { Uri.EMPTY },
    dictation: DictationState = DictationState(),
    canDictate: Boolean = false,
    onDictate: ((String) -> Unit) -> Unit = {},
    onReport: (TranscriptEntry, ReportReason, String) -> Unit = { _, _, _ -> },
    onMode: (AgentMode) -> Unit = {},
    onApproval: (Boolean) -> Unit = {},
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
            onAttach = onAttach,
            onRemoveStaged = onRemoveStaged,
            onToggleReadAloud = onToggleReadAloud,
            isSpeaking = isSpeaking,
            newCaptureUri = newCaptureUri,
            dictation = dictation,
            canDictate = canDictate,
            onDictate = onDictate,
            onReport = onReport,
            onMode = onMode,
            onApproval = onApproval,
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
    onAttach: (Uri) -> Unit,
    onRemoveStaged: (MessagePart.File) -> Unit,
    onToggleReadAloud: (String) -> Unit,
    isSpeaking: Boolean,
    newCaptureUri: () -> Uri,
    dictation: DictationState,
    canDictate: Boolean,
    onDictate: ((String) -> Unit) -> Unit,
    onReport: (TranscriptEntry, ReportReason, String) -> Unit,
    onMode: (AgentMode) -> Unit,
    onApproval: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val actionsFor = actionsForId?.let { id -> state.transcript.firstOrNull { it.id == id } }
    var showParameters by remember { mutableStateOf(false) }
    var showAttachments by remember { mutableStateOf(false) }
    val clipboard = rememberMessageClipboard()

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        // The top bar applies the status-bar inset itself and the app's navigation bar owns
        // the bottom one, so this scaffold must not add either, doing both is what left the
        // chrome floating away from the edges it belongs to.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { RuntimeBar(state = state, onClick = onOpenModels) },
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
            // Only once something has scrolled under it. A permanent rule draws a line
            // across an empty screen; an absent one lets a thumbnail collide with the
            // model name. Appearing on demand is the right behaviour for both.
            if (listState.canScrollBackward) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
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
                    Transcript(
                        state = state,
                        listState = listState,
                        isSpeaking = isSpeaking,
                        clipboard = clipboard,
                        onActionsForId = onActionsForId,
                        onToggleReadAloud = onToggleReadAloud,
                        onRegenerate = onRegenerate,
                    )
                }

                JumpToLatestButton(
                    visible = followTail.isDetached && state.transcript.isNotEmpty(),
                    onClick = followTail::jumpToLatest,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                )
            }

            StatusStrip(state = state, dictationError = dictation.error)
            state.pendingApproval?.let { call ->
                ToolApproval(call = call, onAnswer = onApproval)
            }

            Composer(
                conversationKey = state.activeConversationId,
                enabled = state.canSend,
                isGenerating = state.isGenerating,
                staged = state.staged,
                canAttach = state.mediaSupport.any,
                isAttaching = state.isAttaching,
                canDictate = canDictate,
                isListening = dictation.isListening,
                heard = dictation.partial,
                onAttach = { showAttachments = true },
                onRemoveStaged = onRemoveStaged,
                onDictate = onDictate,
                onSend = onSend,
                onStop = onStop,
                onCommand = { command ->
                    when (command) {
                        SlashCommand.NEW_CHAT -> onNewChat()
                        SlashCommand.COMPACT -> onCompact()
                        SlashCommand.REGENERATE -> onRegenerate()
                        SlashCommand.PLAN -> onMode(AgentMode.PLAN)
                        SlashCommand.AUTO -> onMode(AgentMode.AUTO)
                        SlashCommand.ASK -> onMode(AgentMode.ASK)
                    }
                },
            )
        }
    }

    if (showAttachments) {
        AttachmentSheet(
            support = state.mediaSupport,
            newCaptureUri = newCaptureUri,
            onPicked = onAttach,
            onDismiss = { showAttachments = false },
        )
    }

    ChatSheets(
        state = state,
        actionsFor = actionsFor,
        showParameters = showParameters,
        onDismissParameters = { showParameters = false },
        onSavePreferences = onSavePreferences,
        onResetPreferences = onResetPreferences,
        onRegenerate = onRegenerate,
        onToggleReadAloud = onToggleReadAloud,
        isSpeaking = isSpeaking,
        onDismissActions = { onActionsForId(null) },
        onReport = onReport,
    )
}

/**
 * The narrow band between the transcript and the composer.
 *
 * Everything here is transient. An error, a compaction in progress, how full the context
 * is, and all of it belongs next to the composer rather than in the transcript, because
 * none of it is something the model said.
 */
@Composable
private fun StatusStrip(state: ChatUiState, dictationError: String?) {
    (state.error ?: dictationError)?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }

    if (state.isCompacting) {
        Metric(
            text = "Folding earlier turns into a summary…",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }

    // Only once there is something to report. An empty meter is a hairline that reads as a
    // stray divider above the composer.
    if (state.contextUsed > 0 && state.contextSize > 0) {
        ContextMeter(used = state.contextUsed, total = state.contextSize)
    }
}

/**
 * The conversation itself.
 *
 * Split out from the screen because the screen is a layout, chrome, composer, sheets,
 * and this is the content. Keeping them apart is also what lets the transcript be reasoned
 * about on its own when scroll behaviour needs attention.
 */
@Composable
@Suppress("LongParameterList")
private fun Transcript(
    state: ChatUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isSpeaking: Boolean,
    clipboard: MessageClipboard,
    onActionsForId: (Long?) -> Unit,
    onToggleReadAloud: (String) -> Unit,
    onRegenerate: () -> Unit,
) {
    val lastId = state.transcript.lastOrNull()?.id

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(state.transcript, key = { it.id }) { entry ->
            entry.compactionNote?.let { note -> CompactionMarker(note) }
            when (entry.role) {
                ChatRole.USER -> UserTurn(
                    entry = entry,
                    onLongPress = { onActionsForId(entry.id) },
                )

                else -> AssistantTurn(
                    entry = entry,
                    isSpeaking = isSpeaking,
                    onLongPress = { onActionsForId(entry.id) },
                    onCopy = { clipboard.copy(entry.answer.ifEmpty { entry.text }) },
                    onReadAloud = { onToggleReadAloud(entry.answer.ifEmpty { entry.text }) },
                    // Only the last reply can be retried: regenerating an earlier one would
                    // silently discard everything said after it.
                    onRetry = onRegenerate.takeIf { entry.id == lastId && !state.isGenerating },
                )
            }
        }
    }
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
    onToggleReadAloud: (String) -> Unit,
    isSpeaking: Boolean,
    onDismissActions: () -> Unit,
    onReport: (TranscriptEntry, ReportReason, String) -> Unit,
) {
    var reportFor by remember { mutableStateOf<TranscriptEntry?>(null) }

    if (showParameters && state.modelName != null) {
        ParameterSheet(
            modelName = state.modelName,
            preferences = state.preferences,
            supportsThinking = state.supportsThinking,
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

    reportFor?.let { entry ->
        ReportSheet(
            modelName = state.modelName.orEmpty(),
            replyText = entry.answer.ifEmpty { entry.text },
            onSubmit = { reason, note ->
                onReport(entry, reason, note)
                reportFor = null
            },
            onDismiss = { reportFor = null },
        )
    }

    actionsFor?.let { entry ->
        MessageActionsSheet(
            entry = entry,
            // Same rule as the inline action: regenerating an earlier reply would
            // silently discard every turn that came after it.
            canRegenerate = entry.role == ChatRole.ASSISTANT &&
                !state.isGenerating &&
                entry.id == state.transcript.lastOrNull()?.id,
            isSpeaking = isSpeaking,
            onToggleReadAloud = { onToggleReadAloud(entry.answer.ifEmpty { entry.text }) },
            onRegenerate = {
                onRegenerate()
                onDismissActions()
            },
            onReport = {
                onDismissActions()
                reportFor = entry
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
        enter = fadeIn(Motion.quick()) + scaleIn(Motion.quick(), initialScale = 0.8f),
        exit = fadeOut(Motion.instant()) + scaleOut(Motion.instant(), targetScale = 0.8f),
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
private fun UserTurn(entry: TranscriptEntry, onLongPress: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (entry.attachments.isNotEmpty()) {
            SentAttachments(entry.attachments)
        }
        // A message can be attachments alone, in which case there is no bubble to draw.
        if (entry.text.isNotBlank()) {
            Text(
                text = entry.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(Radius.md))
                    .combinedClickable(onClick = {}, onLongClick = onLongPress)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * A model reply: reasoning collapsed above, markdown answer below, both beside a rail
 * coloured by how fast this reply was produced. The reply is the artifact, so it is not
 * boxed into a bubble the way the user's own message is.
 */
@Composable
@Suppress("LongParameterList")
private fun AssistantTurn(
    entry: TranscriptEntry,
    isSpeaking: Boolean,
    onLongPress: () -> Unit,
    onCopy: () -> Unit,
    onReadAloud: () -> Unit,
    onRetry: (() -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress),
        ) {
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

            // Only once the reply has finished: actions on a half-written answer copy half
            // an answer, and a retry mid-stream is a stop the user did not ask for.
            if (!entry.isStreaming && entry.answer.isNotEmpty()) {
                MessageActions(
                    isSpeaking = isSpeaking,
                    onCopy = onCopy,
                    onReadAloud = onReadAloud,
                    onRetry = onRetry,
                    modifier = Modifier.padding(top = 2.dp),
                    measurements = entry.tokensPerSecond?.let { { Measurements(entry) } },
                )
            }
        }
    }
}

private const val MILLIS_PER_SECOND = 1000.0

/**
 * The measurements under a reply.
 *
 * This replaced a coloured rail down the left of every model turn. The rail cost a gutter
 * on every reply on a screen that has none to spare, and it said one thing, throughput,
 * that this line already says in words. Colour now lands on the number it describes, which
 * is where it was always most useful.
 */
@Composable
private fun Measurements(entry: TranscriptEntry) {
    val dark = LocalIsDarkTheme.current
    val locale = LocalConfiguration.current.locales[0]
    val speed = entry.tokensPerSecond ?: return

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = String.format(locale, "%.1f tok/s", speed),
            style = MetricTextStyle,
            color = signalColor((speed / FAST_TOKENS_PER_SECOND).toFloat(), dark),
            maxLines = 1,
            modifier = Modifier.semantics {
                contentDescription = "Generated at ${speed.roundToInt()} tokens per second"
            },
        )
        // Two numbers, not four. How fast it wrote and how long the wait was are the two
        // anyone reads at a glance; time to first token and the token count are detail,
        // and detail belongs in the long press sheet where there is room for it.
        entry.totalMillis?.let { total ->
            Metric(
                text = String.format(locale, "%.1fs", total / MILLIS_PER_SECOND),
                maxLines = 1,
            )
        }
    }
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Loading the model into memory",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            hasModel -> Text(
                "Ready. Ask it anything. Nothing leaves this device.",
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

@Preview(showBackground = true, backgroundColor = 0xFF0B0D0F)
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
