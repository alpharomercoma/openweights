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

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.BatteryManager
import android.os.Process
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.content.getSystemService
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.alpharomercoma.openweights.R
import io.github.alpharomercoma.openweights.core.common.context.Compaction
import io.github.alpharomercoma.openweights.core.common.context.Goal
import io.github.alpharomercoma.openweights.core.common.context.GoalState
import io.github.alpharomercoma.openweights.core.common.context.TaskPlan
import io.github.alpharomercoma.openweights.core.common.context.TaskStep
import io.github.alpharomercoma.openweights.core.common.model.AnswerLength
import io.github.alpharomercoma.openweights.core.common.model.AssistantReply
import io.github.alpharomercoma.openweights.core.common.model.ChatMessage
import io.github.alpharomercoma.openweights.core.common.model.ChatRole
import io.github.alpharomercoma.openweights.core.common.model.CompiledBackend
import io.github.alpharomercoma.openweights.core.common.model.GgufFileName
import io.github.alpharomercoma.openweights.core.common.model.MediaKind
import io.github.alpharomercoma.openweights.core.common.model.MessagePart
import io.github.alpharomercoma.openweights.core.common.model.ModelFormat
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.OutputModality
import io.github.alpharomercoma.openweights.core.common.model.ToolCall
import io.github.alpharomercoma.openweights.core.common.model.assistantHistoryText
import io.github.alpharomercoma.openweights.core.common.model.parseAssistantReply
import io.github.alpharomercoma.openweights.core.common.model.withoutToolMarkup
import io.github.alpharomercoma.openweights.core.data.ArchivedConversations
import io.github.alpharomercoma.openweights.core.data.ComputeTarget
import io.github.alpharomercoma.openweights.core.data.ConversationFiling
import io.github.alpharomercoma.openweights.core.data.ModelPreferences
import io.github.alpharomercoma.openweights.core.data.ToolStepRecord
import io.github.alpharomercoma.openweights.core.data.computeLayersFor
import io.github.alpharomercoma.openweights.core.data.db.EngineHistoryEntity
import io.github.alpharomercoma.openweights.core.data.db.MessageEntity
import io.github.alpharomercoma.openweights.core.data.db.ToolStepEntity
import io.github.alpharomercoma.openweights.core.data.decodeAttachments
import io.github.alpharomercoma.openweights.core.device.ThermalLevel
import io.github.alpharomercoma.openweights.core.engine.GenerationEvent
import io.github.alpharomercoma.openweights.core.engine.GenerationStats
import io.github.alpharomercoma.openweights.core.engine.LoadedModelInfo
import io.github.alpharomercoma.openweights.core.engine.MediaSupport
import io.github.alpharomercoma.openweights.core.engine.StopReason
import io.github.alpharomercoma.openweights.core.tools.AgentMode
import io.github.alpharomercoma.openweights.core.tools.AgentStep
import io.github.alpharomercoma.openweights.core.tools.AskBoard
import io.github.alpharomercoma.openweights.core.tools.GoalBoard
import io.github.alpharomercoma.openweights.core.tools.PlanBoard
import io.github.alpharomercoma.openweights.core.tools.SessionArtifacts
import io.github.alpharomercoma.openweights.core.tools.ToolEvidence
import io.github.alpharomercoma.openweights.core.tools.ToolNotes
import io.github.alpharomercoma.openweights.core.tools.ToolSwitches
import io.github.alpharomercoma.openweights.core.tools.WorkspaceGrant
import io.github.alpharomercoma.openweights.core.tools.correlatedWebResearchSources
import io.github.alpharomercoma.openweights.download.ModelArrivals
import io.github.alpharomercoma.openweights.model.StagedDocument
import io.github.alpharomercoma.openweights.runtime.GenerationService
import io.github.alpharomercoma.openweights.ui.ReplyNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import javax.inject.Inject

/**
 * A turn as shown in the transcript, with the measurements taken while producing it.
 *
 * [text] is the raw model output; [reasoning] and [answer] are the split view of it, so the
 * UI never has to parse and the raw form stays available for regeneration and export.
 */
data class TranscriptEntry(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val reasoning: String? = null,
    val answer: String = text,
    val isReasoningInProgress: Boolean = false,
    val reasoningMs: Long? = null,
    val tokensPerSecond: Double? = null,
    /** The prefill half of [tokensPerSecond]. See [MessageEntity.prefillTokensPerSecond]. */
    val prefillTokensPerSecond: Double? = null,
    val timeToFirstTokenMs: Long? = null,
    val generatedTokens: Int? = null,
    /**
     * This turn's prompt, cached and freshly-decoded tokens together. Null on a turn with no
     * measurement at all (a reply reloaded from storage, or one that never finished), which
     * is a different claim from zero — a turn really can prefill nothing on a full cache hit.
     */
    val promptTokens: Int? = null,
    /** How much of [promptTokens] the KV cache answered for free. See [GenerationStats.cachedTokens]. */
    val cachedTokens: Int? = null,
    /** How long the prompt took to read. See [MessageEntity.prefillMs]. */
    val prefillMs: Long? = null,
    /** How long the reply took to write. See [MessageEntity.decodeMs]. */
    val decodeMs: Long? = null,
    val isStreaming: Boolean = false,
    /** Set on the first entry that survives a compaction, so the fold is visible. */
    val compactionNote: String? = null,
    /** Files sent with this turn, shown above its text. */
    val attachments: List<MessagePart.File> = emptyList(),
    /** Everything the model said and did before the answer, in order. */
    val blocks: List<TurnBlock> = emptyList(),
    /** Wall clock from send to finished, which is what the wait actually felt like. */
    val totalMillis: Long? = null,
    /**
     * The reply exactly as it was decoded, kept only for as long as the process lives.
     *
     * [text] is what is shown and stored, and it is not the same string: see
     * [assistantHistoryText]. This is what goes back to the engine as history, because it
     * is the only version that matches the tokens still sitting in the KV cache. Null on a
     * turn that was reloaded from storage, where the cache is empty anyway and there is
     * nothing to match.
     */
    val history: String? = null,
    /**
     * The row this entry is stored as, or null when it is not stored at all.
     *
     * Null is a fact rather than a default. A question whose write failed stays on screen
     * and is answered anyway, so the transcript can run one entry ahead of the table, and
     * an edit that addressed rows by position then rewrote somebody else's question. The
     * id is set when the write lands and read back when the conversation is reopened, and
     * an entry without one cannot be edited in storage, only refused.
     */
    val storedId: Long? = null,
) {
    /**
     * An assistant turn that was opened and never written to.
     *
     * The placeholder goes on screen the moment generation starts, so that a reply appears
     * to begin immediately. If the turn then stops before a single token, that placeholder
     * is all there is: an empty bubble under the question, which reads as an answer of
     * silence rather than as a turn that never ran. Whoever ends the turn early drops it.
     */
    val saidNothing: Boolean
        get() = role == ChatRole.ASSISTANT && isStreaming && text.isBlank() && blocks.isEmpty()
}

/**
 * What one turn was opened on: the history to send, and the state it was built from.
 *
 * Both, and the same both, because the sampler settings, the tool switch and the tool notes
 * have to be the ones the prompt was assembled with. Reading them from the live state at the
 * moment the engine is called instead would take whatever this turn has already changed,
 * which is not the question the model is about to answer.
 */
private data class OpenedTurn(
    val conversation: List<ChatMessage>,
    val state: ChatUiState,
    val question: String,
)

/**
 * One rewrite of a question already asked, as a value rather than as a sequence of steps.
 *
 * Held together because the three things done with it have to agree: what the screen shows
 * while the write is in flight, what storage ends up holding, and what the screen goes back
 * to if the write fails. Two of those are the same decision read twice, and the version that
 * computed them separately is the one that let the transcript and the database disagree.
 */
private data class Edit(
    /** Where in the transcript the rewritten question sits. */
    val at: Int,
    /** The row being rewritten, or null for a question storage never took. */
    val storedId: Long?,
    val text: String,
    /** Kept with the new wording: editing a question does not detach what came with it. */
    val attachments: List<MessagePart.File>,
    /** Files belonging to turns this drops, whose copies nothing will reference again. */
    val abandoned: List<MessagePart.File>,
    /** Everything before the edited turn, which is all that survives. */
    val kept: List<TranscriptEntry>,
    val invalidatesCompaction: Boolean,
    /** The state as it was, so a failed write can put it back exactly. */
    val before: ChatUiState,
) {
    fun applied(current: ChatUiState): ChatUiState = current.copy(
        transcript = kept,
        error = null,
        // Claimed here, before any suspending work, so a second tap on a second bubble
        // cannot start a rewrite while this one is still being written.
        isGenerating = true,
        compaction = if (invalidatesCompaction) null else current.compaction,
        contextUsed = if (invalidatesCompaction) 0 else current.contextUsed,
    )

    fun rolledBack(current: ChatUiState): ChatUiState = current.copy(
        transcript = before.transcript,
        compaction = before.compaction,
        contextUsed = before.contextUsed,
        isGenerating = false,
        error = STORAGE_FAILED,
    )
}

/** The transcript without a trailing reply that never said anything. See [TranscriptEntry.saidNothing]. */
private fun List<TranscriptEntry>.withoutEmptyReply(): List<TranscriptEntry> =
    if (lastOrNull()?.saidNothing == true) dropLast(1) else this

/** A past conversation, as shown in the drawer. */
data class ConversationSummary(
    val id: Long,
    val title: String,
    val modelName: String?,
    val updatedAt: Long,
    /** When it was pinned, or null: the drawer shows pinned chats in their own section. */
    val pinnedAt: Long? = null,
    /** When it was archived, or null: archived chats leave the list for their own section. */
    val archivedAt: Long? = null,
    /** True when a half-written message is waiting in this chat's composer. */
    val hasDraft: Boolean = false,
) {
    val isPinned: Boolean get() = pinnedAt != null
    val isArchived: Boolean get() = archivedAt != null
}

/**
 * What the runtime is doing.
 *
 * Named states rather than a spinner, because on a phone these are minutes apart and they
 * mean different things: reading a prompt with four video frames in it is not the same
 * wait as generating, and a phone that has quietly halved its thread count looks exactly
 * like a slow model unless something says so.
 *
 * This is the part of the interface no cloud assistant has, because no cloud assistant is
 * running on hardware the user can feel getting warm.
 */
enum class RuntimeState(val label: String) {
    NO_MODEL("no model"),
    LOADING("loading weights"),
    PREPARING("preparing the first reply"),
    READY("ready"),
    READING("reading the prompt"),
    GENERATING("generating"),
    COMPACTING("folding earlier turns"),
    THROTTLED("cooling down"),
    ;

    /** True while the runtime is busy, which is when the state means anything. */
    val isBusy: Boolean get() = this != READY && this != NO_MODEL
}

/**
 * The engine's record of the conversation through one particular reply.
 *
 * [throughCount] is how many transcript entries [messages] stands in for, and
 * [throughEntryId] pins which entry the record runs through: a transcript whose entry at
 * that position is no longer the same one — an edit, a regenerate, a reopened chat — makes
 * the record stale, and stale reads as absent rather than as the truth.
 */
data class EngineHistory(
    val messages: List<ChatMessage>,
    val throughCount: Int,
    val throughEntryId: Long,
) {
    /** Whether this record still describes the front of [transcript]. */
    fun covers(transcript: List<TranscriptEntry>): Boolean =
        transcript.getOrNull(throughCount - 1)?.id == throughEntryId
}

/** Everything the chat screen renders. */
data class ChatUiState(
    val modelName: String? = null,
    val modelQuantization: String? = null,
    val isLoadingModel: Boolean = false,
    /** True until the first fresh-chat prefix has been warmed after a model load. */
    val isPreparingFirstResponse: Boolean = false,
    val isGenerating: Boolean = false,
    val transcript: List<TranscriptEntry> = emptyList(),
    /**
     * What the tools returned earlier in this chat, kept because the transcript does not.
     *
     * Held here rather than in the turn loop because it outlives a turn, and rather than in
     * storage because it is not part of the conversation: nothing shows it, nothing edits it,
     * and a chat reopened from disk starts it again empty. See [ToolNotes].
     */
    val toolNotes: ToolNotes = ToolNotes(),
    /**
     * The conversation exactly as the engine last read it, or null when there is no usable
     * record. What the KV cache holds is this, not the transcript: the questions carry the
     * notes and grounding they were decorated with, and the tool rounds sit between
     * question and answer as the template rendered them. A prompt built as an extension of
     * this is one the cache can answer for; a prompt rebuilt from the transcript diverges
     * at the first decorated message, which a hybrid model pays as a full re-read. See
     * [EngineHistoryEntity].
     */
    val engineHistory: EngineHistory? = null,
    val contextUsed: Int = 0,
    val contextSize: Int = 0,
    /**
     * How this model tokenises this conversation, as the last completed pass reported it.
     *
     * Null until something has been generated, which is the only time anything has to be
     * guessed. See [estimatedPromptTokens].
     */
    val charsPerToken: Float? = null,
    val error: String? = null,
    val isCompacting: Boolean = false,
    val compaction: Compaction? = null,
    val conversations: List<ConversationSummary> = emptyList(),
    /**
     * How many conversations have been filed away.
     *
     * A count rather than the rows. The drawer needs one digit to decide whether to offer
     * the way into the archive and what to write on it; the rows themselves are read by
     * the archive screen, and only while it is open.
     */
    val archivedCount: Int = 0,
    val activeConversationId: Long? = null,
    /**
     * What was half-written in this conversation's composer when it was last left, or
     * null when nothing was. Loaded when the conversation opens; the composer seeds its
     * field from it once per conversation and then owns the text.
     */
    val composerDraft: String? = null,
    val preferences: ModelPreferences = ModelPreferences(),
    /** What the loaded model can read. All false without a projector. */
    val mediaSupport: MediaSupport = MediaSupport(),
    /** What the loaded model writes, which decides which settings are worth offering. */
    val outputModality: OutputModality = OutputModality.TEXT,
    /** True when this model's chat template understands being told whether to think. */
    val supportsThinking: Boolean = false,
    /**
     * True when this model's chat template does something with the effort setting.
     *
     * Measured at load by rendering the template twice, so the control appears only where
     * moving it changes the prompt.
     */
    val supportsReasoningEffort: Boolean = false,
    /** True when at least one tool is switched on in Tools. */
    val toolsAvailable: Boolean = false,
    /** True when this device has a backend other than the CPU to offload layers to. */
    val hasGpu: Boolean = false,
    /** Whether an accelerator is enumerated; see `ModelRuntime.hasNpu`. */
    val hasNpu: Boolean = false,
    /**
     * The processor a compiled model was built for, or null for a GGUF.
     *
     * Non-null turns the two processor controls into a statement. A `.pte` holds delegate
     * identifiers and loading resolves those exact ones, so where it runs was decided when
     * somebody exported it and no setting here can move it. Saying so is better than
     * hiding the section, which reads as the app having no opinion.
     */
    val compiledProcessor: ComputeTarget? = null,
    /**
     * How hot the device is, sampled while a reply is being written.
     *
     * The one reading no hosted assistant can show, because there the hardware getting warm
     * is somebody else's. This is the four step level the system publishes, which is what
     * the scheduler acts on when it slows the phone down, so it is the one to colour by and
     * to throttle on. [deviceCelsius] is the number beside it.
     */
    val thermal: ThermalLevel = ThermalLevel.NONE,
    /**
     * The device temperature in degrees, or null where the platform will not say.
     *
     * The level above is what decides throttling; this is the number a person asked for
     * when they asked how hot their phone was.
     */
    val deviceCelsius: Float? = null,
    /** Attachments staged in the composer, not yet sent. */
    val staged: List<MessagePart.File> = emptyList(),
    /**
     * A text document staged in the composer, not yet sent.
     *
     * Separate from [staged] because it is a different thing wearing the same word. Media
     * needs a model that can see or hear and a file on disk for the projector to open; a
     * document needs neither, because it becomes part of the question. That is why this one
     * is offered whatever model is loaded.
     */
    val stagedDocument: StagedDocument? = null,
    /** True while a picked file is being copied in. */
    val isAttaching: Boolean = false,
    /** The compute device the engine is actually running on, e.g. `CPU`. */
    val backend: String? = null,
    /**
     * Every buffer holding the weights, largest first, in MiB.
     *
     * Shown beside the processor setting, because that setting is a request and this is
     * what came of it. llama.cpp reports the layer count it was asked for whether or not a
     * backend attached, so the only honest answer to "did that do anything" is which
     * buffers the tensors ended up in.
     */
    val offloadBuffers: List<Pair<String, Int>> = emptyList(),
    /**
     * True while the phone is hot enough that the thread plan has been cut back.
     *
     * Only meaningful while generating. A device cools while idle, and the reading is only
     * taken around a reply, so claiming "cooling down" on an idle screen would be showing
     * a measurement minutes out of date.
     */
    val isThrottled: Boolean = false,
    /**
     * How much rope the model gets.
     *
     * Auto by default. Asking per call sounds safer and is not: a model that searches
     * three times to answer one question produces three prompts, and a user who taps
     * through three prompts stops reading the fourth. The tools that ship cannot write
     * anything or spend anything, so the honest default is to let them run and to say
     * clearly in the transcript what ran.
     */
    val mode: AgentMode = AgentMode.AUTO,
    /** A tool waiting for the user to allow it. Null when nothing is waiting. */
    val pendingApproval: ToolCall? = null,
) {
    /**
     * The runtime's current state, in the order it matters.
     *
     * Throttling outranks everything: it explains a slowness the other states cannot, and
     * a user who does not know the phone is hot has no reason to put it down.
     */
    val runtimeState: RuntimeState
        get() = when {
            isThrottled && isGenerating -> RuntimeState.THROTTLED
            isLoadingModel -> RuntimeState.LOADING
            isPreparingFirstResponse -> RuntimeState.PREPARING
            modelName == null -> RuntimeState.NO_MODEL
            isCompacting -> RuntimeState.COMPACTING
            // No text yet means the prompt is still being read, which with an attachment
            // is most of the wait, and is the one part a spinner cannot explain.
            isGenerating && transcript.lastOrNull()?.text.isNullOrEmpty() -> RuntimeState.READING
            isGenerating -> RuntimeState.GENERATING
            else -> RuntimeState.READY
        }

    /**
     * What is loaded, in one line: quantization, compute device, context window.
     *
     * Shown whenever the runtime is idle. It is the answer to "what am I actually talking
     * to", which for this app changes with every download and is otherwise invisible.
     */
    val runtimeIdentity: String
        get() = listOfNotNull(
            // Not the quantization: it is already the tail of the model name directly
            // above, and repeating it wastes the only line that can say something new.
            computeLine,
            contextSize.takeIf { it > 0 }?.let { "$it ctx" },
            // Only when it is not the default, which is the same rule the rest of this line
            // follows. A mode was choosable by typing and then invisible: nothing anywhere
            // said the app was in plan mode, so the only evidence was tools not running.
            mode.takeIf { it != ChatUiState().mode }?.label,
        ).joinToString(" · ")

    /**
     * Where the work runs, said the way the user chose it.
     *
     * One word while both halves are automatic or agree, which is every phone until the
     * user opens the processor controls. Split into "reads X · writes Y" only when the
     * halves genuinely differ, and a half left on Auto is reported as what it resolved
     * to rather than as "auto", because the line answers what *is*, not what was asked.
     * A compiled model's processor was decided at export, so it is stated flat.
     */
    private val computeLine: String?
        get() {
            compiledProcessor?.let { return it.name }
            val reads = preferences.prefillTarget
            val writes = preferences.decodeTarget
            if (reads == ComputeTarget.AUTO && writes == ComputeTarget.AUTO) return backend
            val fallback = backend ?: return null
            val read = reads.takeIf { it != ComputeTarget.AUTO }?.name ?: fallback
            val write = writes.takeIf { it != ComputeTarget.AUTO }?.name ?: fallback
            // Compact on purpose: this line ellipsizes at small widths, and
            // "reads GPU · writes…" hides exactly the half the split exists to say.
            // Read → write, seven characters, never truncated; the settings sheet
            // carries the full words.
            return if (read == write) read else "$read→$write"
        }

    /**
     * Whether the composer may start a turn.
     *
     * [isCompacting] belongs here and was missing, which is a race rather than a nicety.
     * Folding runs after a reply, while the user is reading it, and it runs the model for
     * twenty to thirty seconds. Send during that window passed this check, reached
     * [ChatViewModel.generate], found `Folding` already busy and skipped its own fold, and
     * then called the engine while the summary was still streaming into it. Two turns on one
     * engine: the single-threaded executor keeps it from corrupting anything, and what comes
     * out is a turn that re-reads the whole conversation because the cache holds the summary
     * prompt, answered from a history the screen has already replaced. Nothing crashes and
     * nothing is right.
     *
     * [isAttaching] for a smaller version of the same race. The copy a paperclip starts is
     * not instant, and Send during it passed: the question went without the file, which
     * then sat staged and rode along with whatever was asked next.
     */
    val canSend: Boolean get() =
        modelName != null &&
            outputModality == OutputModality.TEXT &&
            !isGenerating &&
            !isLoadingModel &&
            !isPreparingFirstResponse &&
            !isCompacting &&
            !isAttaching

    /**
     * Whether the composer may be typed into, staged with a file, or dictated to.
     *
     * [canSend] minus the loading and first-prefix checks: a model coming into memory, or
     * preparing the first prompt cache, is not a reason to throw away a draft. A message
     * written while either read runs is kept — [Composer] refuses to submit it until [canSend]
     * is true, so nothing races the load or pays the cold prefill; the field itself stays open
     * for however long the preparation takes.
     */
    val canType: Boolean get() =
        modelName != null &&
            outputModality == OutputModality.TEXT &&
            !isGenerating &&
            !isCompacting
}

