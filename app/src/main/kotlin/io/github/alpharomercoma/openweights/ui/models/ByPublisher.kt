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

package io.github.alpharomercoma.openweights.ui.models

/** Installed models under the name of whoever published them. */
data class PublisherGroup(
    /** Null when nobody could be named, in which case the group carries no heading. */
    val publisher: String?,
    val models: List<LocalModel>,
) {
    /**
     * What the heading says, or null for a group that should not have one.
     *
     * This used to say "Added by hand" for anything with no recorded publisher, and that was
     * wrong twice over. It reads as an accusation of something the user did not do, and on
     * every install that predates the app recording publishers at all, which is every install
     * that existed when grouping shipped, it was the heading over *every* model. A label that
     * appears once is a category; a label that appears over everything is noise.
     *
     * So a group with nobody to name has no heading, and the models simply sit at the end of
     * the list. Nothing is lost: a heading that says nothing was never telling anyone
     * anything.
     */
    val heading: String? get() = publisher
}

/**
 * Installed models grouped by publisher, publishers in alphabetical order.
 *
 * The list was in whatever order the files came off disk, which is download order, and
 * download order is a fact about the past rather than about the models. Somebody with three
 * Liquid models and two Qwen ones had them interleaved by when they happened to be fetched.
 *
 * Alphabetical rather than by count or by recency, because a heading that moves is a heading
 * you have to read: the point of grouping is that the second time you open this you already
 * know where to look. Models inside a group keep their own alphabetical order for the same
 * reason. Unattributed files go last, since they are the exception and putting them first
 * would push the common case down the screen.
 */
fun List<LocalModel>.byPublisher(): List<PublisherGroup> =
    groupBy { it.publisher ?: it.name.publisherFromName() }
        .map { (publisher, models) -> PublisherGroup(publisher, models.sortedBy { it.name }) }
        .sortedWith(compareBy({ it.publisher == null }, { it.publisher?.lowercase() }))

/**
 * Who published a model, read off its filename, for the ones that can be read off reliably.
 *
 * The app records the publisher when it downloads something, which is the right answer and
 * the only certain one. It is also blank for every model that was already on a phone before
 * that recording existed, and for anything side loaded, and the first version of this put all
 * of them under a heading reading "Added by hand" that was wrong twice: it names something
 * the user did not do, and on an existing install it was the heading over every model.
 *
 * A filename is weaker evidence, and for this handful it is not weak. `LFM2.5-2.6B-QAD-Q4_0`
 * is a Liquid model in the same way `Qwen3-1.7B` is a Qwen one: the family name is the
 * publisher's own and nobody else ships under it. Matched on the start of the name, so a fine
 * tune called `my-qwen-experiment` is attributed to nobody, and anything unrecognised still
 * gets no heading rather than a guess.
 */
private fun String.publisherFromName(): String? {
    val name = substringAfterLast('/').lowercase()
    return FAMILIES.entries.firstOrNull { (prefix, _) -> name.startsWith(prefix) }?.value
}

/**
 * Family name to publisher, for families whose name is the publisher's own.
 *
 * Deliberately short. Every entry is a claim the app makes on a publisher's behalf, so the
 * bar is that the family name is unambiguous and the publisher is the one who ships it.
 */
private val FAMILIES = mapOf(
    "lfm" to "LiquidAI",
    "qwen" to "Qwen",
    "gemma" to "Google",
    "llama" to "Meta",
    "phi" to "Microsoft",
    "smollm" to "HuggingFaceTB",
    "granite" to "IBM",
    "mistral" to "Mistral AI",
    "deepseek" to "DeepSeek",
    "olmo" to "AllenAI",
)
