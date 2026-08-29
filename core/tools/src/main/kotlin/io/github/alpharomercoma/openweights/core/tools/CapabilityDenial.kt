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

/**
 * Reads a reply that claims a missing capability, so the turn can spend a pass fixing it.
 *
 * The failure this exists for was reproduced at temperature zero on the exact shipped
 * prompt: shown five working tools, LFM2.5-1.2B opens with "I'm sorry, but I don't have a
 * tool that can..." on a third of a 34-case suite, for things its tools do (search the
 * current meta, multiply two numbers) and for things it needs no tool for at all (write a
 * haiku, translate a phrase). Half of those replies then do the thing anyway under the
 * apology; the other half stop at an offer, and the user is the one who has to type "go".
 *
 * Wording was the first lever tried and it is refuted twice over. The system prompt already
 * says, at length, that the tools work and none of it is true; a sentence naming the exact
 * apology phrase changed nothing on-device; and on this suite five description rewrites
 * moved the total by at most one in either direction while reshuffling unrelated cases. The
 * lever that works is mechanical, and it is the same one the user reaches for: pushed with
 * one corrective line, the model does the right thing nearly every time. So the push is
 * automated, and this object is the part that reads the model's own denial to decide which
 * push it earned.
 *
 * Two pushes, because a retry that can still see the tools calls one, almost whatever the
 * corrective text says — measured, a haiku request retried "with no apology" became a call
 * to web_search. A denial about *looking up, fetching or computing* keeps the tools and is
 * told which one fits: eight of eight such denials converted to the right call. Any other
 * denial is about something the model can write in the reply, so the retry takes the tools
 * away — with nothing to call, six of six wrote the complete answer cleanly.
 */
object CapabilityDenial {
    /**
     * Whether this reply is a claim of missing capability rather than an answer.
     *
     * Judged on the head of the reply only. Every denial observed opens with one — it is a
     * reflex prefix, not a conclusion — and a match deeper in the text is far more likely
     * to be quoted or legitimate content. Both halves are required: a denial word alone is
     * an ordinary refusal ("I can't help with that"), and a capability noun alone is an
     * ordinary sentence about tools. "I'm sorry for your loss" matches neither.
     */
    fun denies(reply: String): Boolean {
        val head = reply.head()
        // The refusal check reads only the denial's own sentence. The rest of the head is
        // usually a suggestion ("set a reminder on your phone instead"), and "your" there
        // is the model being helpful, not a privacy boundary being claimed.
        return DENIAL.containsMatchIn(head) &&
            CAPABILITY.containsMatchIn(head) &&
            !REFUSAL.containsMatchIn(head.substringBefore(". "))
    }

    /**
     * The tools the denial itself says are needed, best fit first, empty when the task is
     * something to write rather than to run.
     *
     * Read from the model's own words, not the user's: the denial names the capability the
     * model decided it lacked ("access to the latest information", "perform that
     * calculation"), which is exactly the routing decision it got right before talking
     * itself out of it. An address anywhere in the question outranks that, because a turn
     * holding a URL was given its errand by the user directly.
     */
    fun fitting(denial: String, question: String): List<String> {
        // The first sentence, because that is where the model names the capability it
        // refused; what follows is often a list of what it supposedly can do instead, and
        // that list poisons the match. "I can't write code for functions. My capabilities
        // are focused on searching the web ... and setting up reminders" names writing,
        // and a classifier reading the whole head sent it to the watch tool.
        val refused = denial.head().substringBefore(". ")
        return when {
            question.contains("http", ignoreCase = true) ->
                listOf(FetchUrlTool.NAME, WebSearchTool.NAME)
            COMPUTE_SHAPED.containsMatchIn(refused) -> listOf(RunScriptTool.NAME)
            SCHEDULE_SHAPED.containsMatchIn(refused) -> listOf(WatchTool.NAME)
            LOOKUP_SHAPED.containsMatchIn(refused) -> listOf(WebSearchTool.NAME)
            else -> emptyList()
        }
    }

    /** The corrective line for the retry, given the tool that fits or null for none. */
    fun retryRequest(fitting: String?): String = if (fitting == null) {
        "Write the complete answer yourself now, directly, with no apology and no " +
            "mention of tools."
    } else {
        "You do have a working tool for exactly this: $fitting. Call it now, with no " +
            "apology and no explanation."
    }

    /** Lowercased with curly apostrophes straightened, which is how this model writes. */
    private fun String.head(): String = take(HEAD_CHARS).lowercase().replace('’', '\'')

    private val DENIAL = Regex(
        "\\b(don't|do not|doesn't|does not|can't|cannot|unable to|not able to|no way to)\\b",
    )

    // "Webpage" is here for the denial that names no tool at all: "I can't view or
    // analyze the content of that specific webpage", said with a working fetch_url in the
    // prompt and the address in the question. Present tense only throughout: a "couldn't"
    // is a report of something genuinely tried and failed, and rewriting those would hide
    // real failures.
    // "abilit" covers both "the ability to" and "my capabilities"; both are said.
    private val CAPABILITY = Regex("tool|function|abilit|access|way to|web ?page")

    /**
     * Refusals this must never push against. "Access to your camera" is a privacy claim
     * and true; "I can't help with that" is a decision, not a capability; and a reply
     * that names the user's own things ("your files", "your location") is talking about
     * what it should not touch rather than what it cannot do. A retry that argued with
     * any of these would be the mechanism overriding a refusal it has no business
     * judging, so all of them read as not-a-denial and the reply stands as written.
     */
    private val REFUSAL = Regex("your |(can't|cannot|won't|will not) (help|assist|do that)")

    /**
     * Denials whose missing capability is a lookup. "Identify", "look up" and "information
     * about" are here because the on-device misses used exactly those words ("a tool that
     * can directly identify the most powerful character"); "search" deliberately is not,
     * because a write-shaped denial lists searching among the things it supposedly can do
     * ("my capabilities are focused on searching the web") and would flip class.
     */
    private val LOOKUP_SHAPED = Regex(
        "latest|up.?to.?date|real.?time|current |meta data|in stock|price" +
            "|identify|look up|find out|information about",
    )

    private val COMPUTE_SHAPED = Regex("calculat|arithmetic|comput|perform that")

    private val SCHEDULE_SHAPED = Regex("remind|schedul|monitor|alert|notif")

    /**
     * Two sentences of apology, roughly. Longer than any observed denial prefix and short
     * enough that a match inside pasted or quoted content later in a reply stays unread.
     */
    private const val HEAD_CHARS = 220
}