@HiltViewModel
// Owed work, named rather than waved away. This class is the chat screen, the turn loop and
// the goal loop in one, and ten dependencies is what carrying all three costs. The seam is
// the goal loop: `startGoal` through `stepPrompt` is fourteen functions that talk to the
// boards, the runtime and one turn at a time, and lifting them out takes this back under
// every one of these limits at once. It is not lifted here because it needs a callback
// surface back into the turn machinery, which is a design decision rather than a lint fix,
// and because the tests that make it safe to move only landed alongside this comment.
@Suppress("LongParameterList", "LargeClass", "TooManyFunctions")
class ChatViewModel @Inject constructor(
    private val runtime: ModelRuntime,
    private val compactor: ConversationCompactor,
    private val staging: Staging,
    private val writer: ChatWriter,
    /** Naming, pinning and archiving, which are about a conversation rather than in it. */
    private val filing: ConversationFiling,
    /** Only for the count the drawer shows; the archive screen reads its own rows. */
    private val archive: ArchivedConversations,
    private val turns: TurnRunner,
    private val notifier: ReplyNotifier,
    private val goals: GoalBoard,
    /** Only to hear toggles; the turn itself reads them through [turns]. */
    private val toolSwitches: ToolSwitches,
    private val workspaceGrant: WorkspaceGrant,
    /** Only to forget them on a switch; the file tools are what fill and read it. */
    private val artifacts: SessionArtifacts,
    /** Only to hear that a model finished downloading; see the collector in `init`. */
    private val arrivals: ModelArrivals,
    @param:ApplicationContext private val appContext: Context,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    /** Completed by the approval buttons, so the agent can wait on a human. */
    private var approval: CompletableDeferred<Boolean>? = null

    /**
     * One question on screen at a time.
     *
     * There is a single [approval] slot and a single card above the composer, so two
     * questions raised at once would leave the first waiting on a deferred that has been
     * replaced and can no longer be completed: the turn hangs until Stop. Tools that run
     * together are chosen so this does not arise, and this keeps the failure at "asked one
     * after the other" rather than "never answered" if that ever changes.
     */
    private val approvalGate = Mutex()

    /** Tool evidence emitted by the current goal turn; reset before every turn starts. */
    private var lastTurnSteps: List<AgentStep> = emptyList()
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /**
     * What is attached to the message being typed.
     *
     * Built here rather than injected, because it needs this view model's scope and its state,
     * and a Hilt binding for something that takes both would be a longer way to say the same.
     */
    private val attachments = Attaching(
        staging = staging,
        scope = viewModelScope,
        state = _uiState,
        limitMessage = appContext.getString(R.string.attachment_limit, MAX_STAGED_ATTACHMENTS),
        unreadableMessage = appContext.getString(R.string.attachment_unreadable),
        loadingMessage = appContext.getString(R.string.attachment_still_loading),
    )

    /** Folding older turns into a summary. Built here for the reason [attachments] is. */
    private val folding = Folding(compactor, writer, _uiState)

    private var generationJob: Job? = null

    /**
     * The background read of the fresh-conversation prefix, so nobody waits for it later.
     *
     * See [TurnRunner.warmFreshChat]. Fired after a load and when the prefix could have
     * changed; a real turn interrupts it rather than queueing, so it never costs the user
     * anything but battery the first answer was going to spend anyway.
     */
    private var warmJob: Job? = null

    /** Identifies the current warm so a cancelled warm cannot clear a newer load's readiness. */
    private var warmGeneration = 0L

    private var thermalJob: Job? = null
    private var goalJob: Job? = null

    /**
     * Overrides whether the next turn offers ask_user, consumed the moment [generate] reads
     * it so it cannot leak into the turn after the one it was set for.
     *
     * Set immediately before the one turn that needs it — a research brief's own planning
     * turn — rather than threaded as a parameter through [send], which every ordinary
     * message also calls and has no reason to know this exists.
     */
    private var offerAskOverride: Boolean? = null

    /**
     * Overrides the configured tool prompt for the next turn only, consumed by [openTurn]
     * the same way [offerAskOverride] is consumed by [generate].
     *
     * Set before a research step. The configured prompt defaults to "you already know the
     * answer to most questions", tuned for ordinary chat where a needless search is the
     * failure worth avoiding; a research step is already past that decision; the plan
     * would not have this question on it if the answer were assumed known. Sent as-is that
     * instruction argues with the very turn asking the model to search.
     */
    private var toolPromptOverride: String? = null

    /**
     * Searching the history, collected beside this state rather than inside it.
     *
     * Built here rather than injected because it needs this view model's scope: a search in
     * flight when the screen goes should go with it.
     */
    val search = ChatSearch(writer, viewModelScope)
    private var nextEntryId = 0L

    /**
     * True only while a turn is inside the engine.
     *
     * Distinct from [ChatUiState.isGenerating], which the composer sets the moment it is
     * tapped so that two quick taps cannot both start a turn. Compaction resets the KV
     * cache, so what it has to know is whether anything is decoding into it, and those two
     * facts stop agreeing for the whole window between the tap and the first token.
     */
    private var isDecoding = false

    /** Held for the length of a load, so two of them cannot interleave. */
    private val loadMutex = Mutex()

    /** Counts load requests, so a queued one can tell it has been superseded. */
    private var loadRequest = 0L

    /**
     * The filename settings are stored against.
     *
     * The full name, not the stem: two repositories can both ship `model.gguf`, and
     * sharing one system prompt between them would be silent cross-contamination.
     */
    private var preferencesKey: String? = null

    /** The weights currently loaded, kept so a turn can tell whether they are still there. */
    private var loadedFile: File? = null

    /** The row this conversation is being written to, created lazily on the first message. */
    private var conversationId: Long? = null
        set(value) {
            field = value
            // The draft goes with the conversation it was typed in. Left standing, the
            // composer seeded it into whichever chat came next: the previous chat's
            // half-written question appeared in a new chat, and a draft that had just
            // been sent reappeared the moment its conversation gained an id.
            _uiState.update { it.copy(activeConversationId = value, composerDraft = null) }
            loadComposerDraft()
            // Remembered, and never forgotten. Android reclaims a process holding a model in
            // memory sooner than most, and coming back to a blank screen with the
            // conversation still in the database reads as having lost it. SavedStateHandle
            // is the right shelf: it survives being killed for memory and does not survive
            // being swiped away, which is exactly the distinction that matters here.
            if (value != null) savedState[LAST_CONVERSATION] = value
        }

    /**
     * The conversation to reopen once a model is up, read once when the view model is built.
     *
     * Consumed by the first load, which on a cold start is the one the screen asks for. It
     * cannot fire twice, so switching model afterwards still starts a fresh chat.
     */
    private var restoring: Long? = savedState[LAST_CONVERSATION]

    init {
        loadComposerDraft()
        viewModelScope.launch {
            // A tool toggled, or the folder granted or taken back, rewrites the tool block
            // at the head of every future prompt — and for a hybrid model any head byte
            // change is a full re-read, because rollback is refused. Heard here, that
            // re-read runs now, in the background, while the user is still on the settings
            // screen; unheard, it ran in front of them on their next send, which is the
            // cold first turn this warm machinery exists to prevent. Each StateFlow replays
            // its current value on collect, so the first emission of each is dropped.
            merge(toolSwitches.changes.drop(1), workspaceGrant.changes.drop(1))
                .collect { warmEngine() }
        }
        viewModelScope.launch {
            // A model that has just finished downloading is the one case the whole warm
            // mechanism cannot help with: there is no state file to restore, so the
            // prefix has to be computed once, and until now the first person to send a
            // message paid for it — a minute of prefill on a phone that had just spent
            // several more minutes downloading, which is the worst first impression the
            // app can make.
            //
            // The download ending is the moment to spend instead. Nothing is on screen
            // that needs the engine, the user is still reading the models list, and what
            // this does is exactly what tapping through to the chat tab would have done a
            // moment later — [loadDefaultModel], through the same selection and the same
            // prompt composition, so there is no second path to keep in step with the
            // first. The warm it ends in writes the state file, and every launch after
            // this one restores it in milliseconds.
            arrivals.arrivals.collect { prewarmAfterDownload() }
        }
        viewModelScope.launch {
            archive.observeCount()
                .catch { failure ->
                    // The list beside it is still readable and the archive is not the
                    // reason anybody opened the app, so this loses the way in rather than
                    // the drawer.
                    Log.w("OpenWeights", "the archive count could not be read", failure)
                }
                .collect { count -> _uiState.update { it.copy(archivedCount = count) } }
        }
        viewModelScope.launch {
            writer.conversations()
                // Collected for the life of the view model and awaited by nobody, so an
                // SQLite error here had no catch above it and took the process with it,
                // from `init`, before there was a screen to say anything on.
                .catch { failure ->
                    Log.w("OpenWeights", "the conversation list could not be read", failure)
                    _uiState.update { it.copy(error = CHATS_UNREADABLE) }
                }
                .collect { rows ->
                    _uiState.update { state ->
                        state.copy(
                            conversations = rows.map {
                                ConversationSummary(
                                    id = it.id,
                                    title = it.title,
                                    modelName = it.modelName,
                                    updatedAt = it.updatedAt,
                                    pinnedAt = it.pinnedAt,
                                    archivedAt = it.archivedAt,
                                    hasDraft = it.draft.isNotEmpty(),
                                )
                            },
                        )
                    }
                }
        }
    }

    /**
     * True once a model has been loaded or is loading, so the screen can ask for the
     * default model on first composition without reloading it after a rotation.
     */
    val hasModel: Boolean
        get() = _uiState.value.modelName != null || _uiState.value.isLoadingModel

    /**
     * Set by [unloadModel] and cleared by a load the user asked for.
     *
     * The chat tab loads the default model whenever it finds none loaded, which is right
     * on a cold start and wrong after the user unloaded it: leaving the tab and coming back
     * brought the model straight back into memory. An unload is a choice, kept until the
     * next one.
     */
    private var unloadedByUser = false

    /** Loads the model last chosen, or whichever is on disk if there is no choice yet. */
    fun loadDefaultModel() {
        if (unloadedByUser) return
        runtime.preferredModel()?.let(::loadModel)
    }

    /**
     * Opens a model that has just finished downloading, if nothing else wants the engine.
     *
     * Deliberately the same two lines [loadDefaultModel] runs, guarded rather than
     * reimplemented: the point is to move *when* that happens, not what it does. Anything
     * that composed its own prefix here would drift from the one a real turn renders, and
     * a warm whose bytes differ from the next prompt by a single character warms nothing.
     *
     * Five reasons not to, and each of them is a case where doing it would be worse than
     * waiting for the chat tab to ask:
     *
     * - The user unloaded the model on purpose. That is a choice, and quietly loading
     *   several gigabytes back because a download finished would undo it.
     * - Something is already loaded or loading. Swapping the weights under a conversation
     *   nobody asked to swap is the one thing this must never do.
     * - A download is still running. Usually the projector for the model that just landed;
     *   see `ModelStore.downloadsInFlight`.
     * - The phone is hot. A download of several gigabytes is itself heating, and a minute
     *   of prefill for a screen nobody is looking at is not worth the thermal budget of
     *   the reply that follows it.
     * - The app is not on screen. This is the common case rather than the exception: a
     *   multi-gigabyte download is minutes, and nobody watches it, which is the whole
     *   reason it runs in a worker. Taking two gigabytes into a process Android has
     *   already filed as cached is how that process gets killed, and the user would come
     *   back to an app that had restarted for a warm they never asked for.
     *
     * None of the five is retried, because none of them needs to be: the chat tab still
     * loads the model the moment somebody opens it, exactly as it did before this existed.
     * All this can do is be early, and the last four are the cases where early is wrong.
     */
    private fun prewarmAfterDownload() {
        if (unloadedByUser || hasModel) return
        if (!isOnScreen) return
        if (runtime.downloadsInFlight()) return
        if (runtime.thermalLevel() >= ThermalLevel.HEAVY) return
        runtime.preferredModel()?.let(::loadModel)
    }

    /**
     * True when this app is the thing the user is looking at.
     *
     * The same question [ReplyNotifier] asks and answered the same way, because it is a
     * question about the process rather than about any one screen: the download that
     * triggers this finished in a worker, and the view model it reaches has no lifecycle
     * that distinguishes "the chat is behind the models screen" from "the phone is in a
     * pocket". A foreground *service* — which is what the download itself holds — ranks
     * below IMPORTANCE_FOREGROUND, so a download finishing in the background reads as
     * background here, which is exactly the distinction wanted.
     */
    private val isOnScreen: Boolean
        get() {
            val manager = appContext.getSystemService<ActivityManager>() ?: return false
            val mine = manager.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }
            return mine != null &&
                mine.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }

    /** Loads a GGUF file from disk. */
    /**
     * Loads a model.
     *
     * @param keepConversation carry the open chat over to the new model instead of
     * starting a fresh one. The transcript is text, so it survives the swap; the KV cache
     * does not, and is rebuilt from that text on the next reply.
     */
    fun loadModel(modelFile: File, contextLength: Int? = null, keepConversation: Boolean = false) {
        unloadedByUser = false
        // A generation in flight writes into the transcript, so it has to finish before the
        // model under it is replaced, not merely be asked to stop.
        stop()
        // Claimed on the caller's thread, so the ordering matches the taps rather than the
        // order coroutines happen to start in.
        val request = ++loadRequest
        viewModelScope.launch {
            generationJob?.join()
            // One load at a time. Two in flight would both write the identity of whichever
            // model they finished with, and the engine would be holding neither.
            loadMutex.withLock {
                // A load that was superseded while queueing is not worth the seconds it
                // costs: the user has already asked for a different model.
                if (request != loadRequest) return@withLock
                performLoad(modelFile, contextLength, keepConversation)
            }
        }
    }

    /** Explicitly releases the loaded model while keeping its files available to reload. */
    fun unloadModel() {
        unloadedByUser = true
        stop()
        val request = ++loadRequest
        viewModelScope.launch {
            generationJob?.join()
            loadMutex.withLock {
                if (request != loadRequest) return@withLock
                runCatching {
                    runtime.unload()
                    forgetLoadedModel()
                    preferencesKey = null
                }.onFailure { failure ->
                    _uiState.update { it.copy(error = failure.userMessage()) }
                }
            }
        }
    }

    /**
     * Drops everything that described the model being replaced.
     *
     * Before the attempt, not after: the engine frees what it held as it loads, so from
     * that moment the old name, context size and capabilities describe nothing. The staged
     * attachments go too, whether the load succeeds or fails, because they were picked for
     * a model that is no longer there.
     *
     * @param replacing the model being loaded, whose name the top bar keeps while the
     * weights are remapped. Everything that could be wrong about it is still cleared: the
     * processor, the window, and what it can read are all properties of a load that has not
     * happened yet. Only the name survives, and only because moving the processor took the
     * title down to "Choose a model" for the seconds it took, which reads as having lost
     * the model rather than as having moved it. Nothing can act on the name meanwhile, since
     * the composer is closed for the whole of a load.
     */
    private suspend fun forgetLoadedModel(replacing: File? = null) {
        // Cleared here as well as on the way in. A load that fails leaves nothing loaded, and
        // a stale file here would have the next turn check weights that are not in use.
        loadedFile = null
        val abandoned = _uiState.value.staged
        _uiState.update {
            it.copy(
                staged = emptyList(),
                modelName = replacing?.nameWithoutExtension,
                isPreparingFirstResponse = false,
                // The engine's record belongs to the template of the model that wrote it.
                // Judged here because this is where the old name is last known — by the
                // time the load finishes, the state already carries the new one.
                engineHistory = if (it.modelName == replacing?.nameWithoutExtension) {
                    it.engineHistory
                } else {
                    null
                },
                modelQuantization = null,
                backend = null,
                contextSize = 0,
                contextUsed = 0,
                mediaSupport = MediaSupport(),
                outputModality = OutputModality.TEXT,
                supportsThinking = false,
                supportsReasoningEffort = false,
            )
        }
        // The screen is left in one step above, so nothing observes a half-forgotten model.
        // What is left is the copies on disk, which is the part that has to suspend.
        attachments.discard(abandoned)
    }

    /**
     * How to load this model, including which processor gets its layers.
     *
     * Auto's answer is a measurement rather than a setting, and the measurement changes as
     * the model is used, so it is resolved per load. That is also the only moment llama.cpp
     * will accept it.
     */
    private suspend fun loadParamsFor(
        modelFile: File,
        preferences: ModelPreferences,
        contextLength: Int?,
    ): ModelLoadParams {
        val (prompted, generated) = writer.inOrder { turnShape(modelFile.nameWithoutExtension) }
        // Resolved from the two halves together, because layers serve both. The prefill
        // choice reaches the other knob — op_offload — inside toLoadParams.
        val layers = computeLayersFor(
            prefill = preferences.prefillTarget,
            decode = preferences.decodeTarget,
            hasGpu = runtime.hasGpu(),
            promptTokens = prompted,
            generatedTokens = generated,
        )
        contextLength?.let {
            return preferences.toLoadParams(gpuLayers = layers, automatic = it)
                .copy(contextLength = it)
        }
        // Only computed when it will be used. Reading the header is cheap but it is still a
        // file read on the path a cold start always takes, and a user who has chosen a window
        // has already answered the question this asks.
        val automatic = if (preferences.contextLength == ModelPreferences.AUTOMATIC) {
            runtime.windows.defaultFor(modelFile, runtime.projectorFor(modelFile))
        } else {
            ModelLoadParams.DEFAULT_CONTEXT_LENGTH
        }
        return preferences.toLoadParams(layers, automatic)
    }

    private suspend fun performLoad(
        modelFile: File,
        contextLength: Int?,
        keepConversation: Boolean,
    ) {
        _uiState.update {
            it.copy(
                isLoadingModel = true,
                isPreparingFirstResponse = false,
                error = null,
            )
        }

        // The same yield a turn does, and now for the same reason. A warm holds the engine
        // for as long as it takes to read the whole prefix, and a load queues behind it:
        // measured on the phone at 9.9 s between tapping a model and having it, of which
        // 8.4 s was a background warm the user never asked for. Loading a model is exactly
        // as much a thing somebody is waiting on as sending a message is.
        //
        // Nothing is lost by killing it. A warm interrupted mid-batch keeps its committed
        // tokens, and this load ends in [finishLoad], which warms again — so the work moves
        // to after the load instead of in front of it. What that costs is the state file
        // this warm would have written, which the warm after the load writes instead.
        turns.yieldWarms()

        // Inside the catch, not before it. Settings come from DataStore and the projector
        // from a directory listing, and a phone whose storage has gone wrong fails those
        // exactly as readily as it fails the weights. Outside, they were two unwatched reads
        // in the one path a cold start always takes. The settings come back out because the
        // screen shows them, and they are only known once the read has succeeded.
        try {
            val settings = runtime.settingsFor(modelFile.name)
            val projector = runtime.projectorFor(modelFile)
            forgetLoadedModel(replacing = modelFile)
            runtime.load(modelFile, loadParamsFor(modelFile, settings, contextLength), projector)
            // Inside the same guard as the load, and it was not. `Result.onSuccess` does not
            // fold a throw of its own back into the Result, so nothing [finishLoad] raised
            // ever reached the failure branch below: it left `viewModelScope.launch`, where
            // a root coroutine hands what it cannot catch to Android's uncaught handler.
            // Two things in there can raise, the native context reset and a database
            // write, and neither is exotic on a phone whose storage is full.
            finishLoad(modelFile, settings, keepConversation)
        } catch (cancellation: CancellationException) {
            // A load the user superseded is not a load that failed, and must not be reported
            // as one. Passed on so the job ends cancelled, which the `runCatching` this
            // replaces did not do.
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            // Throwable and not Exception, because the `runCatching` this replaces caught
            // Throwable: mapping several gigabytes of weights is the one thing here that
            // credibly ends in an OutOfMemoryError, and a linker that cannot find the
            // native library raises an Error too.
            //
            // The name was kept across the swap for the top bar. A load that failed holds no
            // weights, so it goes here rather than staying to describe nothing.
            _uiState.update {
                it.copy(
                    isLoadingModel = false,
                    isPreparingFirstResponse = false,
                    modelName = null,
                    error = failure.userMessage(),
                )
            }
        }
    }

    /** The state and bookkeeping of a load that took, split out to stay readable whole. */
    private suspend fun finishLoad(
        modelFile: File,
        preferences: ModelPreferences,
        keepConversation: Boolean,
    ) {
        // The cache belonged to the old weights. Clearing it makes the next reply
        // re-read the transcript, which is what carries the conversation across.
        runtime.resetContext()
        runtime.rememberChoice(modelFile)
        if (!keepConversation) conversationId = null
        preferencesKey = modelFile.name
        loadedFile = modelFile
        val info = runtime.loadedModel
        _uiState.update {
            it.afterLoad(modelFile, info, preferences, keepConversation)
                .copy(isPreparingFirstResponse = true)
        }
        // Only ever on the startup load, so switching model still starts fresh.
        if (!keepConversation) {
            restoring?.let { id ->
                restoring = null
                openConversation(id)
            }
        }

        if (keepConversation) {
            conversationId?.let { id ->
                // Reported rather than raised. The weights are mapped and the screen has
                // already said so; a database that will not take this row is worth a
                // sentence, not the model the user just waited twenty seconds for. Raised,
                // it used to leave the load looking like it had failed while the engine
                // held a perfectly good model.
                reportingFailure { writer.inOrder { setModel(id, modelFile.nameWithoutExtension) } }
            }
        }

        // The context was just reset for the new weights, so the first answer's twenty
        // seconds of instructions-and-tools prefill are all still ahead — spend them now,
        // in the background, while the user is still reading the screen.
        warmEngine()
    }

    private fun ChatUiState.afterLoad(
        modelFile: File,
        info: LoadedModelInfo?,
        preferences: ModelPreferences,
        keepConversation: Boolean,
    ): ChatUiState {
        val support = info?.mediaSupport ?: MediaSupport()
        return copy(
            isLoadingModel = false,
            backend = runtime.backendName(),
            offloadBuffers = info?.offloadBuffers.orEmpty(),
            hasGpu = runtime.hasGpu(),
            hasNpu = runtime.hasNpu(),
            compiledProcessor = modelFile.name
                .takeIf { ModelFormat.of(it) == ModelFormat.PTE }
                ?.let {
                    when (CompiledBackend.of(it).processor) {
                        CompiledBackend.Processor.CPU -> ComputeTarget.CPU
                        CompiledBackend.Processor.GPU -> ComputeTarget.GPU
                        CompiledBackend.Processor.NPU -> ComputeTarget.NPU
                    }
                },
            modelName = modelFile.nameWithoutExtension,
            // The filename's own quantization, not llama's verbose description:
            // "Q4_K_M" beside the compute device and context window reads as a
            // spec line, "lfm2 1.2B Q4_K - Medium" reads as a sentence.
            modelQuantization = GgufFileName.quantization(modelFile.name),
            contextSize = info?.contextSize ?: 0,
            contextUsed = info?.contextUsed ?: 0,
            preferences = preferences,
            transcript = if (keepConversation) transcript else emptyList(),
            toolNotes = if (keepConversation) toolNotes else ToolNotes(),
            // Only under the model that wrote it. The record holds one template's
            // own rendering of tool rounds — raw call syntax, TOOL roles — and
            // replaying that into a different model's template is at best foreign
            // control text in the conversation and at worst a render refusal.
            engineHistory = engineHistory.takeIf {
                keepConversation && modelName == modelFile.nameWithoutExtension
            },
            compaction = if (keepConversation) compaction else null,
            error = if (keepConversation) transcript.unreadableWarning(support) else null,
        ).withCapabilities(info, runtime.ignoresThinkingSwitch(modelFile.name))
    }

    /**
     * Whether the weights behind the loaded model have been deleted, and letting go if so.
     *
     * Checked at the start of a turn, which is the only moment it matters and is one stat of
     * a file the process already has open. A model can go while it is loaded: deleted from
     * the Models tab, removed by a file manager, on a card that was taken out. The engine has
     * it mapped and carries on regardless, so the screen keeps naming a model that is not
     * there, Send stays live, and what the turn eventually makes of an unlinked mapping
     * surfaces minutes later as a generic failure.
     *
     * Told at the point of asking rather than at the point of deleting, because deleting is
     * not the only way it happens and a check at the boundary catches all of them.
     */
    private fun loadedModelHasGone(): Boolean {
        val file = loadedFile ?: return false
        if (file.exists()) return false
        loadedFile = null
        viewModelScope.launch {
            forgetLoadedModel()
            reportError("$MODEL_GONE ${file.nameWithoutExtension} is no longer on this device.")
        }
        return true
    }

    /**
     * Sends a message, or says it could not.
     *
     * Returns false when the turn was refused, so the composer can keep what was typed.
     * It used to return silently and the composer cleared regardless, so a question asked
     * while the weights were still being mapped, which is the first thing anyone does on a
     * cold start, vanished with nothing said about it.
     */
    fun send(prompt: String): Boolean {
        val typed = prompt.trim()
        val staged = _uiState.value.staged
        val document = _uiState.value.stagedDocument
        // An attachment on its own is a complete message: "what is this?" is implied.
        val nothingToSend = typed.isEmpty() && staged.isEmpty() && document == null
        if (nothingToSend) return false
        if (loadedModelHasGone()) return false
        if (!_uiState.value.canSend) {
            _uiState.value.refusalReason()?.let { why ->
                _uiState.update { it.copy(error = why) }
            }
            return false
        }

        // The document becomes part of what was asked, rather than something carried
        // alongside it. That keeps one string as the message: what is shown, what is
        // stored, and what the model reads are the same, which is what makes a reopened
        // conversation send the model the same thing it saw the first time.
        val text = document?.let { it.asPrompt() + typed }?.trim() ?: typed

        pinDayForFreshChat()

        // isGenerating is claimed here, before any suspending work: two quick taps would
        // otherwise both pass canSend, create two conversations, and race the engine.
        val asked = entry(ChatRole.USER, text).copy(attachments = staged)
        _uiState.update { state ->
            state.copy(
                transcript = state.transcript + asked,
                isGenerating = true,
                staged = emptyList(),
                stagedDocument = null,
                error = null,
            )
        }

        // Awaited before generating: a fast reply could otherwise finish before the row
        // exists and be dropped, taking its usage record with it.
        //
        // Tracked as the generation job from the first line, because until generate() runs
        // there was nothing for Stop to cancel. The write is short but it is not instant,
        // it can wait on the mutex behind a compaction, and isGenerating is already true:
        // Stop in that window did nothing at all, and the turn it appeared to cancel then
        // started against whatever state had replaced it.
        val send = viewModelScope.launch {
            // Answered either way. What the user typed is on screen and worth a reply even
            // if the disk would not take it, so a failure here is reported rather than
            // thrown: silence left a message that was there for the session and gone on
            // reopening, with the turn generating against it as though it were durable.
            reportingFailure {
                writer.inOrder {
                    val title = text.ifEmpty { staged.firstOrNull()?.describe() ?: "Attachment" }
                    val id = conversationId
                        ?: startConversation(title, _uiState.value.modelName)
                            .also {
                                conversationId = it
                                // A goal or research started on a still-empty chat had no
                                // conversation to record when it started; this is the first
                                // moment one exists. See GoalBoard.bindConversation.
                                goals.bindConversation(it)
                                // The message this draft was keeping safe is a row now.
                                saveDraft(0L, "")
                            }
                    val row = addMessage(id, ChatRole.USER.wireName, text, attachments = staged)
                    // Only once the row exists. An entry that never gets this is one an
                    // edit refuses rather than guesses at; see [TranscriptEntry.storedId].
                    _uiState.update { it.stored(asked.id, row) }
                }
            }
            generate()
        }
        generationJob = send
        // On completion rather than in a catch inside the block, because a job cancelled
        // before its body starts never runs the catch at all. generate() is what normally
        // hands the busy state back, and if it was never reached nothing else will.
        send.invokeOnCompletion { cause ->
            if (cause != null && generationJob === send) {
                _uiState.update { it.copy(isGenerating = false) }
            }
        }
        return true
    }

    /**
     * Stages a picked file for the next message.
     *
     * Copied in immediately rather than at send time, so the thumbnail appears at once and
     * the picker's read permission is used while it is still granted.
     */
    fun attach(uri: Uri) = attachments.attach(uri)

    /** Several at once, from a multi-select picker or from the clipboard. */
    fun attachAll(uris: List<Uri>) = attachments.attachAll(uris)

    /** Removes a staged attachment and deletes the copy that was made of it. */
    fun removeStaged(attachment: MessagePart.File) = attachments.remove(attachment)

    /** Stages a text document for the next question, or clears the staged one. */
    fun stageDocument(uri: Uri?) = attachments.stageDocument(uri)

    /**
     * The grounding record a stored conversation adds up to, folded once per message.
     *
     * The one way [ToolNotes.withSteps] is safe to replay: per message, in storage order,
     * which is the sequence a live conversation produced it in. Both places that rebuild the
     * record — reopening a chat, and regenerating a reply whose steps were just deleted —
     * go through here so they cannot drift apart again: regenerate used to leave the
     * discarded turn's results in the live notes while storage had already lost them, and
     * the two versions of the conversation disagreed until the next reopen.
     */
    private fun restoredToolNotes(
        messages: List<MessageEntity>,
        stepsByMessage: Map<Long, List<ToolStepEntity>>,
        foldedThrough: Int?,
    ): ToolNotes = messages.foldIndexed(ToolNotes()) { index, notes, message ->
        // The fold is replayed where it happened: the suspicion earned before it went
        // with the pages the fold rewrote, exactly as it does live.
        val afterFold = foldedThrough != null && index == foldedThrough + 1
        val current = if (afterFold) notes.folded() else notes
        val steps = stepsByMessage[message.id].orEmpty().map { it.toAgentStep() }
        if (steps.isEmpty()) current else current.withSteps(steps, turns::toolNamed)
    }

    /**
     * Discards the last model reply and asks again.
     *
     * The engine reuses the cached prompt prefix, so a regeneration costs only new tokens.
     */
    fun regenerate() {
        val state = _uiState.value
        if (state.isGenerating || state.transcript.none { it.role == ChatRole.ASSISTANT }) return
        // The same check send makes, for the same reason: this runs the model too.
        if (loadedModelHasGone()) return

        _uiState.update {
            it.copy(
                transcript = it.transcript.dropLastWhile { entry ->
                    entry.role == ChatRole.ASSISTANT
                },
                error = null,
                isGenerating = true,
            )
        }

        // Tracked as the generation job from its first line, the way send() tracks its
        // own. It was not: Stop found nothing to cancel until generate() ran, and a chat
        // reopened from the drawer meanwhile joined a finished job, queued behind this
        // one's write, and then received the regenerated reply as its own.
        val regenerate = viewModelScope.launch {
            // Storage has to lose the same replies the screen just lost. Without this the
            // conversation reopens with the discarded reply and the new one both in it.
            // Under the same lock as the writes, so the read cannot happen before the
            // reply being discarded has been inserted.
            //
            // Guarded, because the busy flag was claimed above and a read that throws here
            // used to leave it claimed forever: not a crash, a chat showing Stop with nothing
            // behind it, unusable until the app was killed.
            val discarded = runCatching {
                writer.inOrder {
                    conversationId?.let { id ->
                        val stored = messages(id)
                        // The same rule the transcript used: the trailing run of replies,
                        // which starts after the last thing that was not one.
                        val firstDiscarded =
                            stored.indexOfLast { it.role != ChatRole.ASSISTANT.wireName } + 1
                        stored.getOrNull(firstDiscarded)?.let { deleteFrom(id, it.id) }
                        // What the notes are now that the discarded replies' steps are gone.
                        // Read back after the delete, so the cascade has already taken the
                        // dead steps with it and the fold sees only what survived.
                        restoredToolNotes(
                            stored.take(firstDiscarded),
                            toolSteps(id),
                            state.compaction?.foldedThroughIndex,
                        )
                    }
                }
            }
            // A cancellation is Stop, not a storage failure, and runCatching had been
            // reading it as one: the job carried on deciding what to do inside a scope
            // that was already dead.
            discarded.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            if (discarded.isFailure) {
                // Refused rather than generated anyway. The reply on screen is already gone
                // and the stored one is not, so answering would leave two of them in the
                // conversation and the user reading a history they never had. The replies
                // go back too, the way a failed edit puts its turns back: storage still
                // holds them, and a screen that had lost them showed a question with no
                // answer that came back on the next reopen.
                Log.w("OpenWeights", "a reply could not be discarded", discarded.exceptionOrNull())
                _uiState.update {
                    it.copy(
                        transcript = state.transcript,
                        isGenerating = false,
                        error = STORAGE_FAILED,
                    )
                }
                return@launch
            }
            // The notes the discarded reply's tool calls added are discarded with it, before
            // the new attempt runs: left in place, a result the user threw away kept
            // grounding every prompt after it — and then silently vanished on the next
            // reopen, when the rebuild read a storage that had never kept it.
            discarded.getOrNull()?.let { notes ->
                _uiState.update { it.copy(toolNotes = notes) }
            }
            generate()
        }
        generationJob = regenerate
        regenerate.invokeOnCompletion { cause ->
            if (cause != null && generationJob === regenerate) {
                _uiState.update { it.copy(isGenerating = false) }
            }
        }
    }

    /**
     * Rewrites a question that was already asked, and answers the new one.
     *
     * The conversation ends at the edited turn. Everything the old wording produced, and
     * everything said after it, is dropped from the screen and from storage before the new
     * question is sent, because a transcript holding both is a conversation the user never
     * had and the model would read it as one.
     *
     * Nothing here touches the KV cache on purpose. The engine compares the new prompt
     * against the tokens it is holding and keeps the longest common prefix, which is
     * everything before the edited turn; on a hybrid model, where a partial rollback is
     * refused, it starts over instead. Either way the cache is a function of the prompt, and
     * a second mechanism reaching in to purge it could only disagree with the first.
     */
    fun editAndResend(entryId: Long, text: String) {
        val edit = editFor(entryId, text) ?: return
        _uiState.update { edit.applied(it) }

        val editJob = viewModelScope.launch {
            val rewritten = rewriteStoredTurn(edit)
            if (rewritten == null) {
                _uiState.update { edit.rolledBack(it) }
                return@launch
            }
            _uiState.update { current ->
                current.copy(
                    transcript = edit.kept +
                        entry(ChatRole.USER, edit.text).copy(
                            attachments = edit.attachments,
                            storedId = rewritten.storedId,
                        ),
                    // The notes the dropped turns' tool calls added go with them, as they
                    // do on regenerate. Left in place, a page the user had edited away
                    // kept grounding every prompt after it.
                    toolNotes = rewritten.notes,
                    isGenerating = true,
                )
            }
            attachments.discard(edit.abandoned)
            generate()
        }
        generationJob = editJob
        // The same guard [send] carries, and for the same reason. This claims the busy
        // state before the rewrite, and the rewrite waits on the write queue, which can sit
        // behind a compaction. Stop in that window cancels the job before generate() has
        // run, so nothing has registered `releaseTurn` and nothing else hands the state
        // back: the composer stayed disabled for the rest of the session.
        editJob.invokeOnCompletion { cause ->
            if (cause != null && generationJob === editJob) {
                _uiState.update { it.copy(isGenerating = false) }
            }
        }
    }

    /**
     * Works out everything one edit changes, or returns null if it changes nothing.
     *
     * Decided in one place and off a single read of the state, because the screen keeps
     * moving: tokens arrive, a fold completes, and a rule read at the top would no longer
     * hold by the time the write at the bottom ran. What comes back is a description of the
     * edit rather than the edit itself, so applying it, undoing it, and writing it down are
     * three readings of the same decision instead of three decisions.
     */
    private fun editFor(entryId: Long, text: String): Edit? {
        val edited = text.trim()
        val state = _uiState.value
        val at = state.transcript.indexOfFirst { it.id == entryId }
        // Only a question of the user's own, and only while nothing else is rewriting the
        // transcript. `loadedModelHasGone` is last because it is the one that also puts a
        // message on screen, and there is nothing to say about a missing model to somebody
        // who is not going to get this far anyway.
        val editable = at >= 0 && state.transcript[at].role == ChatRole.USER
        val busy = state.isGenerating || state.isCompacting || loadedModelHasGone()
        if (edited.isEmpty() || !editable || busy) return null

        // The summary covered the turn being rewritten, so it describes a conversation that
        // is about to stop existing and has to go with it.
        val invalidatesCompaction = state.compaction?.foldedThroughIndex?.let { at <= it } == true
        return Edit(
            at = at,
            storedId = state.transcript[at].storedId,
            text = edited,
            attachments = state.transcript[at].attachments,
            abandoned = state.transcript.drop(at + 1).flatMap { it.attachments },
            kept = state.transcript.take(at).map {
                if (invalidatesCompaction) it.copy(compactionNote = null) else it
            },
            invalidatesCompaction = invalidatesCompaction,
            before = state,
        )
    }

    /**
     * Puts the rewritten turn in storage, and says what the conversation is now.
     *
     * Null rather than thrown, because the screen has already been changed and the two
     * have to agree: a transcript that shows the edit while storage still holds the original
     * is a conversation that changes back the next time it is opened.
     *
     * The row is addressed by the id the entry was stored under, never by its position.
     * Position was how it used to be found, and the transcript and the table only line up
     * while every write has landed: a question whose write failed is answered anyway, and
     * from then on the transcript is one entry ahead of the rows, so an edit by position
     * rewrote the question after the one that was tapped. An entry with no stored id is
     * refused instead, with the same sentence the failed write already showed.
     */
    private suspend fun rewriteStoredTurn(edit: Edit): Rewritten? {
        val storedId = edit.storedId ?: return null
        return writeOrNull {
            writer.inOrder {
                conversationId?.let { id ->
                    replaceFrom(
                        conversationId = id,
                        messageId = storedId,
                        text = edit.text,
                        attachments = edit.attachments,
                        clearCompaction = edit.invalidatesCompaction,
                    )
                    // Read back after the write, the way regenerate does, so the cascade
                    // has already taken the dropped turns' steps and the fold sees only
                    // what survived. The rewritten question is the last row.
                    val stored = messages(id)
                    Rewritten(
                        storedId = stored.lastOrNull()?.id,
                        notes = restoredToolNotes(
                            stored,
                            toolSteps(id),
                            edit.before.compaction?.foldedThroughIndex
                                ?.takeUnless { edit.invalidatesCompaction },
                        ),
                    )
                }
            }
        }
    }

    /** What an edit left in storage: the new row, and the notes the surviving turns add up to. */
    private class Rewritten(val storedId: Long?, val notes: ToolNotes)

    /**
     * Carries this conversation up to a point into a new one, and opens it there.
     *
     * For the moment somebody wants to try a different direction without losing the one they
     * are on. The alternative people actually use is to scroll up, copy their question and
     * paste it into a new chat, which loses everything before it.
     *
     * Copied rather than shared. Two conversations pointing at the same rows would diverge
     * the moment either was folded, and a summary written for one would appear in the other
     * describing turns it does not have.
     */
    fun branchFrom(entryId: Long) {
        val state = _uiState.value
        if (state.isGenerating || state.isCompacting) return
        val upTo = state.transcript.indexOfFirst { it.id == entryId }
        if (upTo < 0) return
        val carried = state.transcript.take(upTo + 1)
        if (carried.isEmpty()) return

        viewModelScope.launch {
            val copiedTranscript = mutableListOf<TranscriptEntry>()
            val copiedFiles = mutableListOf<MessagePart.File>()
            var createdId: Long? = null
            val branched = writeOrNull {
                writer.inOrder {
                    val title = carried.first { it.role == ChatRole.USER }.text
                    val id = startConversation(title, state.modelName).also { createdId = it }
                    // Skipped rather than carried, because the summary belongs to the
                    // conversation it was written for: the new one has every turn the old
                    // one folded away, so there is nothing for it to stand in for.
                    carried.forEach { entry ->
                        val attachments = staging.duplicate(entry.attachments)
                        copiedFiles += attachments
                        copiedTranscript += entry.copy(attachments = attachments)
                        addMessage(
                            conversationId = id,
                            role = entry.role.wireName,
                            text = entry.history ?: entry.text,
                            attachments = attachments,
                        )
                    }
                    id
                }
            }

            if (branched == null) {
                cleanUpBranch(createdId, copiedFiles)
                reportError(STORAGE_FAILED)
                return@launch
            }
            // Straight over, and the cache deliberately kept: alignment only ever reuses
            // positions whose bytes match the new prompt, so the parent's cache can never
            // leak into a reply — what it can do is spare the branch re-reading the turns
            // both conversations share. See newChat, which dropped its reset for the same
            // reason.
            conversationId = branched
            _uiState.update {
                it.copy(
                    transcript = copiedTranscript,
                    compaction = null,
                    toolNotes = ToolNotes(),
                    // Carried only when it describes exactly the carried turns: a branch
                    // from the last reply keeps extending the parent's cache byte for
                    // byte. A branch from earlier has no way to cut the record at the
                    // branch point — tool rounds mean its messages do not map one-to-one
                    // onto transcript entries — so it rebuilds from the transcript below.
                    engineHistory = state.engineHistory?.takeIf { record ->
                        record.throughCount == carried.size && record.covers(carried)
                    },
                    contextUsed = 0,
                    error = null,
                )
            }
            // What the branch will re-read, read now in the background: by the time the
            // user has typed where this conversation should go instead, the carried turns
            // are already in the cache.
            warmEngine()
        }
    }

    /**
     * Keeps the thermal reading current for as long as a reply is being written.
     *
     * Sampled while working rather than once at the end: a phone warms up over the course
     * of a long answer, and a reading taken when it finishes describes a device that has
     * already begun cooling.
     *
     * Off the main dispatcher, so the wait between readings is a real wait. On the main one
     * it is whatever dispatcher the caller installed, and under a test scheduler a delay
     * costs nothing and takes no time: a loop of them is an infinite loop that always has
     * one more task ready, so `advanceUntilIdle` never returns. That is not a hypothetical.
     * It hung the suite for an hour at a time, and each run that was killed left a worker
     * behind spinning a core, which is why every build on this machine got slower until the
     * cause was found. Reading a hardware sensor was never main-thread work anyway.
     */
    /**
     * Removes what a branch that did not finish left behind.
     *
     * Uncancellable on purpose, and that is the whole reason it is its own function. This
     * runs on the failure path, and one of the ways to reach the failure path is the
     * coroutine being cancelled: cleanup written inline suspends at its first line, is
     * cancelled there, and does nothing. What it does not do is delete a conversation that
     * has already been created and filled in, so a branch stopped halfway stayed in the
     * drawer, with copies of every attachment it had got through.
     */
    private suspend fun cleanUpBranch(createdId: Long?, copied: List<MessagePart.File>) {
        withContext(NonCancellable) {
            writeOrNull { writer.inOrder { createdId?.let { deleteConversation(it) } } }
            runCatching { staging.discard(copied) }
        }
    }

    private fun startThermalSampling() {
        thermalJob?.cancel()
        thermalJob = viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                val level = runtime.thermalLevel()
                val degrees = runtime.thermalCelsius()
                _uiState.update { it.copy(thermal = level, deviceCelsius = degrees) }
                delay(THERMAL_SAMPLE_MS)
            }
        }
    }

    /**
     * Everything that has to be true before the engine is asked anything.
     *
     * Returns what the turn was opened on, or null when there is nothing to send and the
     * turn is over before it started. Separated from [generate] because these are decisions
     * about the conversation while what follows is a stream of tokens, and reading one while
     * looking for the other is how the empty-conversation branch came to be easy to miss.
     */
    private suspend fun openTurn(): OpenedTurn? {
        // Folded before the turn as well as after it. Compaction only ever ran at the
        // end, so a conversation already past the threshold started its next turn
        // against the wall, and what the user saw was the turn failing rather than the
        // history being folded. Cheap when there is nothing to fold.
        compactIfNeeded()

        // Read here rather than held: the user can switch a tool off in another tab
        // between one question and the next, and the instruction has to follow. Asked
        // for the mode the turn will run in, because plan mode's tools follow no switch.
        _uiState.update {
            it.copy(toolsAvailable = turns.hasEnabledTools(it.mode))
        }

        val state = _uiState.value
        val conversation = state.engineMessages(
            toolPromptOverride = toolPromptOverride.also { toolPromptOverride = null },
        )
        if (conversation.isEmpty()) {
            // Callers claim the busy state before this point to close the double-tap
            // window, so nothing to send has to give it back.
            _uiState.update { it.copy(isGenerating = false) }
            return null
        }

        // Taken from the transcript rather than from `conversation`'s last message: that one
        // has already had the tool notes folded into it by `withToolNotes`, and what a pass
        // needs re-grounded on is the question the user actually asked, not the notes riding
        // along beside it.
        val question = state.transcript.lastOrNull { it.role == ChatRole.USER }?.text.orEmpty()

        _uiState.update { current ->
            current.copy(
                transcript = current.transcript +
                    entry(ChatRole.ASSISTANT, "").copy(isStreaming = true),
                isGenerating = true,
            )
        }
        return OpenedTurn(conversation = conversation, state = state, question = question)
    }

    private fun generate() {
        startThermalSampling()
        // Before the work, because a foreground service cannot be started from the
        // background and the tap that got here was the foreground. See GenerationService:
        // without it, leaving the app during a reply stops the reply dead.
        GenerationService.hold(appContext, GenerationService.TURN, "Answering")

        val job = viewModelScope.launch {
            // First, before anything here reaches the engine: the pre-turn fold and the
            // thread re-plan both hop onto the engine's own thread, and with a warm
            // holding it they queue behind the whole background read. The turn-side
            // interrupt in TurnRunner.run cannot help from back there.
            turns.yieldWarms()
            val opened = openTurn() ?: return@launch
            val conversation = opened.conversation
            val state = opened.state

            val turnStartedAt = System.currentTimeMillis()
            var lastFrameAt = 0L
            var reasoningEndedAt: Long? = null
            var produced = ""
            // Set once, at the one place a completed turn is persisted, and read again by
            // settleTurn below rather than recomputed: two separate System.currentTimeMillis()
            // calls a few suspension points apart do not agree to the millisecond, and the
            // live transcript entry disagreeing with the row just written under it read as a
            // reopened conversation losing track of its own number.
            var settledMillis: Long? = null

            // What the turn will write down: the last completed pass's text, with the
            // stats of every pass folded together. The text is rightly the final pass's --
            // it is the answer -- but keeping only that pass's numbers made a tool turn's
            // row lie: the first pass re-read the whole conversation and the row reported
            // whatever the short closing pass happened to cost. Null until a pass completes
            // at all, which is what tells the difference between a turn that answered and
            // one that was stopped before it could.
            var settled: Pair<String, GenerationStats>? = null
            var turnStats: GenerationStats? = null
            // The turn's engine-side conversation, if it completes normally. Everything
            // needed to build the next prompt as an extension of this one's cache.
            var engineTail: List<ChatMessage>? = null
            lastTurnSteps = emptyList()

            val listener = object : TurnListener {
                override fun onText(raw: String) {
                    produced = raw
                    // Not every token: re-parsing the whole reply and rebuilding its
                    // markdown tree costs more than a frame, so publishing per token on a
                    // phone that is also running the model means the list never settles.
                    val now = System.currentTimeMillis()
                    if (now - lastFrameAt < STREAM_FRAME_MS) return
                    lastFrameAt = now

                    val parsed = parseAssistantReply(raw)
                    if (reasoningEndedAt == null &&
                        parsed.reasoning != null &&
                        !parsed.isReasoningInProgress
                    ) {
                        reasoningEndedAt = System.currentTimeMillis()
                    }
                    applyStreamedText(raw, parsed, reasoningEndedAt, turnStartedAt)
                }

                override fun onPass(event: GenerationEvent.Completed, raw: String) {
                    val merged = turnStats?.through(event.stats) ?: event.stats
                    turnStats = merged
                    settled = applyCompletion(event, raw, merged) to merged
                }

                override fun onSteps(steps: List<AgentStep>) {
                    lastTurnSteps += steps
                    updateLastEntry { it.copy(blocks = it.blocks + steps.map(TurnBlock::Step)) }
                    // Kept for the turns after this one. Within this turn the results are
                    // already in the loop's own messages, which is why nothing here is sent
                    // twice: the prompt is built before the first tool runs.
                    _uiState.update {
                        it.copy(toolNotes = it.toolNotes.withSteps(steps, turns::toolNamed))
                    }
                }

                override fun onIntermediate(text: String) =
                    updateLastEntry { it.copy(blocks = it.blocks + TurnBlock.Said(text)) }

                override fun onNextPass() {
                    // Room for the next pass under the same entry, which is what makes a
                    // turn with tools in it read as one answer rather than several.
                    produced = ""
                    lastFrameAt = 0L
                    updateLastEntry { it.copy(isStreaming = true, text = "", answer = "") }
                }

                override suspend fun onApproval(call: ToolCall): Boolean = askUser(call)

                override fun onEngineHistory(messages: List<ChatMessage>) {
                    engineTail = messages
                }
            }

            try {
                // Re-planned per reply: the phone is a different machine hot than cold,
                // and a count chosen at load time is wrong by the third long answer.
                // Inside the try because it suspends, so Stop can land here too.
                if (!applyThreadPlan()) return@launch

                isDecoding = true
                // Consumed here and only here: whatever this turn was set to override, the
                // next one starts with nothing overridden unless it sets its own.
                val offerAsk = offerAskOverride.also { offerAskOverride = null }
                produced = turns.run(
                    conversation = conversation,
                    params = state.preferences.toSamplerParams().let {
                        if (state.toolsAvailable) it.copy(thinking = true) else it
                    },
                    mode = _uiState.value.mode,
                    // Offering a tool to a template that cannot render one wastes the
                    // context it takes up and leaves the model describing what it would
                    // do if it could.
                    // Not gated on the template being able to render tools any more.
                    // TurnRunner puts the definitions in the conversation for a model whose
                    // template drops them, which is two of the three families tested here,
                    // and deciding here that they cannot have tools is what made that
                    // silent.
                    withTools = state.toolsAvailable,
                    // The same notes the prompt was built with, read from the same snapshot.
                    // Taking them from the live state instead would seed the guard from
                    // whatever this turn had already found, which is not what is in the
                    // question the model is about to answer.
                    notes = state.toolNotes,
                    listener = listener,
                    offerAsk = offerAsk,
                    question = opened.question,
                )
                // Here, so a turn that used a tool is written down once. Skipped by both
                // catches below, where finishInterrupted writes what was produced instead.
                // Blank is not written at all: an empty row reopens as an empty bubble and
                // is resent as an empty assistant turn, which some templates refuse.
                settled
                    ?.takeIf { (text, _) -> text.isNotBlank() }
                    ?.let { (text, stats) ->
                        settledMillis = System.currentTimeMillis() - turnStartedAt
                        // Null for a turn that kept no record — a stopped pass, a turn with
                        // media in it — and then the stale-record check quietly falls the
                        // next prompt back to the rebuilt-from-transcript path.
                        val record = engineRecord(engineTail)
                        persistReply(text, stats, settledMillis, record?.messages)
                        _uiState.update { it.copy(engineHistory = record) }
                    }
            } catch (cancellation: CancellationException) {
                // Stop was pressed. What arrived before it is real output the user watched
                // being written, so it is kept and stored like any other reply.
                finishInterrupted(produced)
                _uiState.update { it.copy(isGenerating = false, pendingApproval = null) }
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
                finishInterrupted(produced)
                // The cache is whatever the failed decode left behind, and the next turn
                // would be built on top of it and fail the same way. Clearing it costs one
                // re-read of the transcript, which is the text still on screen, so nothing
                // the user can see is lost by it.
                runCatching { runtime.resetContext() }
                _uiState.update { it.copy(error = failure.userMessage()) }
            } finally {
                // However the turn ended, including Stop rethrowing above: the cache is
                // idle again and the next fold is allowed to reset it.
                isDecoding = false
            }

            settleTurn(produced, turnStartedAt, settledMillis)
        }
        generationJob = job
        job.invokeOnCompletion(::releaseTurn)
    }

    /**
     * Gives back what the turn was holding, and folds if the context now needs it.
     *
     * Its own function because it runs the same way whether the turn ended in an answer or
     * in an error, and because the last act of a turn is the easiest thing to lose sight of
     * at the bottom of a long one.
     */
    private suspend fun settleTurn(raw: String, turnStartedAt: Long, settledMillis: Long?) {
        // A stream that ends without a completion event, which happens if the engine's
        // channel closes under it, would otherwise leave the entry streaming forever.
        if (_uiState.value.transcript.lastOrNull()?.isStreaming == true) {
            finishInterrupted(raw)
        }

        _uiState.update { it.droppingEmptyReply() ?: it }
        // The same value just persisted, when there is one, rather than a second reading of
        // the clock: a completed turn's row and its live entry must agree to the millisecond,
        // or a reopened conversation shows a different number than the one it just showed.
        updateLastEntry {
            it.copy(totalMillis = settledMillis ?: (System.currentTimeMillis() - turnStartedAt))
        }
        // Re-read rather than leave the last reading standing: a phone that has cooled
        // between replies should stop claiming it is hot.
        _uiState.update {
            it.copy(
                isGenerating = false,
                isThrottled = runtime.isThrottling(),
                pendingApproval = null,
            )
        }
        // Only in plan mode, and only from a reply that actually listed steps. A model that
        // answered instead of planning has proposed nothing, and putting a plan on screen it
        // never wrote would be the app inventing one.
        if (_uiState.value.mode == AgentMode.PLAN) {
            val proposed = _uiState.value.transcript.lastOrNull()?.answer.orEmpty()
            turns.planning.propose(proposed)
        }
        compactIfNeeded()
        // After every settled turn, not only after a fold. A fold rewrites the prompt
        // from the root; but so, more quietly, does a template that re-renders history
        // differently from what was decoded — LFM2.5-Thinking's drops the think block
        // from every prior reply — and the next prompt then diverges from the cache,
        // which a hybrid pays as a restore-and-re-read that grows with the conversation.
        // Warmed here, that read happens between turns, in the background, and the next
        // question extends it; for a byte-stable template the warm meets a cache that
        // already starts with its bytes and costs one compare.
        warmEngine()
        notifier.notifyReply(_uiState.value.transcript.lastOrNull()?.answer.orEmpty())
    }

    /**
     * Gives back everything a turn was holding, however it ended.
     *
     * The try inside [generate] does not open until compaction has finished, and folding a
     * long history asks the model for a summary, which is seconds. Stop pressed in that
     * window threw straight out of the coroutine, past every catch, with isGenerating still
     * claimed from the tap: the composer stayed locked behind a Stop button that had
     * nothing left to cancel. Anything ending this job abnormally has to give the busy
     * state back, wherever it happened.
     *
     * The sampler is stopped either way, because two of the three ways a turn ends never
     * reach the last line of the body: Stop rethrows its cancellation and a failed decode
     * returns through the catch, and either one left the thermal API being read every few
     * seconds for the rest of the process.
     */
    private fun releaseTurn(cause: Throwable?) {
        thermalJob?.cancel()
        // Here rather than at the end of the body, for the reason this function exists:
        // two of the three ways a turn ends never reach the last line, and a notification
        // left up for work that stopped is worse than none.
        //
        // Releases the turn's hold and not the goal's. A goal is many turns and does its own
        // work in the gaps between them; dropping the process there is what let it freeze
        // mid-goal.
        GenerationService.release(appContext, GenerationService.TURN)
        if (cause != null) {
            _uiState.update { it.copy(isGenerating = false, pendingApproval = null) }
        }
    }

    /**
     * Waits for the user to allow one tool.
     *
     * Suspends the agent rather than polling, so Stop is never blocked by a question
     * nobody answered: cancelling the turn cancels this too.
     */
    private suspend fun askUser(call: ToolCall): Boolean = approvalGate.withLock {
        val pending = CompletableDeferred<Boolean>()
        approval = pending
        _uiState.update { it.copy(pendingApproval = call) }
        try {
            pending.await()
        } finally {
            approval = null
            _uiState.update { it.copy(pendingApproval = null) }
        }
    }

    /** Answers the pending tool question. */
    fun resolveApproval(allowed: Boolean) {
        approval?.complete(allowed)
    }

    /** Switches how much the model may do without being asked. */
    /**
     * The plan the app is holding, which the screen shows and the user can tick.
     *
     * Handed out rather than mirrored into [ChatUiState]: it is already a flow, and a second
     * copy would be a second thing to keep in step for nothing.
     */
    val planning: PlanBoard get() = turns.planning

    /** The question the model is waiting on. See [planning]. */
    val asking: AskBoard get() = turns.asking

    /** What the goal is doing, for the screen to show and the user to stop. */
    val goal: StateFlow<Goal?> get() = goals.goal

    fun setMode(mode: AgentMode) = _uiState.update { it.copy(mode = mode) }

    /**
     * Works through a task without being asked again, until it is done or a bound stops it.
     *
     * Each step is an ordinary turn. That is the whole design: the step goes through `send`
     * like anything the user typed, so it is written to the conversation, folded when the
     * context fills, shown on screen as it streams and cancelled by the same Stop. A runner
     * that drove the engine directly would have to reimplement all of that and would drift
     * from it.
     *
     * The first turn is plan mode, which since it was restricted to the two planning tools
     * runs nothing and asks when the request is ambiguous. Everything after it is the mode
     * the user was already in.
     */
    fun startGoal(task: String) = start(task, Brief.GOAL)

    /**
     * Researching a question, which is a goal with three differences.
     *
     * It plans questions rather than actions, every step is expected to search rather than
     * allowed to, and it ends with a turn that writes the findings up. That last one is the
     * whole point: a goal reports on each step and stops, and a pile of step reports is not
     * a piece of research.
     *
     * Reusing the goal loop rather than writing a second one is deliberate. Everything that
     * makes unattended work safe on a phone already lives there: the foreground service
     * across step boundaries, the engine lock, the thermal and battery checks between steps,
     * the consecutive-failure limit and the stop button. A parallel implementation would
     * have to grow every one of those again and would grow them a little differently.
     */
    fun startResearch(question: String) = start(question, Brief.RESEARCH)

    private fun start(task: String, brief: Brief) {
        if (task.isBlank() || goals.isRunning) return
        if (!_uiState.value.canSend) {
            _uiState.value.refusalReason()?.let { why -> reportError(why) }
            return
        }
        // A plan left over from whatever this conversation last ran through the loop —
        // finished, stopped, or halted, none of which touch the board on their own. Without
        // this a new goal's planning turn that answered in prose rather than a plan read as
        // having one anyway: the old plan was still sitting there, non-null, so the check for
        // "did a plan come back" passed on a plan this goal never proposed and could go on to
        // run steps that belonged to the last one.
        turns.planning.clear()
        goals.start(task, conversationId)
        // Held across the whole goal, including the gaps between steps where the turn's own
        // hold is not in force. Released in work()'s finally, whatever ends it.
        GenerationService.hold(appContext, GenerationService.GOAL, brief.notification)
        goalJob = viewModelScope.launch { work(task, brief) }
    }

    /** Always allowed, and the only control a goal needs to offer. */
    fun stopGoal() {
        goals.stop()
        goalJob?.cancel()
        stop()
        // A goal stopped mid-question leaves ask_user suspended on nothing worth resuming:
        // the turn it belonged to is not going to finish either way. Without this the
        // question card outlived the goal it came from, asking on behalf of a run that had
        // already ended.
        turns.asking.cancel()
        // Set for the turn a cancelled job never reached: consumed once by the turn it was
        // meant for, and nothing else clears it if that turn was the one just cancelled. Left
        // set, it would apply itself to whatever ordinary turn runs next.
        offerAskOverride = null
        toolPromptOverride = null
    }

    /**
     * What the user typed while it was running, kept for the next step.
     *
     * Not sent as a turn of its own. A goal reading a message the moment it arrives would
     * have to interrupt a turn already streaming; holding it until the step boundary is the
     * first point where reading it cannot corrupt anything.
     */
    fun steerGoal(message: String) = goals.steer(message)

    /** Removes a finished, stopped, or recovered goal card from the conversation UI. */
    fun dismissGoal() {
        if (goals.goal.value?.isRunning != true) goals.clear()
    }

    private suspend fun work(task: String, brief: Brief) {
        val wasMode = _uiState.value.mode
        var failures = 0
        val researchSources = linkedSetOf<String>()
        try {
            setMode(AgentMode.PLAN)
            offerAskOverride = if (brief.offersAskDuringPlan) null else false
            // What the plan will have to work with, said before the plan is asked for.
            // The planning turn itself carries no tools, so without this the model plans
            // blind and either invents steps nothing can run or asks about things a step
            // could simply look up. Additive rather than a rewrite of the brief, which is
            // the shape of prompt change this codebase has measured working.
            val available = turns.executionToolNames()
            val snapshot = if (available.isEmpty()) {
                ""
            } else {
                "\n\nWhen the plan runs, these tools will be available: " +
                    available.joinToString(", ") +
                    ". Plan only steps they can carry out."
            }
            if (!turn("${brief.plan}$snapshot\n\n$task")) return
            // Falls back for a brief that has one: measured live, a small model asked to
            // plan a subject it does not recognise sometimes answers in prose instead of
            // proposing anything at all, tool or no tool. Research's own fallback plans
            // one step, the question as asked, rather than stopping before it searched
            // once. See Brief.RESEARCH.
            val proposed = turns.planning.plan.value
                ?: brief.fallbackPlan(task)?.also { turns.planning.restore(it) }
            if (proposed == null || proposed.steps.isEmpty()) {
                goals.halt("No plan came back, so there is nothing to work through.")
                return
            }
            goals.planned(proposed)

            // Research is an explicitly autonomous operation. AUTO keeps web tools
            // available without inheriting PLAN, stopping for every call in ASK, or
            // inheriting YOLO's waived egress checks.
            // PLAN is only for producing the plan. Leaving a generic goal in PLAN would
            // silently strip every tool from all execution turns, so a goal started from
            // the planning UI could never carry out its own steps.
            setMode(goalExecutionMode(brief.executionMode, wasMode))
            var running = true
            while (running) {
                when (step(proposed, failures, brief, researchSources)) {
                    StepOutcome.DONE -> failures = 0
                    StepOutcome.RETRY -> failures++
                    StepOutcome.STOP -> running = false
                }
            }
            writeUp(brief, researchSources)
        } finally {
            // Not simply `wasMode`. Starting from PLAN is exactly the case this app has to
            // handle, since PLAN is where a goal's own plan comes from, and restoring it
            // verbatim would return the user to the one mode with no tools in it, with
            // nothing on screen saying why running a goal changed nothing. Same rule as
            // starting one: see goalExecutionMode.
            setMode(goalExecutionMode(null, wasMode))
            GenerationService.release(appContext, GenerationService.GOAL)
        }
    }

    /**
     * One last turn, for work whose answer is not the last step but everything the steps
     * found.
     *
     * Only when the plan actually finished. A run that was stopped by the user, halted for
     * heat, or gave up after three failures has nothing to write up, and writing it up
     * anyway would produce a confident report from half the research, which is the worst
     * possible output of a research tool.
     */
    private suspend fun writeUp(brief: Brief, researchSources: Set<String>) {
        val finish = brief.finish ?: return
        if (goals.goal.value?.state != GoalState.DONE) return
        val sourceBoundary = if (brief.requiresWebEvidence) {
            "\n\nThese are the only source addresses the app verified were read:\n" +
                researchSources.joinToString(separator = "\n") { "- $it" }
        } else {
            ""
        }
        turn(finish + sourceBoundary)
    }

    /**
     * One step of a goal: check it may run, run it, and decide what the result means.
     *
     * Split out of the loop rather than left inline because a loop body with four ways to
     * stop in it stops being readable as a loop. The three outcomes are the whole contract.
     */
    private suspend fun step(
        proposed: TaskPlan,
        failures: Int,
        brief: Brief,
        researchSources: MutableSet<String>,
    ): StepOutcome {
        val goal = goals.goal.value
        val step = goal?.currentStep
        if (goal?.isRunning != true || step == null) return StepOutcome.STOP
        tooHotOrFlat()?.let {
            goals.halt(it)
            return StepOutcome.STOP
        }

        val steering = goals.takeSteering()
        val planBefore = turns.planning.plan.value ?: proposed
        val doneBefore = planBefore.steps.count { it.done }
        _uiState.update { it.copy(error = null) }
        // The configured tool prompt defaults to "you already know the answer to most
        // questions", which is right for ordinary chat and argues with the one turn whose
        // entire purpose is searching: this step exists because the plan already decided
        // the answer was not already known. See RESEARCH_STEP_TOOL_PROMPT.
        if (brief.requiresWebEvidence) toolPromptOverride = RESEARCH_STEP_TOOL_PROMPT
        if (!turn(stepPrompt(brief, step.text, steering))) return StepOutcome.STOP

        val verifiedSources = lastTurnSteps.correlatedWebResearchSources()
        val refusal = stepRefusal(brief, verifiedSources, planBefore)
        if (refusal == null) {
            researchSources += verifiedSources
        } else {
            // Undo an eager `advance` call before retrying. A model saying it searched
            // is not evidence that a source was actually reached.
            turns.planning.restore(planBefore)
            _uiState.update { it.copy(error = refusal) }
        }
        return stepVerdict(failures, doneBefore, proposed)
    }

    /**
     * Why the step that just ran was not really done, or null when it was.
     *
     * Each of these is a way a model marked its own work without the work having
     * happened, caught on-device; the sentence is what the user sees and what the retry
     * pass reads.
     */
    private fun stepRefusal(
        brief: Brief,
        verifiedSources: Set<String>,
        planBefore: TaskPlan,
    ): String? {
        // What the turn is pointed at is exactly one step, but nothing before this stopped
        // the model calling `advance` more than once in the same turn — several sequential
        // tool calls are one round to AgentRunner, and each one ticks whichever step it
        // names. A single turn could finish the whole plan at once and still pass the
        // evidence check above, which only asks whether some step was researched, not
        // which one or how many.
        val planAfter = turns.planning.plan.value ?: planBefore
        val stepIndex = planBefore.steps.indexOfFirst { !it.done }
        val newlyDone = planAfter.steps.indices.filter { i ->
            planAfter.steps[i].done && planBefore.steps.getOrNull(i)?.done != true
        }
        val skippedAhead = newlyDone.isNotEmpty() && newlyDone != listOf(stepIndex)
        // Calling `advance` on a step that was already done closes nothing: `newlyDone` stays
        // empty, exactly as if the model had not called it at all, and the branch below would
        // read that as silence and tick the *next* step on the model's behalf with no evidence
        // it was worked on — the same failure `skippedAhead` exists to catch, reached from the
        // other direction. A stale or wrong step number is treated the same as too many.
        val calledAdvance = lastTurnSteps.calledAdvance()
        val advancedNothing = calledAdvance && newlyDone.isEmpty()
        // A step that tried a tool and every one of them failed or was declined is not
        // evidence of work done, and `tickIfTheModelDidNot` cannot tell that from a step
        // that needed no tool at all: both leave `doneBefore` unchanged. Checked only when
        // the model did not call `advance` itself, since a step that closed itself despite a
        // failed tool call is judged by whether it named the right step, not by this.
        val allToolsFailed = !calledAdvance && lastTurnSteps.everyToolFailed()

        return when {
            // Named by half, because the retry pass reads this sentence and a model that
            // is told only "you did not research" repeats whichever half it already did:
            // measured as the verification-spiral shape, search-fail-search-fail-halt.
            // Saying which half is missing is the smallest steer that breaks the loop.
            brief.requiresWebEvidence && verifiedSources.isEmpty() ->
                if (lastTurnSteps.searchedSomething()) {
                    "This step searched but never opened a result: fetch one of the " +
                        "addresses the search returned, then answer from what it says."
                } else {
                    "This step did not get a successful search: search the web for it, " +
                        "then open the best result before answering."
                }

            skippedAhead ->
                "This step closed more than the one it was given, so it was not marked done."

            advancedNothing ->
                "This step's advance call did not close the step it was given, so it was " +
                    "not marked done."

            allToolsFailed ->
                "This step's tool calls did not succeed, so it was not marked done."

            else -> null
        }
    }

    /** A successful search happened this turn, whatever became of its results. */
    private fun List<AgentStep>.searchedSomething(): Boolean = filterIsInstance<AgentStep.Ran>()
        .any { it.successful && it.evidence is ToolEvidence.Search }

    private fun List<AgentStep>.calledAdvance(): Boolean =
        any { it is AgentStep.Ran && it.call.name == "advance" }

    /** Tools were tried, and not one of them succeeded. */
    private fun List<AgentStep>.everyToolFailed(): Boolean =
        any { it is AgentStep.Ran || it is AgentStep.Skipped } &&
            none { it is AgentStep.Ran && it.successful }

    /** What the step's ending means for the goal: carry on, one retry, or halt. */
    private fun stepVerdict(failures: Int, doneBefore: Int, proposed: TaskPlan): StepOutcome {
        val failure = _uiState.value.error ?: run {
            tickIfTheModelDidNot(doneBefore)
            goals.advanced(turns.planning.plan.value ?: proposed)
            return StepOutcome.DONE
        }
        // A step that ended in an error has not been done, whatever the plan says. One
        // retry, because the common failure is a tool call the model can repair once it
        // reads the message, and then a halt: a loop that retries forever on a phone is
        // a flat battery rather than an answer.
        if (failures + 1 >= MAX_STEP_FAILURES) {
            goals.halt(
                "Stopped after $MAX_STEP_FAILURES steps in a row that did not finish. " +
                    "The last problem was: $failure",
            )
            return StepOutcome.STOP
        }
        return StepOutcome.RETRY
    }

    /**
     * Closes the step just finished, unless the model closed it itself.
     *
     * Ticked by the app rather than by the model, because asking a 2.6B whether it succeeded
     * is asking the thing that just failed to mark its own work, and a goal that never ticks
     * never ends.
     *
     * The condition is the bug this replaces. `advance` is a tool the model can call, and
     * when it did, the host went looking for the first unfinished step and found the *next*
     * one, so a single turn closed two. A four step plan could report itself finished having
     * done half of it, and the step it skipped was as likely to be "check the result" as
     * anything else.
     *
     * @param doneBefore how many steps were closed before the turn ran.
     */
    private fun tickIfTheModelDidNot(doneBefore: Int) {
        val steps = turns.planning.plan.value?.steps ?: return
        if (steps.count { it.done } != doneBefore) return
        val index = steps.indexOfFirst { !it.done }
        if (index >= 0) turns.planning.tick(index)
    }

    /** One step, run and waited for. False when it did not run at all. */
    private suspend fun turn(prompt: String): Boolean {
        if (!send(prompt)) {
            goals.halt(_uiState.value.error ?: "The turn could not be started.")
            return false
        }
        awaitTurn()
        return goals.goal.value?.isRunning == true
    }

    /**
     * Waits for the turn to finish, rather than for it to have started.
     *
     * [send] registers a short job of its own that writes the question down and then calls
     * [generate], which replaces `generationJob` with the one that actually produces the
     * reply. Joining once joined whichever of the two happened to be current at that
     * instant, which is almost always the first, so the goal loop came back the moment the
     * turn had begun.
     *
     * What that looked like was not a race that sometimes lost. The loop read the plan
     * board before the planning turn could put anything on it, found nothing, and halted
     * with "No plan came back"; and where a plan was already there the next step called
     * [send] while the previous turn was still generating, was refused, and halted saying
     * the turn could not be started. A goal on a real phone, where a reply takes seconds,
     * lost this every time.
     *
     * So: join, and if something replaced the job while waiting, join that too.
     */
    private suspend fun awaitTurn() {
        while (true) {
            val job = generationJob ?: return
            job.join()
            if (generationJob === job) return
        }
    }

    /**
     * Whether the phone can take another step.
     *
     * Checked between steps rather than during one, because stopping halfway through a
     * reply wastes the whole step. Heat first: sustained decode was measured falling from
     * about 25 to about 19 tokens a second over three and a half minutes, and a goal is the
     * one thing here that runs long enough to reach the top of that curve.
     */
    private fun tooHotOrFlat(): String? {
        if (runtime.thermalLevel() == ThermalLevel.CRITICAL) {
            return "Paused: the phone is too hot to keep going. Start it again when it has " +
                "cooled down."
        }
        val battery = appContext.getSystemService<BatteryManager>()
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: return null
        if (battery in 1..<MIN_BATTERY_PERCENT) {
            return "Paused at $battery% battery. Working on its own uses a lot of it, so " +
                "this stops rather than flattening the phone."
        }
        return null
    }

    private fun stepPrompt(brief: Brief, step: String, steering: List<String>): String =
        buildString {
            append(brief.step)
            append("\n\n")
            append(step)
            if (steering.isNotEmpty()) {
                append("\n\nSince you started, I have said: ")
                append(steering.joinToString(" "))
            }
        }

    /**
     * Re-plans threads for this reply, and says whether to go ahead.
     *
     * The decision to stop rather than to stop slightly less lives in the runtime; what
     * lives here is what the user is told when it does.
     */
    private suspend fun applyThreadPlan(): Boolean {
        _uiState.update { it.copy(isThrottled = runtime.isThrottling()) }
        if (runtime.planThreads()) return true

        _uiState.update { state ->
            state.copy(
                transcript = state.transcript.withoutEmptyReply(),
                isGenerating = false,
                error = "The phone is too hot to keep generating. It will work again " +
                    "once it cools down.",
            )
        }
        return false
    }

    /**
     * Folds older turns into a summary once the context window gets tight.
     *
     * The whole of it is in [Folding], which is where the reasoning lives too. What is here
     * is the two things that object cannot see for itself: which conversation this is, read
     * again after the fold in case the user moved, and whether the engine is mid-decode.
     */
    private suspend fun compactIfNeeded(force: Boolean = false) =
        folding.fold(force = force, engineIsDecoding = isDecoding) { conversationId }

    /** Clears the conversation and the model's KV cache, keeping the model loaded. */
    /**
     * Persists what is sitting unsent in the composer, debounced by the caller.
     *
     * Keyed to the conversation on screen, with zero standing for the chat that does not
     * exist yet, so a message typed before the first send survives the app closing the
     * same way one typed into an old chat does. Failures are logged and dropped: a draft
     * that could not be saved must not interrupt the typing it was copied from.
     */
    fun saveComposerDraft(text: String) {
        val key = conversationId ?: 0L
        viewModelScope.launch {
            runCatching { writer.inOrder { saveDraft(key, text) } }
                .onFailure { Log.w("OpenWeights", "draft not saved", it) }
        }
    }

    private fun loadComposerDraft() {
        val key = conversationId ?: 0L
        viewModelScope.launch {
            val stored = runCatching { writer.inOrder { draft(key) } }.getOrDefault("")
            // Only if the screen still shows the conversation this was loaded for: the
            // read is behind the write queue, and a fast switch could otherwise deliver
            // one chat's draft into another's composer.
            if ((conversationId ?: 0L) == key) {
                _uiState.update { it.copy(composerDraft = stored.takeIf(String::isNotEmpty)) }
            }
        }
    }

    /**
     * The file this model's warmed head outlives the process in.
     *
     * Keyed to the model file's name, size and modification time, so replaced weights
     * never meet an old state; the prefix bytes themselves are compared inside the
     * engine, so a new day's date line or changed settings simply miss and recompute.
     * Siblings beyond a couple of other models are pruned — each file is roughly the
     * model's KV for two thousand tokens, tens of megabytes.
     */
    private fun warmStore(): File? {
        val model = loadedFile ?: return null
        val dir = File(appContext.cacheDir, "warm")
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val name = "${model.name}-${model.length()}-${model.lastModified()}.warm"
        dir.listFiles()
            ?.filter { it.name != name }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEPT_WARM_STORES)
            ?.forEach { it.delete() }
        return File(dir, name)
    }

    /**
     * Reads the fresh-chat prefix into the engine cache while nobody is waiting — and,
     * when a conversation is on screen, the conversation after it.
     *
     * Always the head first, because the head warm is where a hybrid model's restore
     * snapshot is captured, and the conversation warm is forbidden from taking that slot:
     * a conversation in the snapshot would cost every future new chat its restore. The
     * order costs nothing — the conversation starts with the head, so its warm reuses
     * every byte the head warm just read.
     *
     * The conversation stage is what makes a fold, a branch, a reopened chat and a
     * settings change cheap: each rewrites the prompt from the root, and this reads the
     * rewritten prompt back in the background so the next question pays only for its own
     * words. Composed by [engineMessages] itself — the warm ends exactly where the next
     * prompt appends the question, and the tool-notes decoration only ever lands on that
     * question, so every warmed byte is a byte the send reuses.
     */
    private fun warmEngine() {
        val generation = ++warmGeneration
        warmJob?.cancel()
        warmJob = viewModelScope.launch {
            val preparingFirstResponse = _uiState.value.isPreparingFirstResponse
            try {
                if (loadedFile == null || _uiState.value.isLoadingModel) return@launch
                // Cancelling the job above does not reach the engine: the native read runs
                // to its end whatever the coroutine's state, holding the engine the whole
                // time, so the warm this launches found it busy and gave up — and the old
                // warm then finished with the prompt it had, snapshot included. A tool toggle
                // during the twenty-second load warm, a reopen on a store miss, a new chat
                // right after load: each warmed nothing. So the running warm is interrupted
                // first, the way a turn interrupts one, and this warm reads its own prompt.
                turns.yieldWarms()
                // Refreshed exactly the way a turn refreshes it, because the instruction that
                // says tools exist goes in or stays out of the prefix with this flag — and a
                // warm rendered under yesterday's answer warms a prompt nobody will send.
                _uiState.update { it.copy(toolsAvailable = turns.hasEnabledTools()) }
                val state = _uiState.value
                // The day refreshes only at a fresh chat's warm. A conversation keeps the
                // day it started with — flipping it mid-conversation is the midnight bug
                // below — and a new chat after midnight warms the new day in the background
                // before anybody has typed.
                if (state.transcript.isEmpty()) PromptDay.refresh()
                val head = state.prefixMessages()
                if (head.isEmpty()) return@launch
                // The same sampler shape a real turn would pass, because thinking flags reach
                // the template and the template shapes the bytes being warmed.
                val params = state.preferences.toSamplerParams().let {
                    if (state.toolsAvailable) it.copy(thinking = true) else it
                }
                val headWarm = turns.warmFreshChat(
                    head,
                    withTools = state.toolsAvailable,
                    params = params,
                    store = warmStore(),
                )
                if (preparingFirstResponse) {
                    _uiState.update { it.copy(isPreparingFirstResponse = false) }
                }
                // A head warm that could not run or kept nothing — the engine refused it, a
                // turn interrupted it at zero, or the compute failed the way a swapping phone
                // makes it fail — is no base to stack a longer read on. The conversation
                // stays cold and the next turn reads it once, in front of the user, which is
                // the price the failure already set.
                if (headWarm == null || headWarm.warmedTokens + headWarm.reusedTokens == 0) {
                    return@launch
                }
                if (state.transcript.isEmpty()) return@launch
                val conversation = state.engineMessages()
                // A conversation carrying media cannot be warmed: the warm path renders text
                // only, so its bytes diverge at the first picture and warm nothing the send
                // could use. Media turns re-evaluate the conversation anyway; see the
                // first-turn-latency notes.
                if (conversation.any { message -> message.parts.any { it !is MessagePart.Text } }) {
                    return@launch
                }
                turns.warmConversation(
                    conversation,
                    withTools = state.toolsAvailable,
                    params = params,
                )
            } finally {
                // A compute failure or cancellation must not leave the composer disabled
                // forever. An older warm is allowed to finish its cleanup without clearing
                // the flag belonging to a newer load.
                if (preparingFirstResponse && generation == warmGeneration) {
                    _uiState.update { it.copy(isPreparingFirstResponse = false) }
                }
            }
        }
    }

    /**
     * The day a fresh conversation starts on is the day it keeps, so it is fixed at the
     * first question as well as at the warm: a chat left empty across midnight had warmed
     * yesterday and then sent yesterday for the whole of its life. The head warm never
     * includes the date, so refreshing here invalidates nothing that was warmed.
     */
    private fun pinDayForFreshChat() {
        if (_uiState.value.transcript.isEmpty()) PromptDay.refresh()
    }

    fun newChat() {
        // A goal is driven by its own job, separate from generationJob, and reading the
        // board rather than the transcript to decide what to do next: stopping only the
        // turn in flight left it free to join a wait, see the board still says running, and
        // start the next step against whatever conversation happens to be open by the time
        // it wakes — this one, mid-switch. stopGoal ends the loop itself, not just its turn.
        if (goals.goal.value?.isRunning == true) stopGoal() else stop()
        viewModelScope.launch {
            // Awaited, not merely cancelled: a generation still unwinding writes into the
            // transcript this is about to replace, and would do so after the engine cache
            // has already been reset underneath it.
            generationJob?.join()
            // The same reasoning as generationJob, for the goal's own loop: cancel() only
            // requests it stop, and work() still unwinding past that request can go on to
            // read or write the very board goals.clear() below is about to reset, or the
            // planning board turns.planning.clear() is.
            goalJob?.join()
            // Before the engine reset, not after. Resetting the context suspends, and for
            // as long as it did the screen still had the old conversation's id and a live
            // Send button: a message sent in that window went to the conversation being
            // left — which, when newChat is being run *by* a delete, no longer exists, so
            // the insert had no parent row to hang from. Nothing between here and the
            // reset reads the id, so moving it up only closes the window sooner.
            switchTo(null)
            // The cache is deliberately NOT reset here any more. Every conversation on this
            // screen starts with the same instructions-and-tools prefix, and the engine
            // aligns the cache itself: a transformer rolls the old conversation back to
            // that shared prefix, and a hybrid, which cannot roll back, restores the warm
            // snapshot taken at load. Resetting was a full first-turn re-read on every new
            // chat — about twenty seconds of prefill on the 1.2B before a short question —
            // for hygiene the alignment already guarantees byte-for-byte.
            // A research turn interrupted mid-plan can leave these set; unconsumed, the
            // new chat's first turn would silently inherit an override that belonged to
            // the conversation just left behind. See start().
            offerAskOverride = null
            toolPromptOverride = null
            turns.planning.clear()
            // The board is one object for the whole app: a goal left over from the chat just
            // left behind would otherwise be on screen here too, its card naming a task this
            // conversation never asked for. Safe to clear unconditionally now that a running
            // one has already been stopped above.
            goals.clear()
            turns.asking.cancel()
            _uiState.update {
                it.copy(
                    transcript = emptyList(),
                    // Emptied with the transcript it belongs to. A record of what a tool
                    // returned in one chat is not evidence in the next one.
                    toolNotes = ToolNotes(),
                    engineHistory = null,
                    compaction = null,
                    contextUsed = 0,
                    error = null,
                )
            }
            // Cheap when the cache already begins with the prefix, which is every new chat
            // whose settings did not change; a real re-read only happens when they did.
            warmEngine()
        }
    }

    /** Folds earlier turns immediately, rather than waiting for the context to fill. */
    fun compactNow() {
        if (_uiState.value.isGenerating || _uiState.value.isCompacting) return
        // Folding runs the model to write the summary, so it needs the same check send makes.
        if (loadedModelHasGone()) return
        viewModelScope.launch {
            // The summarizer is about to take the engine's thread; see generate().
            turns.yieldWarms()
            if (compactIfNeeded(force = true)) warmEngine()
        }
    }

    /**
     * Reopens a past conversation.
     *
     * The KV cache holds whichever conversation was last generated, so it is cleared: the
     * engine's prefix matching would otherwise find no common prefix and silently
     * re-decode anyway, but clearing makes the state defined rather than accidental.
     */
    fun openConversation(id: Long) {
        viewModelScope.launch {
            // A read that throws used to leave the launch with nothing above it to catch. The
            // chat on screen is untouched by a failed reopen, so the sentence says the one
            // thing that changed: the other chat did not open.
            readReportingFailure(CHAT_UNREADABLE, ::reportError) { reopen(id) }
        }
    }

    /**
     * The engine's own record of this conversation, or null when it cannot be trusted.
     *
     * Trusted only when it runs exactly through the last stored message: one stamped with
     * an earlier reply describes a history that was edited or continued since, and is left
     * to the fallback path. And only into the template that wrote it — the conversation's
     * own model name is no guide, because switching models renames the conversation, so it
     * always claims the current model while the record still holds the old one's rendering.
     */
    private suspend fun usableEngineHistory(
        id: Long,
        messages: List<MessageEntity>,
    ): List<EngineHistoryEntity>? = writer.inOrder { engineHistory(id) }
        .takeIf { rows ->
            rows.isNotEmpty() && rows.first().replyMessageId == messages.lastOrNull()?.id
        }
        ?.takeIf { rows -> rows.first().modelName == _uiState.value.modelName }

    /**
     * Makes [id] the conversation on screen, and forgets what the model built in the last one.
     *
     * The files it made are the user's now: nobody in the new chat watched them being made,
     * so replacing or deleting one asks again.
     */
    private fun switchTo(id: Long?) {
        conversationId = id
        artifacts.cleared()
    }

    private suspend fun reopen(id: Long) {
        // Read before anything is disturbed. The row's existence does not depend on a reply
        // still being written, so this one read needs no queue, and taking it first means a
        // database that will not answer costs the user nothing: the turn they had running is
        // still running, and the sentence they get is about the chat that did not open.
        val conversation = writer.inOrder { conversation(id) }

        // See newChat: stopping only the turn in flight leaves a goal's own loop free to
        // read the board, find itself still running, and start its next step against
        // whichever conversation is open by the time it wakes — this one, mid-switch. Not
        // when the running goal already belongs to the conversation being reopened, though:
        // that is the same case the cleanup below carves out, and stopping a goal because
        // someone reopened the very chat it is already working in would be a switch that
        // never happened.
        val runningElsewhere = goals.goal.value?.isRunning == true &&
            goals.goal.value?.conversationId != id
        if (runningElsewhere) stopGoal() else stop()
        generationJob?.join()
        // Same reasoning as newChat: cancel() only asks work() to stop, and it unwinding
        // past that request must not go on to touch the board this function is about to
        // read and clear for the conversation being opened.
        if (runningElsewhere) goalJob?.join()

        if (conversation == null) {
            // Deleted between the tap and this read; adopting the id would make the
            // next message violate the foreign key.
            newChat()
            return
        }

        // Behind the write queue, and after the stop: a reply from the turn that just ended
        // may not have been inserted yet, and reopening the same chat without it would show a
        // question with no answer and then resend it.
        val messages = writer.inOrder { messages(id) }
        val stepsByMessage = writer.inOrder { toolSteps(id) }
        val storedEngineHistory = usableEngineHistory(id, messages)

        // Asked again, and this is the ask that counts. The check above was made several
        // queued reads and two joins ago, and a delete confirmed in the drawer in the
        // meantime sits ahead of this in the same queue: adopting the id after that landed
        // puts a conversation that no longer exists on screen, and its next message
        // violates the foreign key. Nothing suspends between this and the line below, so
        // either the delete is already visible here — and this returns — or it is still to
        // come, and its own `conversationId == id` will close what this just opened.
        if (writer.inOrder { conversation(id) } == null) {
            newChat()
            return
        }
        switchTo(id)
        nextEntryId = messages.size.toLong()
        // The board is one object for the whole app, so a plan left in it is on screen in
        // whatever chat is opened next and is pinned to the tail of that chat's prompt.
        // newChat has always cleared it; this is the switch people actually use.
        //
        // Not when the goal on the board is tagged with the very conversation being opened,
        // though: that is recovery, not a switch. A goal interrupted by the process dying
        // comes back HALTED so a person can review it, and reopening its own conversation on
        // the next launch — which restoring the last chat does automatically — used to read
        // as leaving it behind and clear it before anyone had a chance to see it come back.
        if (goals.goal.value?.conversationId != id) {
            turns.planning.clear()
            // Same reasoning: a goal (and any question it left pending) belongs to the
            // conversation that started it, not to whichever one is opened next. Safe to
            // clear unconditionally here — a still-running one has already been stopped
            // above — because this branch is only reached when it belongs elsewhere.
            goals.clear()
            turns.asking.cancel()
        }

        // A conversation continued under a different model would mix two models'
        // voices in one transcript, and the history would not say which said what.
        val currentModel = _uiState.value.modelName
        val mismatch = conversation.modelName != null &&
            currentModel != null &&
            conversation.modelName != currentModel

        // Folded once per message rather than once over the whole history, because that is
        // what withSteps is written to be called as: it replaces a repeated call with the
        // newer one but does not move it, so a call repeated three messages apart landed back
        // in its *first* message's position rather than its last when this folded the whole
        // conversation in a single call. Order matters here because trimming reads it as
        // recency -- the newest note is "whichever the list ends with" -- so that one bug
        // could make a reopened conversation trim away the actually-newest fact under budget
        // pressure while keeping a stale one in its place. Folding per message is exactly the
        // sequence a live conversation already produces one [TurnRunner] pass at a time.
        //
        // Whether a note is private or carries a stranger's text is looked up from the
        // current tool registry rather than stored, so a tool a build no longer ships answers
        // null here and the note reads as neither rather than the reopen failing over a
        // conversation from an older version.
        val foldedThrough = conversation.compactionThroughIndex
            .takeIf { conversation.compactionSummary != null }
        val restoredNotes = restoredToolNotes(messages, stepsByMessage, foldedThrough)

        _uiState.update { state ->
            val reopened = state.copy(
                toolNotes = restoredNotes,
                // Reopened entries are numbered by index, so the record runs through the
                // last of them by construction — the replyMessageId check above already
                // proved it describes exactly these messages. Not under another model,
                // whose template the record's rendered tool rounds do not belong to, and
                // not under a stored fold, where there is no way to tell a record captured
                // after the fold from one the fold made obsolete.
                engineHistory = restoredEngineHistory(
                    storedEngineHistory?.takeIf {
                        !mismatch && conversation.compactionSummary == null
                    },
                    messages.size,
                ),
                transcript = messages.toTranscript(
                    conversation.compactionSummary?.let { conversation.compactionThroughIndex },
                    stepsByMessage,
                ),
                compaction = conversation.compactionSummary?.let {
                    Compaction(it, conversation.compactionThroughIndex, 0)
                },
                error = if (mismatch) {
                    "This chat was written by ${conversation.modelName}. Replies will " +
                        "now come from $currentModel."
                } else {
                    null
                },
            )
            // Not zero. The cache is empty because the model has not read this
            // conversation yet, but the next turn will read all of it, and everything
            // that sizes itself against what is left took the zero for a free window.
            // Reopen a long chat, attach a document, and it was measured against a
            // window that was already spoken for.
            reopened.copy(contextUsed = reopened.estimatedPromptTokens())
        }

        // After the transcript is on screen, not before: this only matters for the next
        // reply's KV cache, and the engine work behind it shares a single-threaded
        // dispatcher with model loading. The old reset here made the cache state defined;
        // the warm does that and more — alignment reuses whatever prefix the reopened
        // conversation shares with the cache, restores the head snapshot where rollback
        // is refused, and reads the rest in the background, so the first question in a
        // reopened chat pays only for its own words. Skipped mid-load by the warm's own
        // guard; the load's finishing warm reads this conversation instead.
        warmEngine()
    }

    /**
     * The engine's stored record as live state, from rows already proven to describe
     * exactly the reopened messages.
     *
     * Reopened entries are numbered by index, so the record runs through the last of
     * them by construction — the replyMessageId check at the read already proved it
     * describes exactly these messages. The caller withholds the rows under another
     * model, whose template the record's rendered tool rounds do not belong to, and
     * under a stored fold, where there is no way to tell a record captured after the
     * fold from one the fold made obsolete.
     */
    private fun restoredEngineHistory(
        rows: List<EngineHistoryEntity>?,
        messageCount: Int,
    ): EngineHistory? = rows?.let {
        EngineHistory(
            messages = rows.map { row ->
                ChatMessage.text(ChatRole.of(row.role), row.text)
                    .copy(toolCallId = row.toolCallId)
            },
            throughCount = messageCount,
            throughEntryId = (messageCount - 1).toLong(),
        )
    }

    /**
     * Saves settings for the loaded model.
     *
     * Most of them are read when the next reply starts and so take effect immediately. Two
     * are not: the context window and which processor holds the layers are both fixed when
     * llama.cpp maps the weights, and the only way to change either is to map them again.
     *
     * The processor is reloaded here rather than left for whenever the model next happens to
     * load. Left, it was indistinguishable from a broken switch: the user moved it to GPU,
     * the top bar went on saying CPU because the weights really were still on the CPU, and
     * nothing on screen said that the setting was waiting for an event the user had no
     * reason to expect. The reload costs three to seven seconds on the hardware measured,
     * and the screen already has a state for that.
     *
     * The context window is deliberately not reloaded with it. Growing it can fail on a
     * phone that cannot spare the memory, and failing a load the user did not ask for would
     * leave them with no model at all in exchange for a number they were only editing.
     */
    fun savePreferences(preferences: ModelPreferences) {
        val model = preferencesKey ?: return
        viewModelScope.launch {
            // Either half moving is a reload, because both reach the loader: the writing
            // half decides which layers are resident and the reading half decides whether
            // large batches may be offloaded, and neither is re-read on an existing context.
            // Checking only one of them is how a control turns into a label — the setting
            // sits in storage until the model happens to load again, which for most people
            // is never, while the top bar goes on truthfully reporting the old answer.
            val current = _uiState.value.preferences
            val movedProcessor = current.prefillTarget != preferences.prefillTarget ||
                current.decodeTarget != preferences.decodeTarget
            runtime.saveSettings(model, preferences)
            _uiState.update { it.copy(preferences = preferences) }
            // The conversation is kept: only the weights move, and the transcript is text.
            // The cache does not survive, which is why the next reply re-reads it.
            if (movedProcessor) {
                loadedFile?.let { loadModel(it, keepConversation = true) }
            } else {
                // The prefix may have changed with the settings — a new system prompt, a
                // different answer length — so the warmed bytes are re-derived; the warm
                // itself is a no-op when they did not.
                warmEngine()
            }
        }
    }

    fun resetPreferences() {
        val model = preferencesKey ?: return
        viewModelScope.launch {
            runtime.resetSettings(model)
            _uiState.update { it.copy(preferences = ModelPreferences()) }
        }
    }

    /**
     * Gives a conversation a name the user chose. Silently does nothing if it is all
     * whitespace, which is what the dialog's disabled Save button already prevents; this
     * is the half that holds when the two disagree.
     */
    fun renameConversation(id: Long, title: String) {
        viewModelScope.launch {
            reportingFailure { filing.rename(id, title) }
        }
    }

    /** Pins a conversation to the top of the drawer, or unpins it. */
    fun setConversationPinned(id: Long, pinned: Boolean) {
        viewModelScope.launch {
            reportingFailure { filing.setPinned(id, pinned) }
        }
    }

    /**
     * Files a conversation away, or takes it back out.
     *
     * Archiving the open one leaves the screen on a chat that is no longer in the list,
     * so it starts a fresh one, the same way deleting the open one does. Unarchiving does
     * not: the conversation being unfiled is one the user is looking at in the archive
     * section, and closing it would be answering a different question.
     */
    fun setConversationArchived(id: Long, archived: Boolean) {
        viewModelScope.launch {
            // Stopped and awaited before the write, for the same reason deleting the open
            // one is, and one more besides: a reply still unwinding writes a message into
            // this conversation, and `ChatRepository.touch` clears `archivedAt` on every
            // message — a chat being used is not one that has been put away. Archiving
            // mid-reply therefore un-archived itself a second later, from a write the user
            // had no idea was still outstanding.
            if (archived && conversationId == id) {
                // The goal's own loop as well as the turn, and this is the line newChat
                // uses for the same reason: a running goal decides what to do next from
                // the board rather than from the transcript, so stopping only the turn in
                // flight leaves it free to join the wait, see the board still say running,
                // and start another turn into the conversation being filed away. That
                // turn's message would clear `archivedAt` on its way through `touch`.
                if (goals.goal.value?.isRunning == true) stopGoal() else stop()
                generationJob?.join()
                goalJob?.join()
            }
            if (writeOrNull { filing.setArchived(id, archived) } == null) {
                // Nothing else follows a write that did not go through. Carrying on to
                // newChat() would replace the conversation the user is looking at with a
                // blank one, on the strength of a filing that never happened, and clear
                // the only message on screen explaining why.
                reportError(STORAGE_FAILED)
                return@launch
            }
            if (!archived) return@launch
            // Do not bring it back. The saved handle remembers the last conversation on
            // purpose, but the last thing done to this one was to put it away, and
            // reopening it on the next launch would leave an archived chat filling the
            // screen while hidden in the list. `restoring` is the same id in flight: on a
            // cold start it holds the conversation to reopen for as long as the model
            // takes to load, which is exactly long enough to open the drawer and archive
            // it, and it is not `conversationId` yet.
            if (restoring == id) restoring = null
            if (savedState.get<Long>(LAST_CONVERSATION) == id) {
                savedState.remove<Long>(LAST_CONVERSATION)
            }
            // Asked again rather than remembered from before the joins. Those suspend for
            // as long as a reply takes to unwind, and the drawer is still open over the
            // screen the whole time: another conversation tapped during that wait would
            // otherwise be the one this closes.
            if (conversationId == id) newChat()
        }
    }

    /** Deletes a conversation; if it is the open one, the screen returns to a blank chat. */
    fun deleteConversation(id: Long) {
        viewModelScope.launch {
            // Stopped and awaited first when it is the open one, for the reason newChat
            // gives about the same wait: a turn still unwinding writes a reply into this
            // conversation, and if the rows have gone by then the insert has no parent to
            // hang from. The attachments are worse, because those are files, and the
            // prompt reader on the engine's thread may still have one open when this
            // deletes it. Both races were open while the stop happened last.
            val wasOpen = conversationId == id
            if (wasOpen) {
                stop()
                generationJob?.join()
            }

            // Read before deleting: the rows are what says which files were attached, and
            // once they are gone nothing else on disk remembers, so the photos would stay
            // forever in a folder the user cannot see. Behind the write queue, so a reply
            // still being written is deleted with the rest rather than after it.
            reportingFailure {
                val orphaned = writer.inOrder {
                    val attached = messages(id).flatMap { it.attachments.decodeAttachments() }
                    deleteConversation(id)
                    attached
                }
                staging.discard(orphaned)
                // Re-read for the same reason archiving re-reads it: the join above waits
                // out a reply, and a conversation opened from the still-open drawer during
                // that wait is not the one being deleted and must not be closed with it.
                if (conversationId == id) newChat()
            }
        }
    }

    /** Stops the running generation, keeping whatever has been produced so far. */
    fun stop() {
        // Only what this screen is running. The runtime is shared with the watches and the
        // goal, and a reopen or a delete with no turn in flight used to cancel whichever of
        // those had the engine at that moment, truncating a reply nobody had asked to stop.
        val job = generationJob?.takeIf { it.isActive } ?: return
        runtime.cancel()
        job.cancel()
    }

    /**
     * Settles the screen on one finished pass, and says what it settled on.
     *
     * [raw] is everything the engine produced, which is ahead of the screen by up to one
     * coalescing window. The string returned is what the entry now shows, and it is what
     * the turn writes to storage once it has finished asking for tools, so what is shown,
     * what is re-read on reopening, and what is sent as history next turn are all the same
     * string.
     */
    private fun applyCompletion(
        event: GenerationEvent.Completed,
        raw: String,
        // The whole turn so far, every pass folded together. The entry under the reply
        // describes the reply, and a reply that took three passes took what the three of
        // them took; [event]'s own stats stay in use below for the things that are genuinely
        // this pass's — the work ledger, and where the context now stands.
        turnStats: GenerationStats = event.stats,
    ): String {
        val parsed = parseAssistantReply(raw)
        val streamed = _uiState.value.transcript.lastOrNull()
        // Whichever source has it. llama.cpp separates thinking itself for the formats it
        // knows; for the rest it comes out of the buffer, and what was already on screen
        // is the last resort.
        val reasoning = event.reasoning.ifEmpty { null } ?: parsed.reasoning ?: streamed?.reasoning
        // The engine only cleans a reply when llama.cpp's parser recognised a tool call in
        // it. For an ordinary reply it hands the whole thing back untouched, thinking and
        // all, and preferring that is how the same reasoning came to be shown twice: once
        // in the block where it belongs and again as the answer. That is what "the model is
        // quoting the prompt at me" turned out to be. Text the engine did not change is
        // text it did not parse, so the local split is the better of the two.
        val engineCleaned = event.content.isNotEmpty() && event.content != raw
        // Stripped when the engine did not clean it, because then nothing has. A call whose
        // name belongs to no tool cannot be salvaged into anything, so neither parser
        // removes it and the invocation itself was what the user read.
        val answer = if (engineCleaned) event.content else parsed.answer.withoutToolMarkup()

        // The template test at load asks whether being told not to think changes the
        // prompt; it cannot ask whether the weights care. This is the other half of that
        // question, answered by the only thing that can answer it.
        if (_uiState.value.thinkingSwitchWasIgnored(reasoning)) {
            preferencesKey?.let(runtime::rememberIgnoresThinkingSwitch)
            _uiState.update { it.copy(supportsThinking = false) }
        }
        // A turn whose last pass was only a call that could not run has no answer in it,
        // and a blank reply is never written down; the steps under it were, on screen, the
        // whole reply, and they vanished with the row on reopen. Said in a sentence, so
        // what the user watched is what the conversation keeps.
        val settled = answer.ifBlank { unansweredNote(streamed?.blocks.orEmpty()) }
        val canonical = canonicalText(reasoning, settled)

        updateLastEntry {
            it.copy(
                text = canonical,
                // Blank only when the pass produced nothing, and then there is nothing in
                // the cache to match either: null keeps [text] as the fallback.
                history = raw.takeIf { it.isNotBlank() }?.let {
                    assistantHistoryText(it, event.stats.thinkingPrefilled)
                },
                answer = answer,
                reasoning = reasoning,
                isStreaming = false,
                isReasoningInProgress = false,
                tokensPerSecond = turnStats.decodeTokensPerSecond,
                prefillTokensPerSecond = turnStats.prefillTokensPerSecond,
                timeToFirstTokenMs = turnStats.timeToFirstTokenMs,
                generatedTokens = turnStats.generatedTokens,
                promptTokens = turnStats.totalPromptTokens,
                cachedTokens = turnStats.cachedTokens,
                prefillMs = turnStats.prefillMs,
                decodeMs = turnStats.decodeMs,
            )
        }
        recordWork(event.stats)
        _uiState.update {
            it.copy(
                contextUsed = event.stats.contextUsed,
                contextSize = event.stats.contextSize,
                // Read off the pass that just finished: the cache now holds this whole
                // conversation, and its length in characters is right here, so the ratio
                // between them is measured rather than assumed. Kept when a pass reports
                // nothing usable, so one odd reading cannot throw the estimate away.
                charsPerToken = event.stats
                    .charsPerToken(it.engineMessages().sumOf { message -> message.text.length })
                    ?: it.charsPerToken,
                // Falls back to what is already there rather than clearing it. A reply that
                // ends normally has no warning of its own, and setting null wiped whatever
                // the turn had already reported: a write that would not go through was
                // announced and then quietly withdrawn by the answer arriving. Nothing goes
                // stale, because send clears the line at the start of every turn.
                error = event.reason.warning() ?: it.error,
            )
        }
        return canonical
    }

    /**
     * What a turn that produced steps and no answer says for itself.
     *
     * Empty when there were no steps either, which is the blank turn the callers already
     * refuse to write.
     */
    private fun unansweredNote(blocks: List<TurnBlock>): String {
        val last = blocks.lastOrNull { it is TurnBlock.Step } as? TurnBlock.Step ?: return ""
        return when (val step = last.step) {
            is AgentStep.Ran ->
                "The turn ended after ${step.call.name} returned, with no answer written."
            is AgentStep.Skipped ->
                "The turn ended on a call to ${step.call.name} that did not run."
            is AgentStep.Requested ->
                "The turn ended on a call to ${step.call.name} that did not run."
        }
    }

    /**
     * Finishes a reply that was stopped or that failed part way.
     *
     * The buffer runs ahead of the screen, so it is applied in full before anything else:
     * stopping must not silently discard the tokens produced since the last publish. An
     * empty buffer means nothing was produced, and a blank turn is worse than no turn, so
     * the placeholder is removed instead of being written down.
     */
    private suspend fun finishInterrupted(raw: String) {
        if (raw.isEmpty()) {
            _uiState.update { state ->
                if (state.transcript.lastOrNull()?.isStreaming != true) {
                    state
                } else {
                    state.copy(transcript = state.transcript.dropLast(1))
                }
            }
            return
        }

        val parsed = parseAssistantReply(raw)
        // Stripped, as the completed path has always stripped it. Stopping over a call the
        // model was partway through writing left the markup here, and this is the path that
        // writes to storage: the half-written call became the assistant's turn, and every
        // later turn of that conversation was handed an assistant asking for a tool with no
        // result after it. Nothing was ever going to run it, so it is not part of the reply.
        val answer = parsed.answer.withoutToolMarkup()
        // What is left of a reply that was only ever a call is nothing, and a blank turn is
        // worse than no turn, so it goes the same way an empty buffer does.
        if (answer.isBlank() && parsed.reasoning.isNullOrBlank()) {
            _uiState.update { state ->
                if (state.transcript.lastOrNull()?.isStreaming != true) {
                    state
                } else {
                    state.copy(transcript = state.transcript.dropLast(1))
                }
            }
            return
        }
        // Closed off rather than left as the stream had it: thinking that was cut off
        // mid-tag reopens as an unterminated block and swallows the answer after it.
        val canonical = canonicalText(parsed.reasoning, answer)
        updateLastEntry {
            it.copy(
                text = canonical,
                answer = answer,
                reasoning = parsed.reasoning ?: it.reasoning,
                isStreaming = false,
                isReasoningInProgress = false,
            )
        }
        persistReply(canonical, stats = null)
    }

    /**
     * Writes the reply a turn settled on.
     *
     * Once per turn, not once per pass. A turn that uses a tool completes two passes or
     * more, and this used to run at the end of each: the screen showed one answer with the
     * tool folded into it, storage held the interim reply that asked for the tool and then
     * the real one, and reopening the chat produced a conversation the user had never had
     * and then sent it back to the model as history.
     *
     * A stopped reply has no numbers, since they only arrive with a completion, and it is
     * written without them rather than with invented ones.
     */
    /**
     * Writes the reply down, and does not come back until it is written.
     *
     * Awaited rather than launched, and uncancellable while it runs. This used to be
     * `viewModelScope.launch { }`: the turn reported itself finished and `isGenerating`
     * cleared while the row was still on its way to the database, so a process kill in that
     * window left a conversation holding a question with no answer. The question was already
     * durable, because the user's own message is written before the model is asked, so the
     * two halves of a turn had different guarantees and the missing half was always the
     * expensive one.
     *
     * [NonCancellable] because the window is not hypothetical on the path that matters most.
     * Stop cancels the job this is called from, and a reply the user watched being written
     * is exactly what they expect to still be there.
     */
    private suspend fun persistReply(
        text: String,
        stats: GenerationStats?,
        totalMillis: Long? = null,
        engineHistory: List<ChatMessage>? = null,
    ) {
        val id = conversationId ?: return
        val reasoningMs = _uiState.value.transcript.lastOrNull()?.reasoningMs
        // Read here rather than passed in: every call site just finished the same turn
        // `lastTurnSteps` was accumulated for, and threading it through would be the same
        // value with three extra parameters to carry it.
        val steps = lastTurnSteps.toRecords()
        withContext(NonCancellable) {
            reportingFailure {
                writer.reply(
                    id,
                    text,
                    stats,
                    reasoningMs,
                    totalMillis,
                    steps,
                    engineHistory,
                    engineHistoryModel = _uiState.value.modelName,
                )
            }
        }
    }

    /**
     * The engine's record of the conversation through the reply just settled, or null when
     * this turn produced nothing worth extending.
     *
     * Null for a turn the runner kept no record of (stopped, failed), and for one whose
     * prompt carried media: embeddings are never compared against the cache, so a media
     * turn re-evaluates from scratch regardless and a record of it buys nothing. The
     * system message is dropped because it is rebuilt fresh each turn from settings that
     * may legitimately change; while it does not change, the rebuilt one is byte-identical
     * and the cache keeps it anyway.
     */
    private fun engineRecord(turnMessages: List<ChatMessage>?): EngineHistory? {
        val body = turnMessages?.dropWhile { it.role == ChatRole.SYSTEM }
            ?.takeIf { messages -> messages.none { it.files.isNotEmpty() } }
            ?: return null
        val transcript = _uiState.value.transcript
        val reply = transcript.lastOrNull()?.takeIf { it.role == ChatRole.ASSISTANT }
            ?: return null
        // The reply goes back the way the cache holds it: the history text, with thinking
        // in the shape the template will re-render. See assistantHistoryText.
        val history = (reply.history ?: reply.text).takeIf { it.isNotBlank() } ?: return null
        return EngineHistory(
            messages = body + ChatMessage.text(ChatRole.ASSISTANT, history),
            throughCount = transcript.size,
            throughEntryId = reply.id,
        )
    }

    private suspend fun reportingFailure(write: suspend () -> Unit) =
        writeReportingFailure(write) { reportError(it) }

    private fun reportError(why: String) = _uiState.update { it.copy(error = why) }

    /**
     * Folds one pass into the lifetime ledger.
     *
     * Per pass rather than per turn, which is the one part of this that was already right.
     * A turn that searched before answering really did decode twice, and the tab says how
     * much work the phone has done. Kept apart from the reply for that reason: the row
     * carries the answer's own numbers, the ledger carries the totals, and deleting the
     * chat later does not un-count the work.
     */
    private fun recordWork(stats: GenerationStats) {
        val model = _uiState.value.modelName ?: return
        viewModelScope.launch { reportingFailure { writer.work(model, stats) } }
    }

    private fun applyStreamedText(
        raw: String,
        parsed: AssistantReply,
        reasoningEndedAt: Long?,
        startedAt: Long,
    ) = updateLastEntry {
        it.copy(
            text = raw,
            reasoning = parsed.reasoning,
            answer = parsed.answer,
            isReasoningInProgress = parsed.isReasoningInProgress,
            reasoningMs = reasoningEndedAt?.minus(startedAt),
        )
    }

    private fun entry(role: ChatRole, text: String) =
        TranscriptEntry(id = nextEntryId++, role = role, text = text)

    /**
     * The state with one entry told which row it was stored as.
     *
     * By the entry's own id rather than by position or by "the last one": the write is
     * awaited behind a queue, and a reply can have been opened under the question by
     * the time it lands.
     */
    private fun ChatUiState.stored(entryId: Long, row: Long): ChatUiState = copy(
        transcript = transcript.map { if (it.id == entryId) it.copy(storedId = row) else it },
    )

    private fun updateLastEntry(transform: (TranscriptEntry) -> TranscriptEntry) {
        _uiState.update { state ->
            val transcript = state.transcript
            if (transcript.isEmpty()) return@update state
            state.copy(transcript = transcript.dropLast(1) + transform(transcript.last()))
        }
    }

    override fun onCleared() {
        runtime.cancel()
    }
}

