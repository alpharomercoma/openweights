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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.alpharomercoma.openweights.core.designsystem.component.Metric
import io.github.alpharomercoma.openweights.core.designsystem.theme.OpenWeightsTheme
import io.github.alpharomercoma.openweights.core.designsystem.theme.Radius
import io.github.alpharomercoma.openweights.core.tools.AgentMode

/**
 * Does what the command says, wherever it was typed or tapped.
 *
 * Here rather than in the screen because there are two callers now, and the screen having
 * one of them and the composer the other is how typing a command came to mean something
 * different from choosing it.
 */
fun SlashCommand.run(
    onNewChat: () -> Unit,
    onCompact: () -> Unit,
    onRegenerate: () -> Unit,
    onMode: (AgentMode) -> Unit,
    onGoal: (String) -> Unit = {},
    onResearch: (String) -> Unit = {},
    argument: String = "",
) = when (this) {
    SlashCommand.GOAL -> onGoal(argument)
    SlashCommand.DEEP_RESEARCH -> onResearch(argument)
    SlashCommand.NEW_CHAT -> onNewChat()
    SlashCommand.COMPACT -> onCompact()
    SlashCommand.REGENERATE -> onRegenerate()
    SlashCommand.PLAN -> onMode(AgentMode.PLAN)
    SlashCommand.AUTO -> onMode(AgentMode.AUTO)
    SlashCommand.ASK -> onMode(AgentMode.ASK)
    SlashCommand.YOLO -> onMode(AgentMode.YOLO)
}

/**
 * Something the user can do to the conversation rather than say to the model.
 *
 * Typing `/` is how developer tools have taught people to find these, and it beats a menu:
 * the list filters as you type and the names are the documentation.
 */
enum class SlashCommand(val trigger: String, val description: String) {
    NEW_CHAT("/new", "Start a fresh conversation"),
    COMPACT("/compact", "Summarize earlier turns to free up context now"),
    REGENERATE("/retry", "Ask the model for a different answer"),

    // The three modes, typed the way Claude Code and Codex do it, because that is the
    // vocabulary anyone reaching for a mode already has.
    PLAN("/plan", "Say what it would do, run no tools"),
    AUTO("/auto", "Run tools without asking. The default"),
    ASK("/ask", "Approve each tool before it runs"),

    // Last, and spelled out rather than softened. Auto already runs almost everything; this
    // waives the two checks it keeps, so the description has to say which two rather than
    // read as a faster Auto.
    YOLO("/yolo", "Run everything, including sending files and pages out. No prompts"),

    /**
     * The two commands that take an argument, which is why they are last and why [typed]
     * alone cannot find them: everything else here is a whole message, and these are a
     * prefix followed by the task.
     */
    GOAL("/goal", "Work through a task on its own, and stop when it is done"),

    /**
     * A goal whose answer is a document rather than a last step.
     *
     * Separate from `/goal` because the difference is not the loop, it is what the model is
     * asked for: questions instead of actions, a search on every step, and a closing turn
     * that writes the findings up with the addresses it used. See `Brief` in the view model.
     */
    DEEP_RESEARCH("/deep-research", "Research a question and write up what it finds"),
    ;

    /** True for a command whose message continues past the trigger. */
    val takesArgument: Boolean get() = this == GOAL || this == DEEP_RESEARCH

