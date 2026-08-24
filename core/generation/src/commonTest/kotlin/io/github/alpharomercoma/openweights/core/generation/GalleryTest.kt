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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reading back what the phone made.
 *
 * Run on every platform rather than merely compiled for them, because the whole reason this
 * lives here instead of in a `WHERE` clause is that two platforms have to agree about it.
 * A rule expressed in SQL is a rule the other side reimplements and gets subtly different,
 * and "subtly different" in a gallery means somebody's picture is missing.
 */
class GalleryTest {
    private fun entry(
        id: Long,
        at: Long = id,
        millis: Long = 1000,
        modality: GenerationTask = GenerationTask.IMAGE,
        prompt: String = "a lighthouse",
        favourite: Boolean = false,
    ) = GalleryEntry(
        id = id,
        artifact = Artifact("/pictures/$id.png", "image/png"),
        modality = modality,
        prompt = prompt,
        bundleId = "sd15",
        bundleName = "Stable Diffusion 1.5",
        createdAt = at,
        totalMillis = millis,
        backend = "OpenCL",
        isFavourite = favourite,
    )

    private val all = listOf(
        entry(1, at = 300, millis = 9_000, prompt = "a lighthouse in fog"),
        entry(2, at = 100, millis = 3_000, modality = GenerationTask.SPEECH, prompt = "read this"),
        entry(3, at = 200, millis = 6_000, prompt = "A LIGHTHOUSE at dawn", favourite = true),
    )

    @Test
    fun newestFirstIsTheDefault() {
        assertEquals(listOf(1L, 3L, 2L), all.matching(GalleryQuery()).map { it.id })
    }

    @Test
    fun oldestFirstIsTheOtherEnd() {
        val query = GalleryQuery(sort = GallerySort.OLDEST)

        assertEquals(listOf(2L, 3L, 1L), all.matching(query).map { it.id })
    }

    @Test
    fun quickestFirstAnswersWhatThisPhoneCanDo() {
        val query = GalleryQuery(sort = GallerySort.FASTEST)

        assertEquals(listOf(2L, 3L, 1L), all.matching(query).map { it.id })
    }

    @Test
    fun slowestFirstIsTheSameQuestionFromTheOtherEnd() {
        val query = GalleryQuery(sort = GallerySort.SLOWEST)

        assertEquals(listOf(1L, 3L, 2L), all.matching(query).map { it.id })
    }

    @Test
    fun oneModalityLeavesTheOtherOut() {
        val query = GalleryQuery(modalities = setOf(GenerationTask.SPEECH))

        assertEquals(listOf(2L), all.matching(query).map { it.id })
    }

    @Test
    fun bothModalitiesIsEverything() {
        val query = GalleryQuery(modalities = setOf(GenerationTask.IMAGE, GenerationTask.SPEECH))

        assertEquals(3, all.matching(query).size)
    }

    @Test
    fun noModalityIsAlsoEverything() {
        // The state a screen opens in and the state "clear filters" returns to. Read as
        // "none of them", a gallery would look empty the first time anybody opened it.
        assertEquals(3, all.matching(GalleryQuery()).size)
    }

    @Test
    fun favouritesOnlyKeepsTheShortlist() {
        val query = GalleryQuery(favouritesOnly = true)

        assertEquals(listOf(3L), all.matching(query).map { it.id })
    }

    @Test
    fun searchIgnoresCase() {
        // Somebody looking for their lighthouse did not type it the way they typed it then.
        val query = GalleryQuery(search = "lighthouse")

        assertEquals(listOf(1L, 3L), all.matching(query).map { it.id })
    }

    @Test
    fun searchIgnoresSurroundingSpace() {
        assertEquals(2, all.matching(GalleryQuery(search = "  lighthouse ")).size)
    }

    @Test
    fun blankSearchMatchesEverything() {
        assertEquals(3, all.matching(GalleryQuery(search = "   ")).size)
    }

    @Test
    fun filtersCombineRatherThanCompete() {
        val query = GalleryQuery(
            modalities = setOf(GenerationTask.IMAGE),
            favouritesOnly = true,
            search = "dawn",
        )

        assertEquals(listOf(3L), all.matching(query).map { it.id })
    }

    @Test
    fun aFilterThatMatchesNothingReturnsNothingRatherThanEverything() {
        assertTrue(all.matching(GalleryQuery(search = "a submarine")).isEmpty())
    }

    @Test
    fun theCountSaysHowManyFiltersAreOn() {
        assertEquals(0, GalleryQuery().activeFilterCount)
        assertTrue(GalleryQuery().isUnfiltered)
        assertEquals(
            3,
            GalleryQuery(
                modalities = setOf(GenerationTask.IMAGE),
                favouritesOnly = true,
                search = "fog",
            ).activeFilterCount,
        )
        // Sorting is not filtering. A gallery sorted differently is still the whole gallery,
        // and a badge saying one filter is on would send somebody looking for it.
        assertTrue(GalleryQuery(sort = GallerySort.OLDEST).isUnfiltered)
    }

    @Test
    fun entriesSharingAMomentKeepAStableOrder() {
        // A batch lands in the same millisecond, and a sort that leaves those to chance
        // reshuffles the grid on every rebuild. On a screen that recomposes while a
        // generation is running, that reads as the pictures moving on their own.
        val batch = listOf(entry(7, at = 500), entry(8, at = 500), entry(9, at = 500))

        val once = batch.matching(GalleryQuery()).map { it.id }
        val again = batch.reversed().matching(GalleryQuery()).map { it.id }

        assertEquals(once, again)
        assertEquals(listOf(9L, 8L, 7L), once)
    }

    @Test
    fun entriesSharingADurationAlsoKeepAStableOrder() {
        val batch = listOf(entry(7, millis = 42), entry(8, millis = 42), entry(9, millis = 42))

        assertEquals(
            batch.matching(GalleryQuery(sort = GallerySort.FASTEST)).map { it.id },
            batch.reversed().matching(GalleryQuery(sort = GallerySort.FASTEST)).map { it.id },
        )
    }

    @Test
    fun anEmptyGalleryIsAnEmptyAnswerRatherThanAFailure() {
        assertTrue(emptyList<GalleryEntry>().matching(GalleryQuery()).isEmpty())
    }
}