/**
 * How often streamed text reaches the screen, in milliseconds.
 *
 * Two frames at 60 Hz. Below this the work per update: re-parse, recompose, re-measure,
 * re-scroll: starts to overlap itself and the transcript visibly judders; above it the
 * text arrives in visible chunks.
 */
/**
 * True when a reply came back reasoning after reasoning was switched off.
 *
 * Proof that this model ignores the switch, which is worth one wrong reply to learn and
 * nothing after that: the finding is kept against the file rather than the session.
 */
/**
 * Everything the loaded model turned out to be able to do, folded on in one place.
 *
 * Four fields interrogating the same nullable, which read as four null checks in the middle
 * of a function whose subject is opening a file. Kept top level rather than made a method so
 * that answering "what can this model do" does not enlarge the view model.
 */
private fun ChatUiState.withCapabilities(
    info: LoadedModelInfo?,
    /** Whether this model has already been caught generating a chain of thought anyway. */
    ignoresThinkingSwitch: Boolean,
) = copy(
    mediaSupport = info?.mediaSupport ?: MediaSupport(),
    outputModality = info?.outputModality ?: OutputModality.TEXT,
    // Two hurdles, and a model has to clear both. The template must render differently when
    // told not to think, and the weights must not have been caught ignoring that already.
    supportsThinking = info?.supportsThinking == true && !ignoresThinkingSwitch,
    supportsReasoningEffort = info?.supportsReasoningEffort == true,
)