    companion object {
        /**
         * Commands matching what has been typed, or null when the draft is not a command.
         *
         * A draft only counts as a command while it is a single `/`-prefixed word, once
         * there is a space, the user is writing a message that happens to start with a
         * slash, and hijacking their input would be wrong.
         */
        fun match(draft: String): List<SlashCommand>? {
            if (!draft.startsWith("/") || draft.contains(' ')) return null
            return entries.filter { it.trigger.startsWith(draft, ignoreCase = true) }
        }

        /**
         * The command a finished message is, if it is one at all.
         *
         * The palette is how these are found, and it was also the only way to run them: a
         * command typed out and sent went to the model as text, which duly answered "/plan"
         * as though it were a question. Anyone who already knows the word types it.
         *
         * Exact rather than prefixed, which is the difference between this and [match].
         * Half a command is something still being typed, and a sentence that opens with one
         * is a sentence; either becoming an action would be worse than having no commands.
         */
        fun typed(message: String): SlashCommand? {
            val trimmed = message.trim()
            entries.firstOrNull { it.trigger.equals(trimmed, ignoreCase = true) }
                ?.let { return it }
            // A command with an argument is the trigger, a space, and the rest. Checked
            // after the exact match so "/goal" on its own is still the command rather than
            // a command with an empty task.
            return entries.firstOrNull {
                it.takesArgument && trimmed.startsWith("${it.trigger} ", ignoreCase = true)
            }
        }

        /**
         * The task after a command that takes one, or empty.
         *
         * Split here rather than at the call site so that the one place which knows a
         * command has an argument is the same place that knows how to find it.
         */
        fun argument(message: String): String {
            val trimmed = message.trim()
            val command = entries.firstOrNull {
                it.takesArgument && trimmed.startsWith("${it.trigger} ", ignoreCase = true)
            } ?: return ""
            return trimmed.removeRange(0, command.trigger.length).trim()
        }

        /**
         * What a finished message actually is: a command, an attempt at one that missed, or
         * text with no special meaning.
         *
         * [typed] alone leaves a near miss silent — "/deep research" with a space where the
         * trigger has a hyphen reaches the model as a question instead of running anything,
         * and nothing on screen says a command was even attempted. This is the one place that
         * decides whether a leading slash was reaching for the six-word set of things this app
         * recognises, so a caller can say so before sending it as prose.
         */
        fun parse(message: String): CommandParseResult {
            typed(message)?.let { return CommandParseResult.Valid(it, argument(message)) }
            val trimmed = message.trim()
            if (!trimmed.startsWith("/")) return CommandParseResult.OrdinaryMessage
            // Hyphen and space are the one difference between a working trigger and a typo of
            // it ("/deep-research" against "/deep research"), so both are folded away before
            // comparing: two strings that agree once whitespace and hyphens are gone are almost
            // certainly the same word typed two ways, not two different sentences.
            val token = trimmed.substringBefore(' ')
            val normalizedToken = token.normalizedForSuggestion()
            val suggestions = entries.filter { command ->
                val normalizedTrigger = command.trigger.normalizedForSuggestion()
                normalizedTrigger == trimmed.normalizedForSuggestion() ||
                    normalizedTrigger.startsWith(normalizedToken) ||
                    normalizedToken.startsWith(normalizedTrigger)
            }
            return CommandParseResult.Unknown(token, suggestions)
        }

        private fun String.normalizedForSuggestion(): String =
            lowercase().filterNot { it.isWhitespace() || it == '-' }
    }
}

/**
 * What [SlashCommand.parse] decided a finished message is.
 *
 * A type rather than a nullable command, because "not a command" and "an attempt at a command
 * that did not match anything" are different facts and used to collapse into the same silent
 * send.
 */
sealed interface CommandParseResult {
    /** A command, recognised exactly or by its trigger-plus-argument prefix. */
    data class Valid(val command: SlashCommand, val argument: String) : CommandParseResult

    /** Opens with `/` but matches nothing. [suggestions] is often one command, often none. */
    data class Unknown(val token: String, val suggestions: List<SlashCommand>) : CommandParseResult

    /** No leading slash, or a slash followed by ordinary words: a message like any other. */
    data object OrdinaryMessage : CommandParseResult
}

@Composable
fun SlashCommandPalette(
    commands: List<SlashCommand>,
    onSelect: (SlashCommand) -> Unit,
    /**
     * Whether a row here would do anything if tapped.
     *
     * The composer disables Send while a reply is in flight or a goal is running, and the
     * palette used to keep answering anyway: `/new` or `/compact` mid-goal reached the view
     * model exactly like tapping Send would have, through a control that looked idle because
     * everything beside it was greyed out.
     */
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    if (commands.isEmpty()) return

    // Capped, and this is the whole bug it fixes.
    //
    // The palette is a lazy list in a column above the composer, and with no ceiling it
    // took every pixel it wanted: six commands with their descriptions come to more than a
    // phone is tall, so typing "/" pushed the message box off the bottom of the screen. The
    // commands were there and the thing you were typing into was not, which is why this
    // read as the commands disappearing.
    //
    // Four rows or so, then it scrolls. The list is short, the first match is the one
    // wanted almost always, and a palette taller than this is a menu.
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .heightIn(max = PALETTE_MAX)
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        items(commands, key = { it.trigger }) { command ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { onSelect(command) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Metric(command.trigger)
                    Text(
                        text = command.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Tall enough for four commands, short enough to leave the composer where it was. */
private val PALETTE_MAX = 260.dp

@Preview(showBackground = true, backgroundColor = 0xFF0D0E10)
@Composable
private fun SlashCommandPalettePreview() {
    OpenWeightsTheme(dynamicColor = false) {
        SlashCommandPalette(commands = SlashCommand.entries, onSelect = {})
    }
}
