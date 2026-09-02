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

package io.github.alpharomercoma.openweights.core.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One thing a tool returned, kept past the turn that ran it.
 *
 * @param untrusted whether a stranger wrote this text, which is a property of the tool that
 *   fetched it rather than of the text. It travels with the note because the note outlives the
 *   turn, and the guard that reads it has to outlive the turn by exactly as much.
 * @param private whether this text is the user's own, by the same argument.
 */
data class ToolNote(
    val call: String,
    val result: String,
    val untrusted: Boolean = false,
    val private: Boolean = false,
)

/**
 * What the tools have found so far in this chat, in the tools' own words.
 *
 * A tool result lives inside the turn that ran it and no longer: what is stored, and what is
 * sent back next time, is the assistant's prose. That is fine for a large model and is not fine
 * here. A 1.2B model summarising a page of search results drops a name, or invents one, and
 * from the next turn onwards that summary is the only surviving record of what the tool said.
 * Ask who the second author was and the answer is a fabrication delivered in the same voice as
 * a fact, with nothing left in the conversation that could contradict it.
 *
 * So the loop keeps its own record. Deterministic, written from the result the tool returned
 * rather than from anything the model said about it, and short: the head of each result, which
 * is where a search puts its hits and a page puts its title and opening.
 *
 * It is not a cache and nothing reads from it but the prompt. A tool asked for again runs
 * again, because a page can change and the user is entitled to the current one.
 */