private fun ChatUiState.thinkingSwitchWasIgnored(reasoning: String?): Boolean =
    supportsThinking && !preferences.thinking && reasoning != null

/**
 * The document, as the model reads it.
 *
 * Named and fenced, so the model can tell the document from the question about it. Saying
 * when it was cut short matters more than it looks: a model that believes it read the whole
 * thing will answer confidently about an ending that was never there.
 */
private fun StagedDocument.asPrompt(): String = buildString {
    append("Document: ").append(name).append('\n')
    append("\"\"\"\n").append(text.trim()).append('\n')
    if (wasTrimmed) append("[cut short: the rest did not fit]\n")
    append("\"\"\"\n\n")
}

/**
 * Two characters to a token, for the one case that has nothing measured to use instead.
 *
 * Named apart from the document budget's own ratio in [Attaching], which happens to share
 * the value, because they answer opposite questions. That one asks how much of a document
 * fits, where being low means attaching less; this one asks how full the window is, where
 * being low means folding early. Sharing a constant made a change for one silently a change
 * for the other.
 */
/**
 * The day the prompt claims, pinned.
 *
 * Rendered live, midnight silently cost the whole cache: when the date sat in the
 * instructions, every prompt after 00:00 diverged ten tokens in, and a hybrid model —
 * unable to roll back, its snapshot and warm file also holding yesterday's bytes — paid
 * a full foreground re-read. Measured live: 2,197 tokens for the word "hi", the first
 * message after midnight. The date now rides on the conversation's first user turn
 * ([withConversationDay]), which takes the head out of the blast radius entirely, but
 * the pin is still what keeps an open conversation's own bytes from shifting mid-chat:
 * a conversation keeps the day it started with, one stale day being the cheaper wrong,
 * and a fresh chat picks up the new day for free.
 */
