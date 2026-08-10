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

package io.github.alpharomercoma.openweights.core.designsystem.component

import java.util.Locale

/**
 * How this app writes a file size.
 *
 * Every screen shows one: Discover sizes a download, Models sizes what is on disk, Settings
 * sizes the lot. They have to agree, so the rule lives here rather than in whichever screen
 * happened to need it first.
 *
 * Binary units, because these are always storage or memory rather than a figure quoted by a
 * disk manufacturer. Two decimals for gigabytes and none for megabytes: below a gigabyte
 * the extra digits are noise, above it they are the difference between fitting and not.
 */
fun formatBytes(bytes: Long): String {
    val locale = Locale.getDefault()
    val gigabytes = bytes / BYTES_PER_GIB
    return if (gigabytes >= 1) {
        String.format(locale, "%.2f GB", gigabytes)
    } else {
        String.format(locale, "%.0f MB", bytes / BYTES_PER_MIB)
    }
}

private const val BYTES_PER_MIB = 1024.0 * 1024.0
private const val BYTES_PER_GIB = BYTES_PER_MIB * 1024.0