data class ToolNotes(
    val notes: List<ToolNote> = emptyList(),
    /**
     * Whether a stranger's text has entered the prompt since the last fold, whatever the
     * budget has trimmed since.
     *
     * The notes are a budget, and a budget forgets: a page fetched three calls ago drops
     * out of them, and with it went the flag that asks before anything leaves the device.
     * The engine's record does not forget; it replays that page verbatim into every prompt
     * until a fold rewrites the conversation. So the suspicion is kept here, apart from the
     * notes, for exactly as long as the text is: set when a tool that returns untrusted
     * text runs, cleared by [folded].
     */
    val readUntrusted: Boolean = false,
    /** The same for the user's own data. See [readUntrusted]. */
    val readPrivate: Boolean = false,
) {
    /**
     * The record with this turn's tools added, oldest dropped to stay inside the budget.
     *
     * Only [AgentStep.Ran] contributes. A call that was declined or refused taught nobody
     * anything, and saying so again next turn spends the budget on the absence of a fact.
     *
     * A repeat of a call already here replaces it rather than joining it. The arguments are the
     * identity, so this is the same claim about the same call made later, and two copies of it
     * would push something else out.
     *
     * The two flags are set on having run at all, not on having succeeded, which is the rule
     * [AgentRunner] applies within a turn and for the same reason: a failed call can still
     * put the text in the window. `run_script` reads the files a program names before running
     * it, and a program that then throws answers with the exception message, which can carry
     * what it had already read. That message is in the engine's record whether or not a note
     * is kept of it, so the flag that asks before anything leaves the device has to be set
     * whether or not a note is kept. Gated on success, the taint ended with the turn while
     * the text did not, and the next turn's web_search could carry it off in Auto unasked.
     */
    fun withSteps(steps: List<AgentStep>, source: (String) -> Tool?): ToolNotes {
        val ran = steps.filterIsInstance<AgentStep.Ran>()
        if (ran.isEmpty()) return this
        val tools = ran.map { source(it.call.name) }
        // associateBy keeps the last on a collision, which is what a batch holding the same
        // call twice should leave behind: one note, the newer of the two.
        val fresh = ran.zip(tools)
            .filter { (step, _) -> step.successful }
            .map { (step, tool) -> step.asNote(tool) }
            .associateBy { it.call }
            .values
            .toList()
        val replaced = fresh.map { it.call }.toSet()
        return ToolNotes(
            notes = (notes.filterNot { it.call in replaced } + fresh).trimmedToBudget(),
            readUntrusted = readUntrusted || tools.any { it?.returnsUntrustedText == true },
            readPrivate = readPrivate || tools.any { it?.readsPrivateData == true },
        )
    }

    /**
     * The record after a fold.
     *
     * The fold rewrote the prompt from the root: what the model reads of the old turns is
     * now its own summary, and the verbatim page that justified the suspicion is gone from
     * the window. The notes themselves stay, and any that still carry a stranger's text
     * still count; only the memory of text the notes no longer hold is released.
     */
    fun folded(): ToolNotes = copy(readUntrusted = false, readPrivate = false)

    /**
     * The notes as the model reads them, or null when there is nothing to say.
     *
     * Introduced as a record of what the tools returned, because this rides inside the user's
     * own message and a model this size will otherwise read it as something the user typed and
     * answer it instead of the question.
     *
     * The trailer exists because the notes sit directly above the question, which is the
     * position [pinning] and [withToolNotes] both use because it is where a small model attends
     * most. That worked against a fresh, unrelated question: three "who is X" turns in a row
     * left the calls and results for the first two people sitting right above a fourth name the
     * notes said nothing about, and the model answered from the shape in front of it — a list of
     * people it had just looked up — rather than noticing none of them were this one. It reused
     * the second person's URL and recapped the first two, and never asked about the one it was
     * asked. So the heading now says in words what the position was implying on its own: these
     * notes answer *earlier* questions, and a name they do not cover is a new question, not a
     * a summary of the old ones.
     */
    fun render(): String? {
        if (notes.isEmpty()) return null
        return notes.joinToString(
            separator = "\n",
            prefix = "$HEADING\n",
            postfix = "\n$TRAILER",
        ) { it.line() }
    }

    /**
     * The newest notes that fit, in the order they happened.
     *
     * From the end, because the question being asked now is nearly always about the turn just
     * gone. Reversed twice rather than sorted, so what survives still reads forwards.
     *
     * Measured on what is rendered, heading and bullets included, rather than on the two fields
     * a note is made of. The first version counted the fields, so every run quietly exceeded the
     * budget it documented by the heading plus five characters a line.
     *
     * The newest is kept whatever it costs. Testing the budget before keeping anything meant a
     * single oversized note answered false immediately and threw away the entire record, which
     * is the one outcome worse than being over budget.
     */
    private fun List<ToolNote>.trimmedToBudget(): List<ToolNote> {
        if (isEmpty()) return this
        // The trailer is not one of the lines being trimmed, but render() always adds it, so
        // the budget has to leave room for it too or the same overrun this comment already
        // describes for the heading comes back for the other fixed piece of the render.
        var spent = HEADING.length + 1 + TRAILER.length
        return asReversed()
            .filterIndexed { index, note ->
                spent += note.line().length + 1
                index == 0 || spent <= TOTAL_CHARS
            }
            .asReversed()
    }

    /**
     * Whether a stranger's words are in the prompt because of these notes.
     *
     * The runner treats untrusted text as a property of the turn that read it, on the stated
     * grounds that "a file read an hour ago is not in the window any more". These notes are
     * what makes that false: the text is in the window again, in the question, so the turn it
     * is handed to has to start suspicious or the guard is one the notes walked around.
     */
    val carriesUntrustedText: Boolean get() = readUntrusted || notes.any { it.untrusted }

    /** And the same for the user's own text. See [carriesUntrustedText]. */
    val carriesPrivateData: Boolean get() = readPrivate || notes.any { it.private }

    companion object {
        /**
         * How much of one result is worth carrying.
         *
         * The head rather than a summary, because summarising is the step that loses the name,
         * and because a search result list and a page both put the answer near the top.
         */
        const val PER_NOTE_CHARS = 400

        /**
         * The whole record, in characters.
         *
         * About four hundred tokens, or a tenth of the four thousand at which a conversation is
         * folded. It is charged on every turn after the first tool call, so it has to be small
         * enough to be worth what it displaces, and this buys roughly the last four calls.
         */
        const val TOTAL_CHARS = 1_600

        private const val HEADING =
            "Notes from tools already used in this chat. They are a record of what the tools " +
                "returned, not part of the question:"

        /**
         * What follows the last note, spelling out what the heading only implied.
         *
         * The notes are evidence about *earlier* questions, so a small model reads them as the
         * most recent thing in view and, faced with a name none of them mention, answers from
         * the shape in front of it — a list of people already looked up — instead of noticing
         * the gap. This says so directly: a subject the notes do not cover is a new question,
         * not a summary of the old ones.
         */
        private const val TRAILER =
            "These notes are about earlier questions in this chat, not the one being asked now. " +
                "If the current question names someone or something not covered above, that is a " +
                "new subject: answer or search for it on its own, and do not reuse a call or an " +
                "answer from these notes for it."
    }
}