internal object PromptDay {
    /**
     * Where today comes from. A test swaps it, so that the process outliving midnight is
     * something the test says has happened rather than something the wall clock might do
     * between one of its assertions and the next.
     */
    @VisibleForTesting
    var today: () -> LocalDate = { LocalDate.now() }

    @Volatile
    var pinned: LocalDate = today()
        private set

    /** Moves the pin to today. Called at a fresh chat's warm; never mid-conversation. */
    fun refresh() {
        pinned = today()
    }

    /**
     * The day as the conversation carries it: the fact, and a commitment to leave it alone.
     *
     * The commitment is load bearing and it has to be in the assistant's mouth. As part of
     * the user's line, "only mention it if asked" broke the date question itself on every
     * model measured (2026-09-01: `read_memory` called for "what is today's date"). As
     * something the model has already said, the same constraint is one it keeps.
     *
     * What it fixes is not the date being recited, which would be cosmetic. The date was
     * the nearest user turn, and a greeting carries nothing to outweigh it, so the reply
     * answered the date instead of the person:
     *
     *     hey → "It seems like you just mentioned today's date. Could you please tell me
     *            more about what you'd like to discuss?"
     *
     * Two changes, because one was not enough and each fixes a different half. The ack
     * carries the constraint, in the model's own mouth. And an ordinary exchange follows,
     * so the date is no longer the last thing said before the question.
     *
     * Measured by `DateStructureProbe` on the phone, sixteen things a person says when
     * they mean nothing in particular, on the pass that writes the reply when tools are
     * on — which is the default, and which no host harness reproduces, because it runs
     * greedily and under [TOOL_PASS_REASONING_BUDGET]:
     *
     * | shape                        | LFM2.5-1.2B | Qwen3-1.7B | date answered |
     * | ---                          | ---:        | ---:       | ---           |
     * | bare ack (before)            | 6/16        | 1/16       | yes           |
     * | scoped ack alone             | 3/16        | 1/16       | yes           |
     * | bare ack, spaced             | 1/16        | 0/16       | yes           |
     * | **both, as shipped**         | **0/16**    | **0/16**   | yes           |
     * | no date at all               | 0/16        | 0/16       | **no**        |
     *
     * The last row is why this cannot simply be deleted: with no exchange, "what is
     * today's date?" drew `run_script` on one model and `web_search` on the other. Two
     * turns of ordinary conversation are a worked example the routing leans on, and the
     * date is a fact the model cannot otherwise have.
     *
     * A host server disagrees with two rows of that table, and the phone wins. On
     * `eval/date_structure_eval.py` the spaced shapes lose the date question outright,
     * because that harness sends the sixteen-tool catalogue from `prompt_dump.json` and a
     * real turn sends whatever the user switched on. The host suite ranks wordings
     * cheaply; it does not decide them.
     *
     * Also measured and refused: the date in the instructions, which answers greetings
     * and loses the date question, because the template renders the whole tool block
     * behind the system message and the fact ends up too far from the question to be
     * recalled; and the date in the assistant's own mouth as an answer already given,
     * which was the worst of everything tried at 86 of 128.
     *
     * A function of its own so the on-device probes send these bytes rather than a copy
     * of them; where they go in the prompt is [withConversationDay]'s to say.
     */
    fun exchange(): List<ChatMessage> = listOf(
        ChatMessage.text(ChatRole.USER, "Today is $pinned."),
        ChatMessage.text(ChatRole.ASSISTANT, DATE_ACK),
        ChatMessage.text(ChatRole.USER, HANDOVER),
        ChatMessage.text(ChatRole.ASSISTANT, HANDOVER_ACK),
    )

