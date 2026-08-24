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

package io.github.alpharomercoma.openweights.core.generation

/**
 * What the phone made, and everything needed to say where it came from.
 *
 * Kept whole rather than split by modality. A picture and a voice line share almost every
 * field here, and the two that differ, [seed] and [durationMillis], are both nullable
 * anyway: an image has no duration and a runtime that does not seed has no seed. Two tables
 * would mean two of every query, two sorts and two ways for a filter to be subtly wrong.
 *
 * Every field except [id] and [createdAt] comes from the run that produced the file. Nothing
 * here is inferred later from the file itself: a prompt read back out of an image's metadata
 * is a prompt somebody could have written into it, and the point of this record is that it
 * is the app's own account of what it did.
 */
data class GalleryEntry(
    val id: Long = 0,
    /** Where the file is. Relative handling is the platform's; this is what was written. */
    val artifact: Artifact,
    val modality: GenerationTask,
    /** Exactly what was asked for, so a result can be asked for again. */
    val prompt: String,
    val negativePrompt: String = "",
    /** The bundle that made it, by id, so a deleted bundle still names itself here. */
    val bundleId: String,
    val bundleName: String,
    /** Null where the runtime does not take one. Never invented. */
    val seed: Long? = null,
    val createdAt: Long,
    val totalMillis: Long,
    /** What actually ran, not what was asked for. */
    val backend: String,
    /** Pixels for an image, null for a voice. */
    val width: Int? = null,
    val height: Int? = null,
    /** Milliseconds of audio, null for an image. */
    val durationMillis: Long? = null,
    val sizeBytes: Long = 0,
    /**
     * Marked by the person who made it, so their own shortlist survives a busy week.
     *
     * The one piece of state here that is not a fact about the run. It is also the one
     * reason to keep a row after the file behind it is gone, which is why deleting a
     * favourite asks and deleting anything else does not.
     */
    val isFavourite: Boolean = false,
)

/** Which way a gallery is read. */
enum class GallerySort {
    /** Newest first, which is what somebody who just made something is looking for. */
    NEWEST,

    /** Oldest first, for finding the one from last week rather than the one from now. */
    OLDEST,

    /**
     * Quickest first.
     *
     * Here because this app's whole claim is that it runs on the phone, so how long a thing
     * took is a fact about the phone rather than trivia, and a gallery sorted by it is the
     * fastest way to see what a device can actually do.
     */
    FASTEST,

    /** Slowest first, which is the same question asked from the other end. */
    SLOWEST,
}

/**
 * What a gallery is being asked for.
 *
 * One value rather than a handful of parameters, because every screen state that can be
 * reached has to be storable and restorable: a filter the user set, an app that was killed,
 * and the same list on the way back.
 *
 * Empty [modalities] means every modality rather than none. Written that way because the
 * empty set is what a screen starts in and what "clear filters" returns to, and a query
 * that returned nothing in its default state would be a screen that looks broken on first
 * open.
 */
data class GalleryQuery(
    val sort: GallerySort = GallerySort.NEWEST,
    val modalities: Set<GenerationTask> = emptySet(),
    val favouritesOnly: Boolean = false,
    /** Matched against the prompt, case insensitively. Blank matches everything. */
    val search: String = "",
) {
    /** How many filters are on, for a screen that wants to say so without listing them. */
    val activeFilterCount: Int
        get() = modalities.size + (if (favouritesOnly) 1 else 0) + (if (hasSearch) 1 else 0)

    val hasSearch: Boolean get() = search.isNotBlank()

    val isUnfiltered: Boolean get() = activeFilterCount == 0
}

/**
 * The query applied, in one place, so every caller filters and sorts the same way.
 *
 * A pure function over a list rather than a query builder, and that is a decision about
 * where this has to run rather than about elegance. The same gallery is read on a phone out
 * of a database and on iOS out of whatever that platform ends up storing, and a rule that
 * lives in SQL is a rule the other platform has to reimplement and get subtly different.
 * Sorting a few hundred rows in memory costs nothing anybody can perceive.
 */
fun List<GalleryEntry>.matching(query: GalleryQuery): List<GalleryEntry> =
    filter { it.matches(query) }.sortedWith(query.sort.comparator())

private fun GalleryEntry.matches(query: GalleryQuery): Boolean {
    if (query.favouritesOnly && !isFavourite) return false
    if (query.modalities.isNotEmpty() && modality !in query.modalities) return false
    if (!query.hasSearch) return true
    return prompt.contains(query.search.trim(), ignoreCase = true)
}

/**
 * A total order, including the tie break.
 *
 * The tie break is not a detail. Two pictures from one batch share a millisecond often
 * enough, and a sort that leaves their order to chance reshuffles the grid every time the
 * list is rebuilt, which on a screen that recomposes while a generation runs looks like the
 * pictures are moving on their own. The id is monotonic and unique, so it settles it.
 */
private fun GallerySort.comparator(): Comparator<GalleryEntry> = when (this) {
    GallerySort.NEWEST ->
        compareByDescending<GalleryEntry> { it.createdAt }.thenByDescending { it.id }

    GallerySort.OLDEST ->
        compareBy<GalleryEntry> { it.createdAt }.thenBy { it.id }

    GallerySort.FASTEST ->
        compareBy<GalleryEntry> { it.totalMillis }.thenByDescending { it.id }

    GallerySort.SLOWEST ->
        compareByDescending<GalleryEntry> { it.totalMillis }.thenByDescending { it.id }
}
