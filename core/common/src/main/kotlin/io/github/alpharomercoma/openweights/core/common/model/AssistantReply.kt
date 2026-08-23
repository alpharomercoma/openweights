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

package io.github.alpharomercoma.openweights.core.common.model

/**
 * A model reply split into the part meant for the reader and the part the model was
 * talking to itself.
 *
 * @param reasoning the chain of thought, or null if the model emitted none.
 * @param answer everything outside the reasoning block.
 * @param isReasoningInProgress true while a reasoning block has opened but not closed, so
 *   the UI can say "thinking" rather than showing an empty answer.
 */
data class AssistantReply(
    val reasoning: String?,
    val answer: String,
    val isReasoningInProgress: Boolean,
)

/**
 * Splits raw model output into reasoning and answer.
 *
 * Reasoning models such as LFM2.5, Qwen3, and DeepSeek-R1 wrap their chain of thought in
 * `<think>` tags. Some chat templates pre-fill the opening tag, so output can arrive with a
 * closing tag and no opening one; that case is treated as "everything up to `</think>` was
 * reasoning", which is what those templates mean.
 *
 * This runs on every streamed token, so it stays a single scan with no regex.
 */
fun parseAssistantReply(raw: String): AssistantReply {
    val openIndex = raw.indexOf(OPEN_TAG)
    val firstClose = raw.indexOf(CLOSE_TAG)

    // No reasoning markers at all: the whole thing is the answer.
    if (openIndex < 0 && firstClose < 0) {
        return AssistantReply(reasoning = null, answer = raw, isReasoningInProgress = false)
    }

    // A close before any open is the template having opened the block for the model, so the
    // stream begins mid-thought. Whatever comes after is the answer, tags and all: a model
    // asked about thinking tags writes one, and the block it belongs to has already ended.
    //
    // The condition used to be openIndex < 0, which missed the case where an opening tag
    // appears later in the answer. Both indexes were then valid and crossed, and the last
    // branch here computed substring(43, 11) and threw. Reached on every streamed token and
    // again on reload, where nothing catches it.
    if (firstClose >= 0 && (openIndex < 0 || firstClose < openIndex)) {
        return AssistantReply(
            reasoning = raw.take(firstClose).trim(),
            answer = raw.substring(firstClose + CLOSE_TAG.length).removePrefix("\n"),
            isReasoningInProgress = false,
        )
    }

    val prelude = raw.take(openIndex)
    // Matched against this block's opening tag rather than the first one anywhere, so a
    // closing tag written inside the answer cannot be mistaken for this block's end.
    val closeIndex = raw.indexOf(CLOSE_TAG, openIndex + OPEN_TAG.length)

    // Opening tag still unclosed: everything after it is reasoning so far.
    if (closeIndex < 0) {
        return AssistantReply(
            reasoning = raw.substring(openIndex + OPEN_TAG.length).trim(),
            answer = prelude,
            isReasoningInProgress = true,
        )
    }

    return AssistantReply(
        reasoning = raw.substring(openIndex + OPEN_TAG.length, closeIndex).trim(),
        answer = (prelude + raw.substring(closeIndex + CLOSE_TAG.length)).removePrefix("\n"),
        isReasoningInProgress = false,
    )
}

/**
 * The reply exactly as the model wrote it, ready to be sent back as history.
 *
 * Not the same string as the one shown or stored, and the difference is worth about nine
 * seconds a turn.
 *
 * The engine reuses whatever prefix of the prompt is already in its KV cache, which for a
 * follow-up should be everything except the new question. That only works if re-rendering
 * the conversation reproduces the tokens that were actually decoded. What is shown and
 * stored does not: [parseAssistantReply] trims the thinking and drops a leading newline
 * from the answer for the sake of the screen, and the answer has tool syntax lifted out of
 * it so that reopening a chat does not change what it says.
 *
 * Every one of those is right for its purpose and wrong for this one. Measured on an
 * SM8650 with LFM2.5 2.6B, a single character of difference at the head of the assistant
 * turn cost the whole cache: the prefix matched 1,158 of 1,204 tokens, the rollback to
 * 1,158 was refused because a hybrid model cannot erase part of its recurrent state, and
 * the engine started over. The second turn re-read 1,220 tokens and took 9.6 seconds to do
 * it, against about a tenth of a second for the sixteen tokens that were actually new.
 *
 * So history goes back untouched. The one thing added is the opening `<think>`, and only
 * when the model's own template pre-filled it: the tag was in the prompt rather than in the
 * output, so the output arrives with a closing tag and no opening one, and putting it back
 * is what makes the string equal to the tokens in the cache.
 */
fun assistantHistoryText(raw: String): String {
    val open = raw.indexOf(OPEN_TAG)
    val close = raw.indexOf(CLOSE_TAG)
    val templateOpenedIt = close >= 0 && (open < 0 || close < open)
    return if (templateOpenedIt) OPEN_TAG + raw else raw
}

private const val OPEN_TAG = "<think>"
private const val CLOSE_TAG = "</think>"

/**
 * The text with any recognised tool invocation taken out of it.
 *
 * Covers the two delimiter families models actually emit. Anything else is left alone: a
 * parser that guesses at unknown syntax deletes real answers, and showing markup is a
 * smaller failure than showing a truncated reply.
 *
 * Wanted in two places, which is why it lives here. The turn loop uses it for what the model
 * said before asking for a tool, and the reply the user reads needs it for the case where
 * neither parser recognised the call: a name no tool has cannot be salvaged into anything, so
 * the invocation itself was what reached the screen.
 */
fun String.withoutToolMarkup(): String =
    TOOL_MARKUP.fold(this) { text, pattern -> pattern.replace(text, "") }
        // The closed ones first, so a finished call followed by prose keeps the prose. What
        // is left with an opener and no closer is a call that was cut off, which is what
        // Stop makes of one, and there is nothing after it to keep.
        .let { closed -> UNCLOSED_TOOL_MARKUP.fold(closed) { text, p -> p.replace(text, "") } }
        .trim()

/**
 * True when the text carries a tool invocation, whether or not anything could read it.
 *
 * The difference between a model that answered and one that tried to call something and got
 * the syntax wrong. Only the second is worth another pass.
 */
fun String.containsToolMarkup(): Boolean = TOOL_MARKUP.any { it.containsMatchIn(this) }

private val TOOL_MARKUP = listOf(
    Regex("""<\|tool_call_start\|>.*?<\|tool_call_end\|>""", RegexOption.DOT_MATCHES_ALL),
    Regex("""<tool_call>.*?</tool_call>""", RegexOption.DOT_MATCHES_ALL),
)

/**
 * An invocation that opened and never closed, which is what a stopped one looks like.
 *
 * Only ever matched after the closed forms, and only to the end of the text, because that is
 * what a truncated call is: the model was writing it when the tokens stopped coming. Nothing
 * was going to run it, so it is not part of the reply, and it used to be kept: pressing Stop
 * over a half-written call wrote the markup into storage as the assistant's turn, and every
 * later turn of that conversation was handed an assistant asking for a tool with no result
 * after it.
 *
 * Not folded into [containsToolMarkup], deliberately. That one decides whether to spend a
 * pass asking the model to try again, and a call the user stopped is not one they want
 * retried.
 */
private val UNCLOSED_TOOL_MARKUP = listOf(
    Regex("""<\|tool_call_start\|>.*$""", RegexOption.DOT_MATCHES_ALL),
    Regex("""<tool_call>.*$""", RegexOption.DOT_MATCHES_ALL),
)