    /**
     * What the model says back about the date, and it is exactly these bytes.
     *
     * Named because the wording is a measured result rather than a phrasing: the table in
     * [exchange] is what it costs to change a word of it, and the fold recap's identical
     * opening is deliberately a different constant, since that one acknowledges a summary
     * and has no reason to promise silence about it.
     */
    const val DATE_ACK: String =
        "Understood, I have that. I will not bring it up unless a question depends on it."

    /**
     * The turn that closes the date and opens the floor, and it does the larger half.
     *
     * Nothing here is about the date, which is the point: what makes a greeting come back
     * as a remark about the date is that the date is the nearest thing the user said, and
     * a greeting has nothing in it to outweigh that. Anything ordinary in between takes
     * the adjacency away. Scored on the phone at 6/16 to 1/16 on LFM2.5-1.2B on its own,
     * and to 0/16 with the ack above.
     *
     * Constant text, sitting immediately behind the head, so it is warmed once with the
     * prefix and costs nothing per turn. The whole exchange is about fifteen tokens.
     */
    const val HANDOVER: String = "Ready when you are."

    const val HANDOVER_ACK: String = "Ready."
}

/** Warm-state files kept for models other than the loaded one. */
private const val KEPT_WARM_STORES = 2
private const val PESSIMISTIC_CHARS_PER_TOKEN = 2

