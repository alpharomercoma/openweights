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

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How a list of past conversations is broken up by when it was last touched.
 *
 * Every assistant does this, and for the same reason: a flat list of forty titles is a
 * list nobody scans. The buckets get coarser the further back you go, because "which
 * Tuesday in March" is not a question anyone can answer about their own chat history.
 */
enum class DayBucket(val label: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    THIS_WEEK("Previous 7 days"),
    THIS_MONTH("Previous 30 days"),
    OLDER("Older"),
}

/** One heading and the conversations under it, newest first. */
data class DayGroup<T>(val bucket: DayBucket, val label: String, val items: List<T>)

/**
 * Groups by local day, newest first.
 *
 * Local, not UTC: a chat at eleven at night belongs to the day the user had, not to the
 * day Greenwich had. Anything older than a month keeps its month name, so a long history
 * reads as a timeline rather than as one enormous "Older".
 *
 * @param today injected so the buckets do not depend on when the suite runs.
 */
fun <T> List<T>.groupByDay(
    today: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
    timestamp: (T) -> Long,
): List<DayGroup<T>> {
    if (isEmpty()) return emptyList()

    return sortedByDescending(timestamp)
        .groupBy { item ->
            val day = Instant.ofEpochMilli(timestamp(item)).atZone(zone).toLocalDate()
            bucketFor(day, today) to day
        }
        .entries
        // Older entries keep one heading per month rather than one per day, so the key is
        // reduced before the groups are merged.
        .groupBy { (key, _) ->
            val (bucket, day) = key
            if (bucket == DayBucket.OLDER) bucket to monthLabel(day) else bucket to bucket.label
        }
        .map { (key, entries) ->
            val (bucket, label) = key
            DayGroup(bucket, label, entries.flatMap { it.value })
        }
        .sortedByDescending { group -> group.items.maxOf(timestamp) }
}

private fun bucketFor(day: LocalDate, today: LocalDate): DayBucket = when {
    day == today -> DayBucket.TODAY
    day == today.minusDays(1) -> DayBucket.YESTERDAY
    day.isAfter(today.minusDays(DAYS_IN_WEEK)) -> DayBucket.THIS_WEEK
    day.isAfter(today.minusDays(DAYS_IN_MONTH)) -> DayBucket.THIS_MONTH
    else -> DayBucket.OLDER
}

/** "March" this year, "March 2025" once the year stops being obvious. */
private fun monthLabel(day: LocalDate): String {
    val pattern = if (day.year == LocalDate.now().year) "MMMM" else "MMMM yyyy"
    return day.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}

private const val DAYS_IN_WEEK = 7L
private const val DAYS_IN_MONTH = 30L
