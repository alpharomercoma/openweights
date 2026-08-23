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
    /** Null for files this app did not fetch, which are shown last. */
    val publisher: String?,
    val models: List<LocalModel>,
) {
    /**
     * What the heading says.
     *
     * A file put here by hand has no publisher and never will: nothing in a `.gguf` says who
     * made it. Saying so is better than guessing from the filename, which would attribute
     * the wrong company to anything named after its architecture rather than its author.
     */
    val heading: String get() = publisher ?: "Added by hand"
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