private const val STREAM_FRAME_MS = 33L

/** Where the open conversation is remembered across a process the system reclaimed. */
private const val LAST_CONVERSATION = "lastConversation"

/**
 * Runs a write, and reports it rather than dying when it fails.
 *
 * Every write in the chat is launched rather than awaited, so the screen never waits on the
 * disk. That also means nothing is watching: an exception in one of those coroutines has no
 * catch above it and no handler on the scope, so a full disk or a database that would not
 * open took the process with it rather than the write.
 *
 * The cause is logged and the reader is told the consequence. There is nothing they can do
 * about an SQLite error, and the thing they can act on while the conversation is still on
 * screen is that it will not be there later.
 */
private suspend fun writeReportingFailure(write: suspend () -> Unit, report: (String) -> Unit) {
    if (writeOrNull(write) == null) report(STORAGE_FAILED)
}

/**
 * Runs a write and gives back what it produced, or null if it would not go through.
 *
 * The same contract as [writeReportingFailure] for callers that need the value rather than
 * only the outcome, and it exists because `runCatching` is not that contract. `runCatching`
 * catches a cancellation exactly like a failure, so pressing Stop over a write became a
 * write that failed: the edit that was being saved reported that storage was broken, and
 * the branch that was being copied reported the same and left the half-built conversation
 * it had already created sitting in the drawer.
 *
 * Cancellation is passed on. Everything else is logged and answered with null.
 */
internal suspend fun <T> writeOrNull(write: suspend () -> T): T? = try {
    write()
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
    Log.w("OpenWeights", "a chat write did not go through", failure)
    null
}

/**
 * What a write that would not go through is worth saying.
 *
 * Names the consequence rather than the cause, because the cause is a database and the
 * consequence is that this conversation will not be here tomorrow, which is the part
 * somebody can act on while it is still on screen.
 */
private const val STORAGE_FAILED =
    "This chat could not be saved. It is still here now, but it will not be when you come back."

/**
 * Runs a read, and says so rather than dying when it fails.
 *
 * The mirror of [writeReportingFailure], and it was missing, which made the rule half a rule:
 * the same database that will not open fails reads and writes alike, and only one of them was
 * being caught. The worst of them ran in the view model's own `init`, so the process died
 * before there was a screen to put a message on, and with no crash reporter that is a launch
 * loop nobody can tell us about.
 *
 * What was lost differs per read, so the caller says it. A drawer that cannot be filled and a
 * chat that will not reopen are different sentences, and neither of them is "the app closed".
 */
private suspend fun readReportingFailure(
    why: String,
    report: (String) -> Unit,
    read: suspend () -> Unit,
) {
    try {
        read()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
        Log.w("OpenWeights", "a chat read did not come back", failure)
        report(why)
    }
}

/** Said when the drawer cannot be filled, which does not stop the chat on screen working. */
private const val CHATS_UNREADABLE =
    "Your saved chats could not be read. This one still works."

/** Said when one chat will not reopen. The one on screen is untouched, so it stays. */
private const val CHAT_UNREADABLE = "That chat could not be opened."

/** Said when the weights have been deleted out from under the engine holding them. */
private const val MODEL_GONE = "Choose a model in Models:"

/**
 * How long an answer should be.
 *
 * Always sent, with or without tools, because length is the thing that decides what a reply
 * costs here. Asked "Gojo vs Sukuna" this model wrote two thousand nine hundred tokens,
 * seventy-one percent of the window, and took five minutes and thirty-nine seconds over it.
 * None of that was searching: it was one pass with no tools and no calls. It restated the
 * question, numbered seven sections, argued with itself in the open with thinking switched
 * off, and reached "Refine the Argument" without ever having given an answer.
 *
 * A hosted assistant answers that in a paragraph. The difference is not the hardware, it is
 * that nothing here had ever told the model when to stop. On a phone at seven tokens a
 * second, every hundred words it does not write is fourteen seconds the user does not wait.
 *
 * One sentence, because the first attempt at fixing this was six sentences of "do not" and
 * it made things worse: the model started quoting the rules back to itself and deciding
 * which one applied, on the page, with thinking off. Against the same model on a Mac this
 * sentence produced 286 tokens where the uncapped original produced 2,900.
 */
/**
 * The answer style now lives in [AnswerLength], because it became a setting.
 *
 * It used to be a constant here reading "in a few sentences", which capped every answer
 * including the ones that asked for the opposite: the 1.2B refused five paragraphs outright,
 * blaming the tools, and the 2.6B quietly wrote a third of what it would have. The wording
 * that replaced it is Balanced, and the other two are the control that was missing.
 */

/**
 * That the answer may be formatted, which the model does not assume.
 *
 * The app has rendered Markdown for as long as it has rendered replies, and the models were
 * not producing any. Measured on four questions that invite structure, with no tools in the
 * prompt so an empty reply could not be mistaken for an unformatted one:
 *
 * | | tables | headings | bullets | length |
 * | --- | ---: | ---: | ---: | ---: |
 * | 2.6B, without this | 1 of 4 | 0 of 4 | 2 of 4 | 1,279 chars |
 * | 2.6B, with it | **3 of 4** | **4 of 4** | **4 of 4** | **1,698** |
 * | 1.2B, without this | 0 of 4 | 0 of 4 | 0 of 4 | 379 |
 * | 1.2B, with it | 0 of 4 | 0 of 4 | 1 of 4 | 490 |
 *
 * Headings from none to every one is worth the thirty five tokens this costs on every turn.
 * Worded as permission rather than instruction: "use headings and tables" gets headings on a
 * one sentence answer, which is worse than plain text.
 */
/**
 * What a goal's first turn asks for.
 *
 * A plan and nothing else. Plan mode already refuses to run tools and asks when the request
 * is ambiguous; this only says what shape the answer should take, because the steps are
 * parsed back out of it and a paragraph is not a list.
 */
/**
 * The three sentences that turn the same loop into a different kind of work.
 *
 * A goal carries out actions and reports on each; research answers questions and then writes
 * up what it found. Everything else about running unattended on a phone is identical, which
 * is why this is a parameter rather than a second loop.
 */
private data class Brief(
    /** What the foreground notification says while this is running. */
    val notification: String,
    /** Asked once, and parsed back into a plan. */
    val plan: String,
    /** Asked for every step, pointed at that step alone. */
    val step: String,
    /** Asked after the last step, or null for work whose answer is the last step. */
    val finish: String? = null,
    /** Execution mode for each step, or null to preserve the user's chosen mode. */
    val executionMode: AgentMode? = null,
    /** Whether each step must prove a correlated web search and page fetch. */
    val requiresWebEvidence: Boolean = false,
    /**
     * Whether the planning turn is offered ask_user at all.
     *
     * True for a goal, where the action is going to touch the user's own files and a
     * genuine ambiguity is worth a question before anything runs. False for research: the
     * prompt alone saying not to ask was not enough, measured against exactly the failure
     * that motivated it, so the tool is not offered rather than merely discouraged.
     */
    val offersAskDuringPlan: Boolean = true,
    /**
     * What to work through when the planning turn did not produce a plan of its own.
     *
     * Null for a goal: guessing a list of actions to carry out on the user's own files,
     * unread by anyone, is not a safe thing to default to. Research's own fallback plans
     * one step, the question exactly as asked — read-only, and the one thing the model
     * was already asked to do, so there is nothing about it to have guessed.
     */
    val fallbackPlan: (String) -> TaskPlan? = { null },
) {
    companion object {
        val GOAL = Brief(
            notification = "Working on a goal",
            plan = GOAL_PLAN_PREFIX,
            step = GOAL_STEP_PREFIX,
        )

        /**
         * Researching, which is planning questions rather than actions.
         *
         * "Do not answer them yet" is doing real work in the first sentence. Asked to break
         * a question down, a small model answers the whole thing in the planning turn and
         * then has nothing left to research, which is the same failure the goal planner has
         * and needs the same explicit instruction.
         *
         * The second sentence exists because plan mode's only other tool is ask_user, and
         * not recognising the subject of a research request reads to a small model as
         * exactly the kind of thing to ask about — asked to research a name it does not
         * know, it asked the person who is Alpha Romer rather than putting that question on
         * the list, which is the one place in this whole loop research can actually search
         * for an answer. Not knowing the subject is not an ambiguity to resolve before
         * planning; it is usually the plan's first question.
         *
         * The step prompt names the address because a report with no sources is the one
         * output here nobody can check, and a model that is not told to keep the link does
         * not keep it.
         */
        val RESEARCH = Brief(
            notification = "Researching",
            plan = "Break this into a short numbered list of specific questions, five at " +
                "most, that could each be answered by searching the web. Do not answer " +
                "any of them yet. If you do not already know who or what this is about, " +
                "that is not a reason to ask first: searching to find out is the research " +
                "itself, so make it one of the questions instead.",
            step = "Research this one question. Search the web, read the best source you " +
                "find, and report what it says along with the address you found it at. " +
                "Only state facts that are actually in what you read; do not add a date, " +
                "a figure, or a name from memory just because it sounds like it belongs. " +
                "Do not research the other questions.",
            offersAskDuringPlan = false,
            finish = "Now write the findings up as one document in Markdown. Start with a " +
                "heading, then the answer in a few short sections. End with a Sources " +
                "section listing the addresses you used. Do not search again: write only " +
                "from what you found. If something could not be answered, say so rather " +
                "than filling the gap.",
            executionMode = AgentMode.AUTO,
            requiresWebEvidence = true,
            fallbackPlan = { task ->
                TaskPlan(listOf(TaskStep(task.take(TaskPlan.MAX_STEP_CHARS))))
            },
        )
    }
}

private const val GOAL_PLAN_PREFIX: String =
    "Plan this out as a short numbered list of steps, five at most, each one a single " +
        "action you could carry out on this phone. Do not do any of them yet."

/** PLAN produces a goal's plan; it is never a usable mode for carrying that plan out. */
internal fun goalExecutionMode(requested: AgentMode?, previous: AgentMode): AgentMode =
    requested ?: previous.takeUnless { it == AgentMode.PLAN } ?: AgentMode.AUTO

/**
 * What every later turn asks for.
 *
 * Pointed at one step rather than the whole goal, because a small model handed the whole
 * list re-plans instead of working: measured elsewhere in this repository, the model is at
 * its best when the next action is the only thing in front of it.
 */
private const val GOAL_STEP_PREFIX: String =
    "Carry out this one step of the plan and report what happened. Do not do the other " +
        "steps."

/**
 * Replaces the configured tool prompt for a research step. See [ChatViewModel.step].
 *
 * The configured default opens with "you already know the answer to most questions", which
 * is the right bias for ordinary chat and the wrong one here: a research step only exists
 * because the plan already decided the answer was not already known, and telling the model
 * to lean on memory anyway is what let a step answer "I don't have the current information
 * stored" instead of searching for it.
 */
private const val RESEARCH_STEP_TOOL_PROMPT: String =
    "This step exists to search, not to answer from memory: the plan already decided this " +
        "question needed one. Search the web for it and read a real source before " +
        "answering. If the first search does not settle it, change the query and search " +
        "again rather than answering from what you already believe or saying you cannot " +
        "know: one weak search is not evidence the answer is unavailable, only that the " +
        "first query was."

/**
 * Where a goal stops rather than flatten the phone.
 *
 * Fifteen percent, which is the band Android itself starts warning in. Working on its own is
 * the most expensive thing this app does: sustained decode holds several cores at full clock
 * for minutes, and a goal left running is the one case where nobody is watching the battery
 * because nobody is watching the screen.
 */
private const val MIN_BATTERY_PERCENT = 15

/**
 * Consecutive failed steps before a goal gives up.
 *
 * Two, so the common case of a tool call the model can repair once it reads the error gets
 * its retry, and a goal that is simply stuck stops rather than working through the battery.
 */
private const val MAX_STEP_FAILURES = 2

/** What one step of a goal turned out to mean for the loop running it. */
private enum class StepOutcome {
    /** It finished, and the plan moved on. */
    DONE,

    /** It failed in a way worth trying once more. */
    RETRY,

    /** The goal is over, whether finished, halted, or cancelled. */
    STOP,
}

private const val MARKDOWN_STYLE: String =
    "Use Markdown when it makes the answer easier to read: headings for sections, bullets " +
        "for lists, and a table when you are comparing things across the same few fields."

/**
 * The instructions every conversation on this screen starts with, as messages.
 *
 * Split out of [engineMessages] because two callers must produce the same bytes: the turn
 * that answers, and the warm pass that reads this prefix into the engine while nobody is
 * waiting. The engine reuses the work by comparing rendered bytes, so this being one
 * function is the guarantee, not a tidiness.
 */
internal fun ChatUiState.prefixMessages(toolPromptOverride: String? = null): List<ChatMessage> {
    // Deliberately no date here. The instructions are the root of the KV cache and the
    // template renders the whole tool block behind them, so a date in this position went
    // stale at every midnight and cost the warm snapshot, the disk store and a full
    // background re-read of the head, daily. It rides on the conversation's first user
    // turn instead — see [withConversationDay].
    val instructions = listOfNotNull(
        AnswerLength.fromName(preferences.answerLength).instruction,
        MARKDOWN_STYLE,
        preferences.systemPrompt.takeIf { it.isNotBlank() },
        // Plan mode says its piece whether or not any tool is switched on, because it is a
        // mode the user chose and it changes how the model is meant to answer. Gated on
        // tools being available, "/plan" with everything switched off sent no instruction
        // at all and the mode was a silent no-op.
        toolInstruction(
            mode,
            toolPromptOverride ?: preferences.toolPrompt,
            anyTools = toolsAvailable,
        ),
    ).joinToString("\n\n")

    return instructions
        .takeIf { it.isNotBlank() }
        ?.let { listOf(ChatMessage.text(ChatRole.SYSTEM, it)) }
        .orEmpty()
}