/**
 * One note as the model reads it, and so the thing its budget has to be measured on.
 *
 * The result is quoted. It is a stranger's text on a line whose shape is `- call: result`, and
 * unquoted it can end with a second colon and a second bullet and pass for another entry: a
 * record of a call nobody made, saying something nobody returned. Quoting does not make that
 * impossible, it makes it visible, which is as far as a line of text can go.
 */
internal fun ToolNote.line(): String = "- $call: \"$result\""

/** One run, reduced to what would still be worth having next turn. */
private fun AgentStep.Ran.asNote(tool: Tool?): ToolNote = ToolNote(
    // Both halves capped. Only the result used to be, and a call carrying twenty arguments
    // could exceed the whole budget on its own, at which point the trim kept nothing at all
    // and one malformed call erased the record.
    call = "${call.name}(${call.argumentsJson.readableArguments()})".clippedTo(
        ToolNotes.PER_NOTE_CHARS,
    ),
    result = result.oneLine().clippedTo(ToolNotes.PER_NOTE_CHARS),
    untrusted = tool?.returnsUntrustedText == true,
    private = tool?.readsPrivateData == true,
)

/**
 * The arguments as something a person could read, and so as something a small model can match
 * against the question in front of it.
 *
 * Values rather than the JSON they arrived in: `web_search(kv cache)` says what was asked, and
 * `web_search({"query":"kv cache"})` says the same thing with the punctuation of a format the
 * model is about to be asked not to imitate. Anything that will not parse is carried as it was
 * written, because a model this size produces that often enough that dropping it would leave
 * the note unattributable.
 */
private fun String.readableArguments(): String {
    val parsed = runCatching { Json.parseToJsonElement(this) as? JsonObject }.getOrNull()
        ?: return oneLine().clippedTo(PER_ARGUMENT_CHARS)
    return parsed.entries
        .mapNotNull { (name, value) ->
            // A list or an object shown as the JSON it is, rather than dropped. Dropping it
            // made two different calls render identically, and identical is what decides that
            // one note replaces another: a search of one folder then erased the search of the
            // other, and the record said the second had never happened.
            val shown = (value as? JsonPrimitive)?.content ?: value.toString()
            shown.oneLine()
                .takeIf { it.isNotBlank() }
                ?.let { "$name=${it.clippedTo(PER_ARGUMENT_CHARS)}" }
        }
        .joinToString(", ")
}

/** Collapsed to a single line, because a note is a line and a result is not. */
private fun String.oneLine(): String = replace(WHITESPACE, " ").trim()

/**
 * At most [limit] characters, ending on a whole word and saying that it stopped.
 *
 * take() alone cuts wherever the count lands, which is regularly inside a word and sometimes
 * inside a number: a result reading "10000 members" became "10", which is not a shorter fact
 * but a different and false one. Falling back to the hard cut when there is no space to break
 * at, because a single unbroken token is still better than nothing.
 */
private fun String.clippedTo(limit: Int): String {
    if (length <= limit) return this
    val cut = take(limit)
    val whole = cut.substringBeforeLast(delimiter = ' ', missingDelimiterValue = cut)
    return "${whole.trimEnd()}$CUT_SHORT"
}

/** Said rather than implied, so a truncated result does not read as a complete one. */
private const val CUT_SHORT = "..."

private val WHITESPACE = Regex("""\s+""")

private const val PER_ARGUMENT_CHARS = 60
