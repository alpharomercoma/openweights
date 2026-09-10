/*
 * Copyright 2025 Alpha Romer Coma
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
 * The model's typographic apostrophes and quotes as the ASCII ones every pattern spells.
 *
 * LFM2.5 writes "I’ll search" and "I don’t have" with U+2019, and a matcher written as
 * `i'll` walks straight past it: on 2026-09-10 the loop let "I’ll search for the latest
 * information about alpha Romer coma ... After reviewing recent sources, there is no
 * widely recognized public figure" stand as an answer, and 705 of the 5,462 no-call replies
 * the four phones had produced carried the character, twelve of them announcements the
 * rule should have carried out. Every classifier reads the reply through this first.
 */
fun String.plainQuotes(): String =
    replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
