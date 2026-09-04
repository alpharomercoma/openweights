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

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Looking through a page for the part that answers the question.
 *
 * `fetch_url` could only ever return a page's opening: four thousand characters off the
 * front, whatever the page was about. For a reference page, a changelog, a table of
 * specifications or anything else worth fetching by address, the answer is almost never
 * in the opening, and the model had no way to ask for the rest. Its only recourse was
 * `save_to` and a script, which is two more round trips and a sandbox on a phone.
 *
 * So the pattern comes in with the call and the search happens here, against the whole
 * downloaded page rather than the excerpt. What comes back is the matches with enough
 * text around each to read them, which is both smaller than the excerpt and far more
 * likely to contain the answer.
 *
 * ### The pattern is somebody else's regular expression
 *
 * It is written by a language model, which means it is neither trusted nor careful, and
 * `java.util.regex` is a backtracking engine: `(a+)+b` against a page of a's does not
 * finish this century. There is no timeout in the API, so [Deadline] provides one — the
 * matcher reads the input through a `CharSequence` that starts throwing once the clock
 * runs out, which is the only interruption point a backtracking matcher offers.
 *
 * And a pattern that will not compile is not an error worth failing a fetch over. A model
 * asked to find "C++" writes `C++`, which is a syntax error and also obviously a literal,
 * so anything that does not compile is searched for as plain text instead.
 */
internal object PageSearch {

    /** Characters of the page kept on each side of a match. */
    private const val CONTEXT = 300

    /** How many matches are worth returning; a pattern matching everything is a mistake. */
    private const val MAX_MATCHES = 12

    /** The whole reply, which still has to leave room for the conversation around it. */
    private const val MAX_CHARS = 4_000

    /** How long a pattern gets before it is abandoned. */
    private const val BUDGET_MS = 750L

    private const val NANOS_PER_MILLI = 1_000_000L

    /** What a search found, or why it found nothing. */
    sealed interface Result {
        /**
         * [windows] are the matching parts of the page, in the order they appear.
         *
         * Fewer windows than [count] is ordinary rather than a cap: neighbouring matches
         * share most of their surrounding text and are merged into one. [more] is the cap,
         * and it is the only thing that makes the count a lower bound.
         */
        data class Found(val count: Int, val windows: List<String>, val more: Boolean) : Result

        /** The pattern is sound and the page does not contain it. */
        data object Absent : Result

        /** The pattern ran too long and was abandoned; [BUDGET_MS] says how long. */
        data object TooSlow : Result
    }

    /**
     * Every place [pattern] appears in [text], with [CONTEXT] characters around each.
     *
     * Case-insensitive, because a model writing a search term is writing what it would
     * type into a find box and nobody means the capitals there.
     */
    fun search(text: String, pattern: String): Result {
        val regex = compile(pattern)
        val guarded = Deadline(text, System.nanoTime() + BUDGET_MS * NANOS_PER_MILLI)
        val matcher = regex.matcher(guarded)
        val spans = mutableListOf<IntRange>()
        var count = 0
        try {
            var from = 0
            while (from <= text.length && matcher.find(from)) {
                count++
                if (spans.size < MAX_MATCHES) {
                    spans += (matcher.start() - CONTEXT).coerceAtLeast(
                        0,
                    )..(matcher.end() + CONTEXT).coerceAtMost(text.length)
                }
                // A pattern that can match nothing at all ("x*") would otherwise find the
                // empty string at every position forever.
                from = if (matcher.end() > matcher.start()) matcher.end() else matcher.end() + 1
                if (count >= MAX_MATCHES) break
            }
        } catch (_: TooSlow) {
            return Result.TooSlow
        }
        if (count == 0) return Result.Absent
        return Result.Found(count, windows(text, spans), more = count >= MAX_MATCHES)
    }

    /**
     * The pattern as a matcher: a regular expression when it is one, literal text when not.
     *
     * `DOTALL` because a page's readable text still has newlines in it and a model writing
     * `Price.*USD` means across them; nobody writing a search box pattern is thinking
     * about line anchors.
     */
    private fun compile(pattern: String): Pattern = try {
        Pattern.compile(pattern, Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    } catch (_: PatternSyntaxException) {
        Pattern.compile(Pattern.quote(pattern), Pattern.CASE_INSENSITIVE)
    }

    /** The spans as text, overlapping ones joined so a dense page is not repeated. */
    private fun windows(text: String, spans: List<IntRange>): List<String> {
        val merged = mutableListOf<IntRange>()
        for (span in spans) {
            val last = merged.lastOrNull()
            if (last != null && span.first <= last.last) {
                merged[merged.lastIndex] = last.first..maxOf(last.last, span.last)
            } else {
                merged += span
            }
        }
        var budget = MAX_CHARS
        val out = mutableListOf<String>()
        for (span in merged) {
            if (budget <= 0) break
            val window = text.substring(span.first, span.last).trim().take(budget)
            budget -= window.length
            if (window.isNotEmpty()) out += window
        }
        return out
    }

    /** Raised through the matcher's own input when the budget is gone. */
    private class TooSlow : RuntimeException(null, null, false, false)

    /**
     * The text as the matcher reads it, with a clock attached.
     *
     * `Matcher` has no timeout and no cancellation. What it does have is a `CharSequence`
     * it reads one character at a time, which is where a runaway pattern spends all of its
     * time, so a `charAt` that throws is the interruption. The check is a clock read per
     * character and the matcher is the only caller.
     */
    private class Deadline(private val text: CharSequence, private val until: Long) :
        CharSequence {
        override val length: Int get() = text.length

        override fun get(index: Int): Char {
            if (System.nanoTime() > until) throw TooSlow()
            return text[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            Deadline(text.subSequence(startIndex, endIndex), until)
    }

    /** What the model is handed, for each shape of answer. */
    fun render(result: Result, pattern: String, pageChars: Int): String = when (result) {
        is Result.Found -> {
            val places = "${result.count} place${if (result.count == 1) "" else "s"}"
            // Counted in matches, not in the passages they were merged into: two matches a
            // line apart are one passage and still two answers, and a header that said
            // "1 of 3" read as most of them having been dropped.
            val header = if (result.more) {
                "The first $places matching \"$pattern\" (there may be more):"
            } else {
                "$places matching \"$pattern\":"
            }
            (listOf(header) + result.windows).joinToString("\n\n---\n\n")
        }
        // Said as a fact about the page rather than as a failure, and with its length, so
        // the model can decide between a looser pattern and reading the page whole. A bare
        // "not found" got reported to the user as "the page does not mention it", which is
        // a different and sometimes wrong claim.
        Result.Absent ->
            "Nothing on that page matches \"$pattern\". The page has " +
                "$pageChars characters of readable text; fetch it without find to read it, " +
                "or try a simpler pattern."
        Result.TooSlow ->
            "That pattern took too long to run against the page and was " +
                "stopped. Try a simpler one, or plain words instead of a regular expression."
    }
}
