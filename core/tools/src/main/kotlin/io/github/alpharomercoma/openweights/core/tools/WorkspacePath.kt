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
 * Breaks a path the model wrote into the names to walk, or refuses it outright.
 *
 * Worth its own function, and its own test, because the path comes from a model, which may
 * have read it off a file the app was pointed at, which anyone can write. It is the same
 * shape of problem as [PublicOnlyDns]: something else chose the target, and one place decides
 * whether it is allowed.
 *
 * What it is *not* is a containment check. The obvious design, and the wrong one, is to join
 * the path onto the folder's own identifier and test that the result still starts with it.
 * That reasoning does not survive contact with the Storage Access Framework, where a document
 * is named by a provider-defined string with no path structure in it at all: Drive hands out
 * things like `doc:1Z...` whose children share no prefix with their parent, so there is
 * nothing for `..` to climb and nothing for a prefix test to mean. Containment is enforced by
 * walking down from the granted folder one name at a time, and by Android's own refusal to
 * answer for anything outside the grant. This function's job is narrower: make sure what gets
 * walked is a list of ordinary names, so the walk cannot be steered sideways.
 *
 * Unicode is deliberately not normalised. A name that does not match a child exactly is
 * simply not found, which is a wrong answer rather than an unsafe one, and normalising here
 * would invent a second spelling of a name the provider never had.
 */
internal fun String.workspaceSegments(): List<String>? {
    if (isEmpty() || length > MAX_PATH_LENGTH) return null

    // Anchored, scheme-like and Windows-shaped paths are refused whole rather than repaired.
    // "/etc/passwd" is not a typo for "etc/passwd": it is a different request, and quietly
    // treating it as one teaches the model that the leading slash was accepted. The colon
    // goes too, because that is the character a document id is built out of.
    if (startsWith('/') || contains('\\') || contains(':')) return null

    val segments = split('/')
    if (segments.size > MAX_SEGMENTS) return null
    return segments.takeIf { everyNameIsOrdinary(it) }
}

/**
 * Whether every name in a split path is one that can be looked up.
 *
 * `.` and `..` are refused rather than resolved. They have no meaning against a document id,
 * so resolving them would mean inventing a filesystem the provider does not have, and
 * refusing them costs nothing, since no ordinary file is called either.
 *
 * Spaces stay allowed. Half the files on a phone are called "My something", and a boundary
 * that refuses them is not safer, only broken.
 */
private fun everyNameIsOrdinary(segments: List<String>): Boolean = segments.all { segment ->
    segment.isNotBlank() &&
        segment.length <= MAX_SEGMENT_LENGTH &&
        segment != "." &&
        segment != ".." &&
        // Carried to the provider as a string and compared against one it handed back. A
        // terminator in the middle is not part of a name anybody gave a file.
        !segment.contains(NUL)
}

/** Deeper than any folder a person keeps notes in, and past it a walk is really a search. */
private const val MAX_SEGMENTS = 12

/** What every filesystem Android runs on stops at, so a longer name cannot exist to find. */
private const val MAX_SEGMENT_LENGTH = 255

/** Long enough for the deepest legal path, short enough that nothing pathological is walked. */
private const val MAX_PATH_LENGTH = 1024

/** The string terminator, named rather than written, because it is invisible either way. */
private val NUL = Char.MIN_VALUE