/**
 * What actually gets sent to the model: the compaction summary, if any, followed by the
 * turns that were not folded into it.
 *
 * @param toolPromptOverride Replaces [ModelPreferences.toolPrompt] for this one turn. The
 *   configured prompt is tuned for ordinary chat, where reaching for a tool the model
 *   probably does not need is the failure worth avoiding; a research step is the opposite
 *   case; see the override research steps pass.
 */
internal fun ChatUiState.engineMessages(toolPromptOverride: String? = null): List<ChatMessage> {
    val system = prefixMessages(toolPromptOverride)

    // The engine's own record of the conversation, where there is a current one. Replaying
    // it verbatim — decorated questions, tool rounds and all — is what lets the next prompt
    // extend the KV cache instead of diverging at the first decoration the transcript never
    // carried, which a hybrid model pays as a full re-read of everything. Deliberately not
    // put through [asExchange]: these messages are exactly what the template already
    // rendered and accepted, and joining two adjacent tool results would both change the
    // bytes and merge results that carry different call ids. Present under a fold means
    // captured after it — the fold clears the one it invalidated — so a post-fold record,
    // recap and all, keeps extending.
    val record = engineHistory?.takeIf { it.covers(transcript) }
    if (record != null) {
        val tail = transcript.drop(record.throughCount)
            // The streaming placeholder is not part of any prompt.
            .filterNot { it.role == ChatRole.ASSISTANT && it.text.isBlank() }
            .map { it.toChatMessage() }
        // The tail alone gets the neighbour-joining [asExchange] would have done — a
        // question whose reply was stopped before its first token leaves two user turns
        // in a row, which strict templates refuse — and the boundary is joined too. The
        // record itself is never joined: two adjacent tool results carrying different
        // call ids must stay two messages.
        val joined = tail.fold(record.messages.toMutableList()) { kept, message ->
            val previous = kept.last()
            if (previous.role == message.role && previous.role != ChatRole.TOOL) {
                kept[kept.lastIndex] = previous.copy(parts = previous.parts + message.parts)
            } else {
                kept += message
            }
            kept
        }
        // The record's first question already carries its date, byte for byte; only a
        // record that holds no user turn at all leaves the tail's first question bare.
        val prompt = system + joined
        val dated = if (record.messages.any { it.role == ChatRole.USER }) {
            prompt
        } else {
            prompt.withConversationDay()
        }
        return dated.withToolNotes(toolNotes)
    }

    val remaining = compaction
        ?.let { transcript.drop(it.foldedThroughIndex + 1) }
        ?: transcript

    return (system + recap(compaction) + remaining.map { it.toChatMessage() })
        .asExchange().withConversationDay().withToolNotes(toolNotes)
}

/**
 * The folded turns, handed back as a turn rather than as an instruction.
 *
 * The summary used to be appended to the system message, and the reason given was that a
 * second system message beside the first made Gemma 3's template raise: it enforces strict
 * user-then-assistant alternation. That reason is real and it argues against a *system*
 * turn, not against a turn. An ordinary exchange alternates correctly and renders on every
 * template here.
 *
 * The position turned out to matter a great deal more than the wording. Given a summary that
 * contained the answer, and the eight shipped tools, the model was asked seven questions the
 * summary answers:
 *
 * | summary in | answered from it | reached for a tool anyway |
 * | --- | ---: | ---: |
 * | the system message | 4 of 7 | **7 of 7** |
 * | a turn, as here | 6 of 7 | **3 of 7** |
 * | prefixed to the question | 1 of 3 | 2 of 3 |
 *
 * The tool column is the honest one, because a search whose query quotes the fact counts as
 * "answered" on a string match and is not an answer. Seven of seven means that after a fold
 * the model looked for what it had just been told, every time, and on a phone with a shared
 * folder that search comes back empty.
 *
 * The reply is put in the model's own mouth, which is a prompt-construction device and not
 * something it said. That is the same liberty [withToolNotes] takes, and it is what makes the
 * difference: a question with the summary merely prefixed to it scored no better than the
 * system message. What works is the conversation having already acknowledged it.
 */
private fun recap(compaction: Compaction?): List<ChatMessage> {
    val summary = compaction?.summary?.takeIf { it.isNotBlank() } ?: return emptyList()
    return listOf(
        ChatMessage.text(ChatRole.USER, "Earlier in this conversation:\n$summary"),
        ChatMessage.text(ChatRole.ASSISTANT, "Understood, I have that."),
    )
}

/**
 * The day, carried by a small acknowledged exchange at the front of the conversation
 * rather than by the instructions.
 *
 * The model still needs it: it cannot tell that "this year's final" is past its training
 * data if nobody says what year it is, and it answers from memory rather than look — on
 * the routing set the date was most of the difference between eleven right out of twenty
 * four and eighteen. But the instructions are the root of the KV cache and the template
 * renders the whole tool block behind them, so a date anywhere in the head made every
 * warmed byte stale at midnight: snapshot, disk store, and a ~2,200-token background
 * re-read, bought back daily. The first user turn is the first thing the warm never
 * covers, so the head stays byte-stable for as long as the settings do, and a new day
 * costs the dozen tokens of a turn that was being read anyway.
 *
 * As its own acknowledged exchange, not prepended to the question. Prepended, the date
 * reads as part of what the user wants and the model reaches for tools to serve it:
 * measured at temp 0 over the shipped catalogue on LFM2.5-1.2B, "hi" and its kin drew
 * tool calls on 8 of 12 chit-chat cases (hi → read_memory), against 4 of 12 with the
 * date in the instructions and 3 of 12 with no date at all. The same acknowledged-turn
 * device the fold recap uses scores 4 of 12 — the old level — and is the only placement
 * measured that also answers "what is today's date?" without searching for it (the old
 * in-system line failed even that). The conversation having already absorbed the fact
 * is what keeps it from being treated as a request.
 *
 * The pair sits directly after the head, so it is rebuilt from the root like everything
 * behind it and folds carry it naturally. The day itself stays pinned per conversation
 * ([PromptDay]) so an open chat's bytes never shift at midnight. The wording, and what it
 * was measured against, is [PromptDay.exchange]'s.
 */
private fun List<ChatMessage>.withConversationDay(): List<ChatMessage> {
    val at = indexOfFirst { it.role == ChatRole.USER }
    if (at < 0) return this
    return subList(0, at) + PromptDay.exchange() + subList(at, size)
}

/**
 * The notes put in front of the question, inside the message that carries it.
 *
 * Inside a user turn rather than beside one, because the prompt is a strict alternation and a
 * turn of its own would be a second user message in a row: the templates that enforce this
 * refuse to render it, which is the same wall the compaction summary hit before it moved into
 * the instructions.
 *
 * And in the last message rather than the first, which is the part that matters on a phone. The
 * instructions are the root of the KV cache and every token of the conversation sits behind
 * them, so a record that grows with each tool call would invalidate the whole prefix every turn
 * and re-prefill a conversation that had not changed. The final user turn is new anyway.
 *
 * Before the question and not after it. Whatever is nearest the end is what a small model
 * answers, and a page of notes in that position gets summarised back instead of used.
 */
private fun List<ChatMessage>.withToolNotes(notes: ToolNotes): List<ChatMessage> {
    val rendered = notes.render() ?: return this
    val last = lastOrNull()?.takeIf { it.role == ChatRole.USER } ?: return this
    // Rebuilt from the whole message rather than by editing its last part: asExchange joins
    // neighbours of one role, so a user turn can arrive here carrying several pieces of text,
    // and prepending to the last of them would file the notes in the middle of the question.
    // Joined only when there is a question to join to. A message that is nothing but an
    // attachment has no text, and the blank line then trailed off the end of the prompt.
    val question = last.text
    val joined = if (question.isBlank()) rendered else "$rendered\n\n$question"
    return dropLast(1) + last.copy(parts = last.files + MessagePart.Text(joined))
}

/**
 * The prompt as a strict exchange: one system turn, then user and assistant in turn.
 *
 * Several widely used templates require this rather than prefer it, and refuse to render
 * anything at all otherwise. Three things here can break it, and each was reachable: the
 * fold keeps a fixed number of recent entries and nothing makes that boundary land on a
 * question; a stop before the first token drops the empty reply, so the next question
 * follows the last one directly; and the summary used to be sent as its own system turn.
 *
 * Neighbours of the same role are joined rather than dropped, because both halves are
 * things the user said or the model wrote and neither is ours to discard. An answer left
 * at the front with no question before it is dropped, because there is nothing to join it
 * to and its question has already been folded away.
 */
private fun List<ChatMessage>.asExchange(): List<ChatMessage> {
    val system = takeWhile { it.role == ChatRole.SYSTEM }
    val body = drop(system.size).dropWhile { it.role == ChatRole.ASSISTANT }

    // Instructions with nothing to answer are not a prompt. A fold that keeps only answers
    // leaves this, and sending it asks the model to reply to nobody: refused by the
    // templates that check, and answered at random by the ones that do not. Empty is what
    // the caller already treats as nothing to send.
    if (body.isEmpty()) return emptyList()

    return system + body.fold(mutableListOf()) { kept, message ->
        val previous = kept.lastOrNull()
        if (previous != null && previous.role == message.role) {
            kept[kept.lastIndex] = previous.copy(parts = previous.parts + message.parts)
        } else {
            kept += message
        }
        kept
    }
}

/**
 * Why a message cannot be sent right now, or null when it is not worth saying.
 *
 * Generating is not worth saying: the composer shows Stop rather than Send, so reaching it
 * at all means a tap beat the recomposition. The other two are the cold start, where
 * somebody types before the weights are mapped, and those are worth saying because the
 * alternative is a question that simply disappears.
 */
private fun ChatUiState.refusalReason(): String? = when {
    // First, because it is the one that used to fall through to null: a caller refused for
    // this reason was told nothing at all, and the goal loop reported its own halt as "the
    // turn could not be started" with no idea why.
    isGenerating -> "A reply is still being written. Wait for it, or stop it first."
    isLoadingModel -> "The model is still loading. Ask again in a moment."
    isPreparingFirstResponse -> "The model is preparing its first reply. Ask again in a moment."
    modelName == null -> "No model is loaded yet. Choose one in Models."
    outputModality == OutputModality.SPEECH ->
        "This model generates speech, which this build can detect but cannot play yet. " +
            "Choose a text model in Models."
    // Reached only if the composer let a tap through anyway. The Send button is disabled
    // for the same condition, so this is the belt to that pair of braces.
    isCompacting -> "Making room by summarising earlier turns. This takes a few seconds."
    isAttaching -> "The attachment is still being copied in. Send once it appears."
    else -> null
}

/**
 * The state with a reply that came back empty taken out of it, or null if there was none.
 *
 * A turn can complete and leave nothing behind. The pass finished, so the entry was settled
 * rather than dropped, and what stayed on screen was an empty bubble with a metrics row
 * under it: nothing said what had happened, and the empty reply was written to storage as
 * well, so it came back on reopening and was resent as an empty assistant turn.
 *
 * Only ever reached when the model itself came back empty, which a small one does when the
 * template renders something it will not continue. Stop takes the cancellation path and
 * never arrives here.
 */
private fun ChatUiState.droppingEmptyReply(): ChatUiState? {
    val last = transcript.lastOrNull() ?: return null
    val nothingInIt = last.role == ChatRole.ASSISTANT &&
        last.text.isBlank() &&
        last.blocks.isEmpty()
    if (!nothingInIt) return null

    return copy(
        transcript = transcript.dropLast(1),
        error = error ?: "The model returned an empty reply. Ask again, or put it another way.",
    )
}

/**
 * Stored messages as the screen shows them.
 *
 * Its own function because reopening has to rebuild everything the live transcript carries,
 * and one part of that was quietly missing: the marker saying earlier turns were folded
 * away is written when the fold happens and was not restored, so a compacted conversation
 * came back looking as though nothing had been summarised and the gap in it was unexplained.
 *
 * @param foldedThrough index of the last entry a fold covered, or null if there was none.
 */
private fun List<MessageEntity>.toTranscript(
    foldedThrough: Int?,
    stepsByMessage: Map<Long, List<ToolStepEntity>> = emptyMap(),
): List<TranscriptEntry> = mapIndexed { index, message ->
    val parsed = parseAssistantReply(message.text)
    TranscriptEntry(
        id = index.toLong(),
        role = ChatRole.entries.firstOrNull { it.wireName == message.role }
            ?: ChatRole.ASSISTANT,
        text = message.text,
        reasoning = parsed.reasoning,
        answer = parsed.answer,
        tokensPerSecond = message.tokensPerSecond,
        prefillTokensPerSecond = message.prefillTokensPerSecond,
        timeToFirstTokenMs = message.timeToFirstTokenMs,
        generatedTokens = message.generatedTokens,
        reasoningMs = message.reasoningMs,
        attachments = message.attachments.decodeAttachments(),
        totalMillis = message.totalMillis,
        promptTokens = message.promptTokens,
        cachedTokens = message.cachedTokens,
        prefillMs = message.prefillMs,
        decodeMs = message.decodeMs,
        compactionNote = COMPACTION_NOTE.takeIf {
            foldedThrough != null &&
                index == foldedThrough + 1
        },
        blocks = stepsByMessage[message.id].orEmpty().map { TurnBlock.Step(it.toAgentStep()) },
        storedId = message.id,
    )
}

/** A stored tool step, read back as the same shape a live turn produces. */
private fun ToolStepEntity.toAgentStep(): AgentStep = AgentStep.Ran(
    call = ToolCall(id = "", name = toolName, argumentsJson = argumentsJson),
    result = result,
    millis = millis,
    successful = successful,
)

/**
 * What a turn's steps are worth writing down, in the shape [ChatRepository.addMessage] wants.
 *
 * Only [AgentStep.Ran] survives. [AgentStep.Requested] never resolved to anything and
 * [AgentStep.Skipped] taught the model nothing a future turn could use — [ToolNotes.withSteps]
 * already discards both the same way, and a reopened conversation should not remember more
 * about a turn than the turn itself was allowed to.
 */
private fun List<AgentStep>.toRecords(): List<ToolStepRecord> =
    filterIsInstance<AgentStep.Ran>().map {
        ToolStepRecord(
            toolName = it.call.name,
            argumentsJson = it.call.argumentsJson,
            result = it.result,
            successful = it.successful,
            millis = it.millis,
        )
    }

/**
 * What the next prompt will occupy, from what the last one actually did.
 *
 * Wanted only just after a fold and on reopen, where the engine's own reading says nothing
 * useful: the cache was reset, so it reports empty, while the next turn will re-decode the
 * summary and whatever was kept verbatim.
 *
 * It used to be two characters to a token, borrowed from the attachment budget, where that
 * number is right for a reason that does not apply here: an attachment is dense comma-heavy
 * text and being wrong costs part of a document. English prose runs nearer four, so the
 * borrowed ratio read a conversation as twice as full as it was and folded it again sooner
 * than it needed to, each fold costing a summary generation and a cache reset.
 *
 * So it is measured instead of guessed. Every completed pass reports how many tokens the
 * whole conversation occupies, and the conversation's length in characters is known, so the
 * ratio is a fact about this model and this text rather than about English in general. It
 * still errs the safe way: the count includes the template's own tokens while the characters
 * do not, so the ratio comes out low and the estimate comes out high.
 *
 * [PESSIMISTIC_CHARS_PER_TOKEN] is only for the first fold of a conversation reopened before
 * anything has been generated, where there is nothing to measure yet.
 */
internal fun ChatUiState.estimatedPromptTokens(): Int {
    val chars = engineMessages().sumOf { it.text.length }
    val ratio = charsPerToken ?: return chars / PESSIMISTIC_CHARS_PER_TOKEN
    return (chars / ratio).toInt()
}

/** Characters to a token as this model was last seen to tokenise, or null before then. */
internal fun GenerationStats.charsPerToken(chars: Int): Float? =
    (chars.toFloat() / contextUsed).takeIf { contextUsed > 0 && it.isFinite() && it > 0f }

/**
 * This conversation's running input/output token counts and cache hit rate, for the status
 * line above the composer.
 *
 * Summed from the transcript itself rather than kept as separate running counters: every
 * place this conversation's transcript is cleared or replaced — a new chat, switching
 * conversations, an edit that drops later turns, reopening one from storage — already has to
 * get that right for the messages themselves, and a sum over what is actually on screen can
 * never drift from it. A separate counter would need its own reset at every one of those
 * sites and would eventually miss one.
 */
internal fun ChatUiState.sessionTokens(): SessionTokens? {
    val measured = transcript.filter { it.role == ChatRole.ASSISTANT && it.promptTokens != null }
    if (measured.isEmpty()) return null
    val input = measured.sumOf { it.promptTokens ?: 0 }
    val output = measured.sumOf { it.generatedTokens ?: 0 }
    val cached = measured.sumOf { it.cachedTokens ?: 0 }
    return SessionTokens(
        inputTokens = input,
        outputTokens = output,
        // input is 0 only in the unreachable case of every measured turn being a full cache
        // hit on an empty prompt; guarded rather than assumed away.
        cacheHitRate = if (input > 0) cached.toDouble() / input else 0.0,
    )
}

/** See [ChatUiState.sessionTokens]. */
internal data class SessionTokens(
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheHitRate: Double,
)

/**
 * A transcript entry as the engine sees it.
 *
 * Attachments come first: a question about a picture reads better after the picture, and
 * models are trained on that order.
 */
private fun TranscriptEntry.toChatMessage(): ChatMessage = ChatMessage(
    role = role,
    // [history] where there is one, because [text] has been through the parser and the
    // engine's cache holds what came out of the model. See assistantHistoryText.
    parts = attachments + MessagePart.Text(history ?: text),
)

/**
 * The one string that stands for a reply everywhere.
 *
 * It is what the entry holds, what is written to storage, and what is resent as history,
 * so it has to read back through [parseAssistantReply] as the same reply the user saw.
 *
 * Rebuilt from the split parts rather than kept as the raw stream, because for the formats
 * llama.cpp recognises the raw stream still holds tool invocation syntax that was lifted
 * out of what is displayed. Storing it would make a chat change what it says the moment it
 * is reopened. Where nothing was lifted out this returns the stream unchanged.
 */
private fun canonicalText(reasoning: String?, answer: String): String =
    if (reasoning.isNullOrEmpty()) answer else "<think>$reasoning</think>$answer"

/**
 * Warns when a chat carried over to a new model contains media that model cannot read.
 *
 * The transcript is resent as history on the next reply, and the engine drops attachments
 * a model has no projector for, so those turns arrive as text with the picture missing.
 */
private fun List<TranscriptEntry>.unreadableWarning(support: MediaSupport): String? {
    val dropped = flatMap { it.attachments }.filterNot { support.accepts(it.kind) }
    if (dropped.isEmpty()) return null
    return "This chat has ${dropped.size} attachment(s) the new model cannot read. Replies " +
        "will be based on the text alone."
}

/**
 * What to tell the model about its tools.
 *
 * Comes from settings, not from here. An app that quietly prepends instructions to every
 * conversation is an app whose behaviour its user cannot account for, so the text is a
 * preference they can read, edit, or empty.
 *
 * Plan mode is the one exception, and it is one the user selected by typing `/plan`: the
 * instruction is the mode.
 */
internal fun toolInstruction(mode: AgentMode, configured: String, anyTools: Boolean): String? =
    when (mode) {
        // Two wordings, because the first one is a lie when nothing is switched on, and a
        // model told it has tools it does not have writes a plan around using them.
        // No longer says "do not call them", because there is now nothing in the prompt to
        // call: plan mode is handed the machinery and nothing else. See TurnRunner.
        AgentMode.PLAN -> if (anyTools) {
            "Do not act on anything yet. Say what you would do and why, as short steps. If " +
                "the request could mean more than one thing, or a detail you would need was " +
                "never given, ask before planning."
        } else {
            "Do not act on anything yet. Say what you would do and why, as short steps."
        }

        // Ask really does wait for the user, so the model narrating "would you like me to
        // do that?" before it calls is not wrong there — the app's own approval prompt
        // says the same thing a moment later. Auto and Yolo do not wait, and the model has
        // no other way to know that: the difference between the three modes is enforced
        // entirely in Kotlin, after a call is emitted, so a model left with only the
        // configured policy has nothing telling it this turn skips the question it was
        // trained to ask by default. Live report: asked something that genuinely needed a
        // search, LFM2.5-1.2B narrated a plan and asked permission instead of calling,
        // in Auto, where nothing was ever going to ask it to.
        AgentMode.ASK ->
            configured.takeIf { it.isNotBlank() && anyTools }

        AgentMode.AUTO, AgentMode.YOLO ->
            configured.takeIf { it.isNotBlank() && anyTools }?.let {
                "$it You do not need to ask before calling a tool here. Call it directly " +
                    "instead of describing the plan and waiting."
            }
    }

/** A short human label for an attachment, used where there is no text to go on. */
internal fun MessagePart.File.describe(): String = name ?: when (kind) {
    MediaKind.IMAGE -> "Image"
    MediaKind.AUDIO -> "Audio"
    MediaKind.VIDEO -> "Video"
    MediaKind.OTHER -> "File"
}

/** Turns engine failures into something a person can act on. */
internal fun Throwable.userMessage(): String = when {
    // What llama.cpp says when the KV cache has no slot left for the batch. The words it
    // uses name an internal return code, and the thing to do about it is not "decode"
    // anything, so it is translated rather than passed through.
    message?.contains(NO_KV_SLOT) == true ->
        "This turn outgrew the context window, usually because a page it read was long. " +
            "The conversation is still here. Ask again, or raise the context length in " +
            "model settings."

    else -> message ?: "Generation failed (${this::class.simpleName})."
}

private const val NO_KV_SLOT = "failed to decode prompt"

/** Some stop reasons need surfacing; a normal end of turn is not. */
private fun StopReason.warning(): String? = when (this) {
    StopReason.CONTEXT_FULL ->
        "The context window is full. Start a new chat, or raise the context length in " +
            "model settings."

    StopReason.END_OF_TURN, StopReason.MAX_TOKENS, StopReason.CANCELLED, StopReason.ERROR -> null
}

/**
 * How often the device's thermal level is re-read while a reply is being written.
 *
 * Often enough to catch a phone heating up mid-answer, rarely enough that the reading
 * itself is not part of the load it is measuring.
 */
private const val THERMAL_SAMPLE_MS = 4_000L
