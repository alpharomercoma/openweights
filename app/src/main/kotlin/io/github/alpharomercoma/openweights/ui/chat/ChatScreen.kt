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
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.core.common.context.TaskPlan
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.MessagePart
import io.github.alpharomercoma.openweights.core.common.model.ReasoningEffort
import io.github.alpharomercoma.openweights.core.data.ModelPreferences
import io.github.alpharomercoma.openweights.core.data.ReportReason
import io.github.alpharomercoma.openweights.core.designsystem.component.AccentButton
import io.github.alpharomercoma.openweights.core.designsystem.component.ContextMeter
import io.github.alpharomercoma.openweights.core.designsystem.component.FAST_TOKENS_PER_SECOND
import io.github.alpharomercoma.openweights.core.designsystem.component.Mark
import io.github.alpharomercoma.openweights.core.designsystem.component.MarkdownText
import io.github.alpharomercoma.openweights.core.designsystem.component.Metric
import io.github.alpharomercoma.openweights.core.designsystem.component.ReasoningBlock
import io.github.alpharomercoma.openweights.core.designsystem.component.hasHiddenTail
import io.github.alpharomercoma.openweights.core.designsystem.component.rememberFollowTailState
import io.github.alpharomercoma.openweights.core.designsystem.theme.LocalIsDarkTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.MetricTextStyle
import io.github.alpharomercoma.openweights.core.designsystem.theme.Motion
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius
import io.github.alpharomercoma.openweights.core.designsystem.theme.signalColor
import io.github.alpharomercoma.openweights.core.device.ThermalLevel
import io.github.alpharomercoma.openweights.core.tools.AgentMode
import io.github.alpharomercoma.openweights.core.tools.UserQuestion
import io.github.alpharomercoma.openweights.model.DictationState
import io.github.alpharomercoma.openweights.ui.models.LocalModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList")
fun ChatScreen(
    state: ChatUiState,
    onSend: (String) -> Boolean,
    onStop: () -> Unit,
    onRegenerate: () -> Unit,
    onNewChat: () -> Unit,
    onCompact: () -> Unit,
    /** Everywhere you can go from here. See [ChatDestinations]. */
    destinations: ChatDestinations = ChatDestinations(),
    /** What is on the phone, for the picker the model name raises. */
    installedModels: List<LocalModel> = emptyList(),
    onSelectModel: (LocalModel) -> Unit = {},
    modifier: Modifier = Modifier,
    onOpenConversation: (Long) -> Unit = {},
    /** The drawer's chat search, which is its own flow rather than part of [state]. */
    chatSearch: ChatSearchState = ChatSearchState(),
    onSearchConversations: (String) -> Unit = {},
    onDeleteConversation: (Long) -> Unit = {},
    onSavePreferences: (ModelPreferences) -> Unit = {},
    onResetPreferences: () -> Unit = {},
    onAttach: (Uri) -> Unit = {},
    onAttachDocument: (Uri) -> Unit = {},
    onRemoveDocument: () -> Unit = {},
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
    plan: TaskPlan? = null,
    onTickStep: (Int) -> Unit = {},
    question: UserQuestion? = null,
    onAnswerQuestion: (String) -> Unit = {},
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

    // Back closes the drawer before it does anything else.
    //
    // Material's ModalNavigationDrawer does not handle this for us, and with the bottom bar
    // gone the drawer is the only way to reach three destinations, so it is opened far more
    // often than it used to be. Without this, back from an open drawer went straight past
    // it and finished the activity: the app closed, which is the wrong answer to a gesture
    // that means "not this".
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

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
                // Closed on the way out, so returning from Settings does not land back on an
                // open drawer over the conversation.
                destinations = ChatDestinations(
                    onOpenTools = {
                        scope.launch { drawerState.close() }
                        destinations.onOpenTools()
                    },
                    onOpenUsage = {
                        scope.launch { drawerState.close() }
                        destinations.onOpenUsage()
                    },
                    onOpenSettings = {
                        scope.launch { drawerState.close() }
                        destinations.onOpenSettings()
                    },
                ),
                onNewChat = {
                    onNewChat()
                    scope.launch { drawerState.close() }
                },
                nowMillis = System.currentTimeMillis(),
                search = chatSearch.query,
                results = chatSearch.results,
                hasSearchAnswer = chatSearch.hasAnswer,
                onSearch = onSearchConversations,
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
            destinations = destinations,
            installedModels = installedModels,
            onSelectModel = onSelectModel,
            onOpenHistory = { scope.launch { drawerState.open() } },
            onSavePreferences = onSavePreferences,
            onResetPreferences = onResetPreferences,
            onAttach = onAttach,
            onAttachDocument = onAttachDocument,
            onRemoveDocument = onRemoveDocument,
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
            plan = plan,
            onTickStep = onTickStep,
            question = question,
            onAnswerQuestion = onAnswerQuestion,
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
    onSend: (String) -> Boolean,
    onStop: () -> Unit,
    onRegenerate: () -> Unit,
    onNewChat: () -> Unit,
    onCompact: () -> Unit,
    destinations: ChatDestinations,
    installedModels: List<LocalModel>,
    onSelectModel: (LocalModel) -> Unit,
    onOpenHistory: () -> Unit,
    onSavePreferences: (ModelPreferences) -> Unit,
    onResetPreferences: () -> Unit,
    onAttach: (Uri) -> Unit,
    onAttachDocument: (Uri) -> Unit,
    onRemoveDocument: () -> Unit,
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
    plan: TaskPlan?,
    onTickStep: (Int) -> Unit,
    question: UserQuestion?,
    onAnswerQuestion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val actionsFor = actionsForId?.let { id -> state.transcript.firstOrNull { it.id == id } }
    var showParameters by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
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
                // The name raises the picker rather than leaving for another screen: which
                // model is answering is a fact about this conversation, so changing it should
                // not take the conversation off screen.
                title = { RuntimeBar(state = state, onClick = { showModelPicker = true }) },
                navigationIcon = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Rounded.Menu, contentDescription = "Past chats")
                    }
                },
                actions = {
                    if (state.modelName != null) {
                        // Starting a chat was only in the drawer, which is two taps and a
                        // slide for the thing people do most often between questions. Left of
                        // the sampler icon so the pair reads outermost-first: begin something,
                        // then adjust it. It only starts one when there is something to say
                        // to, which is the same condition the sampler is under.
                        IconButton(onClick = onNewChat) {
                            Icon(Icons.Rounded.Add, contentDescription = "New chat")
                        }
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
        // Held to a readable width and centred rather than filled to the window's edges.
        //
        // On a phone this changes nothing, because the window is narrower than the cap. It
        // is for the sizes nobody was looking at: rendered at a ten inch tablet's 900dp the
        // reply ran the full width at about a hundred and sixty characters a line, twice
        // what anyone reads comfortably, with the composer stretched to match and a band of
        // empty graphite down the middle where the conversation should have been. The same
        // applies to a foldable, which is a phone right up until it is not.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // One modifier for the bottom edge, covering the gesture bar and the
                // keyboard together. safeDrawing is their union, so unlike chaining
                // navigationBarsPadding() and imePadding() there is no order to get wrong
                // and no way to pad twice by the bar's height when the keyboard is up.
                //
                // It also resolves to exactly zero while the shell still has a bottom bar,
                // because windowInsetsPadding only applies what an ancestor has not already
                // consumed, and the shell consumes the bar's height. That is what lets this
                // land before the bar is deleted rather than in the same breath as it.
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
            contentAlignment = Alignment.TopCenter,
        ) {
            // The cap before the fill, not after. Written the other way round fillMaxSize
            // fixes the width at the window's before widthIn is reached, and a maximum below
            // a minimum is resolved in the minimum's favour, so the cap silently did nothing
            // and the render came back byte for byte identical.
            Column(modifier = Modifier.widthIn(max = READABLE_WIDTH).fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) {
                    if (state.transcript.isEmpty()) {
                        EmptyState(
                            isLoadingModel = state.isLoadingModel,
                            hasModel = state.modelName != null,
                            onBrowseModels = destinations.onBrowseModels,
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
                        // Detached is not enough on its own: a transcript that fits the screen
                        // is detached the moment you touch it, and offering to scroll to a
                        // bottom already in view is an offer that reads as a bug. Only once
                        // there is a screenful or so out of sight below.
                        visible = followTail.isDetached && listState.hasHiddenTail(),
                        onClick = followTail::jumpToLatest,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp),
                    )
                }

                StatusStrip(state = state, dictationError = dictation.error)
                plan?.let { PlanCard(plan = it, onTick = onTickStep) }
                question?.let { QuestionCard(question = it, onAnswer = onAnswerQuestion) }
                state.pendingApproval?.let { call ->
                    ToolApproval(call = call, onAnswer = onApproval)
                }

                val dispatch: (SlashCommand) -> Unit = {
                    it.run(onNewChat, onCompact, onRegenerate, onMode)
                }

                // Not before there is a model on the phone at all.
                //
                // A message box that cannot send is a dead affordance: it invites typing, it
                // takes the focus, and the keyboard comes up over a screen whose only real
                // control is the button in the middle. Hidden on the true first run only,
                // when nothing is installed, rather than whenever nothing happens to be
                // loaded, so it does not flicker away while a model is coming into memory.
                if (installedModels.isNotEmpty() || state.modelName != null) {
                    Composer(
                        conversationKey = state.activeConversationId,
                        enabled = state.canSend,
                        isGenerating = state.isGenerating,
                        staged = state.staged,
                        document = state.stagedDocument,
                        onRemoveDocument = onRemoveDocument,
                        isAttaching = state.isAttaching,
                        canDictate = canDictate,
                        isListening = dictation.isListening,
                        heard = dictation.partial,
                        onAttach = { showAttachments = true },
                        leading = {
                            ThinkingControl(
                                supportsEffort = state.supportsReasoningEffort,
                                supportsThinking = state.supportsThinking,
                                effort = ReasoningEffort.fromName(
                                    state.preferences.reasoningEffort,
                                ),
                                thinking = state.preferences.thinking,
                                enabled = state.canSend,
                                onEffort = {
                                    onSavePreferences(
                                        state.preferences.copy(reasoningEffort = it.name),
                                    )
                                },
                                onThinking = {
                                    onSavePreferences(state.preferences.copy(thinking = it))
                                },
                            )
                        },
                        onRemoveStaged = onRemoveStaged,
                        onDictate = onDictate,
                        // A command that was typed out and sent runs, rather than going to the model
                        // as text for it to answer. The palette is how these are found; it was also
                        // the only way to run one, which anybody who already knew the word found out
                        // by watching the model reply to "/plan".
                        onSend = { typed ->
                            val command = SlashCommand.typed(typed)
                            if (command != null) {
                                dispatch(command)
                                true
                            } else {
                                onSend(typed)
                            }
                        },
                        onStop = onStop,
                        onCommand = dispatch,
                    )
                }
            }
        }
    }

    if (showModelPicker) {
        ModelPickerSheet(
            models = installedModels,
            activeName = state.modelName,
            onSelect = {
                onSelectModel(it)
                showModelPicker = false
            },
            onBrowse = {
                showModelPicker = false
                destinations.onBrowseModels()
            },
            onManage = {
                showModelPicker = false
                destinations.onManageModels()
            },
            onDismiss = { showModelPicker = false },
        )
    }

    if (showAttachments) {
        AttachmentSheet(
            support = state.mediaSupport,
            newCaptureUri = newCaptureUri,
            onPicked = onAttach,
            onPickedDocument = onAttachDocument,
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
                    // Only the reply being written says what the runtime is doing. On an
                    // older one it would be describing work for a different message.
                    activity = state.runtimeState.takeIf {
                        entry.id == lastId && entry.isStreaming
                    },
                    thermal = state.thermal,
                    celsius = state.deviceCelsius,
                    isSpeaking = isSpeaking,
                    onMore = { onActionsForId(entry.id) },
                    // The answer, not the reasoning and not the tool steps: what a reader
                    // means by "copy the reply" is the reply.
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
            hasGpu = state.hasGpu,
            offloadBuffers = state.offloadBuffers,
            loadedContext = state.contextSize,
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
            // Selectable as well, for the same reason as the reply. The long press stays,
            // because this bubble has no action row to hang a control off; selection wins
            // inside the text and the press opens the sheet from the padding around it.
            SelectionContainer {
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
    activity: RuntimeState?,
    thermal: ThermalLevel,
    celsius: Float?,
    isSpeaking: Boolean,
    onMore: () -> Unit,
    onCopy: () -> Unit,
    onReadAloud: () -> Unit,
    onRetry: (() -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Selectable, which it was not.
        //
        // The whole turn was one `combinedClickable`, so a long press highlighted the block
        // end to end and opened the actions sheet: there was no way to take a sentence out
        // of an answer, which is most of what anybody does with a model's output. A
        // SelectionContainer gives back the ordinary Android behaviour, handles and the
        // system toolbar included, across the reasoning and the answer together.
        //
        // Losing the long press means the sheet needs a way in that a reader can see, which
        // is the "More" action in the row underneath. That is where ChatGPT and Claude both
        // put it, and a visible control beats a gesture nobody was told about.
        SelectionContainer {
            Column {
                entry.reasoning?.let { reasoning ->
                    ReasoningBlock(
                        reasoning = reasoning,
                        isInProgress = entry.isReasoningInProgress,
                        durationMs = entry.reasoningMs,
                    )
                }

                // In the order they happened, between the thinking that led to them and the
                // answer they fed. These outlive a pass, which is what the field they
                // replaced did not: it was overwritten by the next one, so nothing was ever
                // visible.
                entry.blocks.forEach { block ->
                    when (block) {
                        is TurnBlock.Step -> ToolStepBlock(block.step)
                        is TurnBlock.Said -> IntermediateText(block.text)
                    }
                }

                if (entry.answer.isNotEmpty()) MarkdownText(entry.answer)
            }
        }

        // Last, under whatever has been written so far, because that is where the eye
        // already is while waiting. The top bar says the same thing, but a status two
        // hundred pixels above the text being read is a status nobody sees.
        activity?.takeIf { it.isBusy }?.let { ActivityLine(it, thermal, celsius) }

        // Only once the reply has finished: actions on a half-written answer copy half an
        // answer, and a retry mid-stream is a stop the user did not ask for.
        if (!entry.isStreaming && entry.answer.isNotEmpty()) {
            MessageActions(
                isSpeaking = isSpeaking,
                onCopy = onCopy,
                onReadAloud = onReadAloud,
                onRetry = onRetry,
                onMore = onMore,
                modifier = Modifier.padding(top = 2.dp),
                measurements = entry.tokensPerSecond?.let { { Measurements(entry) } },
            )
        }
    }
}

/**
 * The widest the conversation is allowed to get, however wide the window is.
 *
 * Prose stops being comfortable somewhere past seventy or eighty characters a line, and a
 * ten inch tablet at this text size is well over twice that. Material caps a reading pane
 * for the same reason.
 */
private val READABLE_WIDTH = 640.dp

private const val MILLIS_PER_SECOND = 1000.0

/**
 * The measurements under a reply.
 *
 * This replaced a coloured rail down the left of every model turn. The rail cost a gutter
 * on every reply on a screen that has none to spare, and it said one thing, throughput,
 * that this line already says in words. Colour now lands on the number it describes, which
 * is where it was always most useful.
 */
/**
 * Something the model said between tool calls.
 *
 * Dimmed rather than hidden. It is real output and it is what explains why the next call
 * was the one it made, but it was written before the results came back, so presenting it
 * with the same weight as the answer would give equal standing to a guess and a finding.
 */
/**
 * What the runtime is doing right now, at the end of the reply being written.
 *
 * Reading a prompt and writing an answer look identical from outside: both are a screen
 * that has not changed. They are also the two halves whose cost is completely different on
 * a phone, and after a tool returns the model re-reads everything, which is the longest
 * silence in a turn and the one that reads as a hang.
 *
 * A pulse rather than a spinner, and the elapsed seconds, which is the number that tells
 * someone whether to keep waiting. Timed from when the phase started, so it restarts when
 * the phase does rather than counting the whole turn.
 */
@Composable
private fun ActivityLine(
    state: RuntimeState,
    thermal: ThermalLevel,
    celsius: Float?,
    modifier: Modifier = Modifier,
) {
    var seconds by remember(state) { mutableIntStateOf(0) }
    LaunchedEffect(state) {
        while (true) {
            delay(1.seconds)
            seconds++
        }
    }

    val pulse = rememberInfiniteTransition(label = "activity")
    val alpha by pulse.animateFloat(
        initialValue = PULSE_DIM,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(PULSE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Row(
        modifier = modifier.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .alpha(alpha)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Text(
            text = state.label.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (seconds > 0) {
            Text(
                text = "${seconds}s",
                style = MetricTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Always, not only once the phone is hot. This is the one reading no hosted
        // assistant can show, because there the hardware warming up is somebody else's,
        // and showing it only when warm meant it was never seen: a phone on a desk at room
        // temperature is cool, so the monitor that was asked for looked like it had never
        // been built. Red when it is warm, which is the moment a hot phone would otherwise
        // be indistinguishable from a slow model.
        Text(
            // The reading if the phone will give one, and the four step word beside it once
            // the phone claims to be warm.
            //
            // Both, because they are two different measurements and only one of them is
            // ours. Android's thermal status is a policy decision made by the vendor, and
            // this reference device reports LIGHT while sitting at 25°C, so a red "25.0°C"
            // on its own said the number was the problem. Attached to the word it is the
            // system's claim, in the system's own terms, with the reading as context.
            text = listOfNotNull(
                celsius?.let {
                    String.format(LocalConfiguration.current.locales[0], "%.1f°C", it)
                },
                thermal.label.takeIf { thermal.isWarm || celsius == null },
            ).joinToString(" · ", prefix = "· "),
            style = MetricTextStyle,
            color = if (thermal.isWarm) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun IntermediateText(text: String, modifier: Modifier = Modifier) {
    // Alpha rather than a colour, so the markdown renderer keeps styling its own code
    // spans and links and only the whole block recedes.
    MarkdownText(
        content = text,
        modifier = modifier
            .padding(vertical = 4.dp)
            .alpha(INTERMEDIATE_ALPHA),
    )
}

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
private fun EmptyState(isLoadingModel: Boolean, hasModel: Boolean, onBrowseModels: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
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

            // The mark, then a question.
            //
            // It was one grey sentence, "Ready. Ask it anything. The model runs on this
            // phone.", which is three statements and no invitation: correct, cold, and the
            // first thing anybody sees. ChatGPT opens with a question, Gemini with a
            // greeting, Claude with both; all three understand that an empty screen is a
            // moment to say hello rather than to file a status report.
            //
            // So the mark carries the brand, the question carries the invitation, and the
            // one claim worth making survives underneath it in the small type where a
            // claim belongs. The claim also had to change: it said "nothing leaves this
            // device" until web search shipped switched on, and what is left is the part
            // that is true of every reply.
            hasModel -> {
                Mark(size = 44.dp)
                Text(
                    text = "Where shall we start?",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Whatever you ask is answered on this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            // A button, not a sentence pointing at one.
            //
            // On a fresh install there is no model, so the app can do precisely nothing,
            // and the one thing that has to happen next was described in prose and left
            // for the reader to go and find: "browse from the name at the top" asks
            // somebody who has never opened this app to know that the name at the top is a
            // control. The screen that can do nothing else should offer the one thing it
            // can do, in the middle, where it is the only thing to press.
            else -> {
                Mark(size = 44.dp)
                Text(
                    text = "Pick a model to begin",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Each one says whether it runs on this phone before you " +
                        "download it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                AccentButton(
                    onClick = onBrowseModels,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("Browse models", modifier = Modifier.padding(start = 8.dp))
                }

                // Said here, once, and never as a wall.
                //
                // The app's whole claim is that it answers on the phone, and web search is
                // on from the first run, so the one place that claim can be quietly wrong
                // is the one place to be plain about it. A consent dialog at install would
                // be dismissed by somebody who does not yet have a model: no context, and
                // nothing to consent about. A line here costs nothing and defuses the
                // surprise before it can happen.
                Text(
                    text = "Once a model is running, answers can search the web. " +
                        "That is a switch in Tools.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 20.dp),
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

@Preview(showBackground = true, backgroundColor = 0xFF0D0E10)
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
            onSend = { true },
            onStop = {},
            onRegenerate = {},
            onNewChat = {},
            onCompact = {},
        )
    }
}

/** Enough to read as said-on-the-way rather than as the answer. */
private const val INTERMEDIATE_ALPHA = 0.72f

/** Dim enough to read as a pulse, bright enough to stay visible on a light background. */
private const val PULSE_DIM = 0.25f

/** One breath, roughly. Faster reads as urgency, slower reads as stalled. */
private const val PULSE_MS = 700
