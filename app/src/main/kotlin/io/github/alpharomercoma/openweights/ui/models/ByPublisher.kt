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
     *
     * ### Why nothing is guessed from the filename
     *
     * The first attempt at filling those gaps read the family off the name, so `llama*`
     * became Meta and `gemma*` became Google. That is a different question answered
     * confidently. Meta trained Llama; the GGUF on the phone was almost certainly converted
     * and quantized by somebody else, and `bartowski/Llama-3.2-3B-Instruct-GGUF` is
     * published by bartowski. Putting Meta's name over that file credits the wrong party
     * and tells the user the file came from somewhere it did not, which matters here more
     * than in most apps because the whole download path is about knowing what you are
     * running. `llamaindex-*` would have matched the same rule and been credited to Meta on
     * no evidence at all.
     *
     * The repository owner recorded at download is the answer, it is already stored, and it
     * is correct by construction. Where it is absent the honest heading is none.
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
fun List<LocalModel>.byPublisher(): List<PublisherGroup> = groupBy { it.publisher }
    .map { (publisher, models) -> PublisherGroup(publisher, models.sortedBy { it.name }) }
    .sortedWith(compareBy({ it.publisher == null }, { it.publisher?.lowercase() }))
