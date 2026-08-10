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

package io.github.alpharomercoma.openweights.core.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** How the conversation drawer breaks a long history into something scannable. */
class DayGroupTest {
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 8, 10)

    private data class Chat(val name: String, val at: Long)

    private fun chat(name: String, daysAgo: Long, hour: Int = 12) = Chat(
        name,
        today.minusDays(
            daysAgo,
        ).atTime(LocalTime.of(hour, 0)).atZone(zone).toInstant().toEpochMilli(),
    )

    private fun group(vararg chats: Chat) = chats.toList().groupByDay(today, zone) { it.at }

    @Test
    fun `the buckets are the ones every assistant uses`() {
        val groups = group(
            chat("now", 0),
            chat("yesterday", 1),
            chat("this week", 4),
            chat("this month", 20),
            chat("ancient", 200),
        )

        assertThat(groups.map { it.bucket }).containsExactly(
            DayBucket.TODAY,
            DayBucket.YESTERDAY,
            DayBucket.THIS_WEEK,
            DayBucket.THIS_MONTH,
            DayBucket.OLDER,
        ).inOrder()
    }

    @Test
    fun `several chats on one day share a heading`() {
        val groups = group(chat("morning", 0, hour = 9), chat("evening", 0, hour = 21))

        assertThat(groups).hasSize(1)
        assertThat(groups.single().label).isEqualTo("Today")
        // Newest first inside the group, which is the order the drawer reads in.
        assertThat(groups.single().items.map { it.name })
            .containsExactly("evening", "morning").inOrder()
    }

    @Test
    fun `old chats are grouped by month, not by day`() {
        // Two days in the same month, months back. One heading, not two.
        val groups = group(chat("early", 120), chat("late", 125))

        val older = groups.filter { it.bucket == DayBucket.OLDER }
        assertThat(older).hasSize(1)
        assertThat(older.single().items).hasSize(2)
    }

    @Test
    fun `groups run newest first whatever order they arrived in`() {
        val groups = group(chat("ancient", 200), chat("now", 0), chat("this week", 3))

        assertThat(groups.first().bucket).isEqualTo(DayBucket.TODAY)
        assertThat(groups.last().bucket).isEqualTo(DayBucket.OLDER)
    }

    @Test
    fun `yesterday is a day, not twenty four hours`() {
        // Late last night and early this morning are hours apart and belong to different
        // headings, which is what a person means by yesterday.
        val groups = group(chat("late last night", 1, hour = 23), chat("early today", 0, hour = 1))

        assertThat(groups.map { it.label }).containsExactly("Today", "Yesterday").inOrder()
    }

    @Test
    fun `an empty history has no headings`() {
        assertThat(emptyList<Chat>().groupByDay(today, zone) { it.at }).isEmpty()
    }
}
