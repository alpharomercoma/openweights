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
 * The name a "who is" question is about, when it is that kind of question.
 *
 * ### Why the app decides this and not the model
 *
 * The tool prompt already tells the model to search for a person or a story it does not
 * recognise. The trouble is that a 1B model recognises everything. Asked "Who is Killua?"
 * at temperature zero, LFM2.5-1.2B placed him in Naruto and Qwen3-1.7B in Final Fantasy,
 * both fluently, both without a call, both wrong (he is from Hunter x Hunter). Neither model
 * felt unsure, so no wording about being unsure could reach the case: a prompt arm that
 * widened the search-when-named clause to characters was measured on 2026-09-05 and moved
 * Qwen from 8 of 10 to 6 of 10. That is the third time in this codebase's history that a
 * routing wording has been measured and found not to move routing.
 *
 * What did move it, on both models, to 10 of 10, was a sentence on the question itself
 * saying that it names somebody and that the name is to be looked up. A model told *that
 * this* question is the kind it should search does search; a model told *what kind* of
 * question to search does not recognise the kind when it arrives. So the recognition is
 * done here, with a regular expression, on the four shapes such a question takes.
 *
 * The price is a search on "Who is Albert Einstein?", which the model could have answered.
 * Accepted: a search is a few seconds and a confabulated biography is wrong, and Mallen et
 * al. 2022 (arXiv:2212.10511) put the popularity threshold below which a model this size
 * should retrieve well above where a phone user's questions fall. The routing matrix in
 * `eval/routing_matrix.py` measures the model without this trailer, so its `known` rows
 * still expect no call and still mean what they meant: the model's own judgement.
 *
 * ### What counts
 *
 * "Who is X", "tell me about X", and "what happens in X" take any name, capitalised or not,
 * since people type names in lower case on phones and a pronoun stop list is enough to keep
 * "who is she" out. "What is X" takes only a capitalised name of two or more words, because
 * "what is photosynthesis" is the settled knowledge the prompt is right to answer directly
 * and "what is the Quenlark 7" is a product nobody can know without looking.
 */
object NamedSubject {
    /** The tool the trailer names, so a caller can check it is on offer before naming it. */
    val TOOL: String get() = WebSearchTool.NAME

    /** The subject the question names, or null when it is not a question of that shape. */
    fun of(question: String): String? {
        val trimmed = question.trim()
        if (trimmed.length > LONGEST_QUESTION) return null
        // A full stop is part of "St. Louis" and not part of "Dagupan.", and only the end of
        // the question can tell which, so the name keeps its dots and loses the last one.
        val subject = SHAPES.firstNotNullOfOrNull { it.matchEntire(trimmed)?.groupValues?.get(1) }
            ?.trim()
            ?.trimEnd('.', '?', '!')
            ?: return null
        if (subject.lowercase() in STOP_WORDS) return null
        return subject
    }

    /**
     * What to append to the question, at its tail, so the model looks the name up.
     *
     * Verbatim the wording that scored 10 of 10 on both models. A parenthesis rather than an
     * instruction, because it sits inside the user's own message and should read as a note
     * on it rather than as the user shouting.
     */
    fun trailer(subject: String): String =
        "(This question names $subject. Look it up with $TOOL before answering rather " +
            "than recalling it, and answer from what the search returns.)"

    /** One word of a name: a letter or digit to start, then word characters and a few marks. */
    private const val WORD = """[\p{L}\p{N}][\p{L}\p{N}'.\-]*"""

    /** Words a name can contain without being capitalised: "Riverlight Festival in Dagupan". */
    private const val JOINER = "of|the|and|in|at|de|von|van|da|del|la|le"

    /** A name of one word or more. */
    private const val NAME = "(?:$WORD)(?: (?:$WORD|$JOINER))*"

    /** A name of two words or more, at least the first of them capitalised. */
    private const val LONG_NAME = """(?:\p{Lu}[\p{L}\p{N}'.\-]*)(?: (?:$WORD|$JOINER))+"""

    private val SHAPES = listOf(
        Regex("""^(?i:who) (?i:is|was|are|were) (?i:the )?($NAME)\??$"""),
        Regex("""^(?i:what) (?i:is|was|are|were) (?i:the |a |an )?($LONG_NAME)\??$"""),
        Regex("""^(?i:tell me about) (?i:the )?($NAME)\.?$"""),
        Regex("""^(?i:what happens) (?i:in|at the end of|to|after) (?i:the )?($NAME)\??$"""),
    )

    /** Subjects that are not names: a pronoun, a demonstrative, a person in the room. */
    private val STOP_WORDS = setOf(
        "i", "me", "you", "he", "she", "it", "we", "they", "this", "that", "these", "those",
        "him", "her", "them", "us", "who", "what", "someone", "anyone", "everyone", "nobody",
        "there", "here", "the", "a", "an", "my", "your", "our", "their", "his", "its",
    )

    /**
     * Longer than this and it is not a question naming a thing, whatever it opens with.
     *
     * A hundred and twenty characters is a long "who is" question. Past it the regular
     * expressions are being run over a pasted paragraph, which is the one input a
     * backtracking matcher should not be handed.
     */
    private const val LONGEST_QUESTION = 120
}
