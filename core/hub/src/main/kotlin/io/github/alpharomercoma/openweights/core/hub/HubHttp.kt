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

package io.github.alpharomercoma.openweights.core.hub

import okhttp3.Request

/**
 * How this app talks HTTP to the Hub.
 *
 * Search, header inspection and downloading each build their own requests, and each used
 * to spell out the same header names, the same scheme word, and the same status code. They
 * are one protocol, so they are written down once. A token that stopped being sent from one
 * of the three would show up as a gated repository working in two places and failing in the
 * third, which is a slow thing to notice.
 */
internal object HubHttp {
    const val PARTIAL_CONTENT = 206

    private const val AUTHORIZATION = "Authorization"
    private const val RANGE = "Range"
    private const val BEARER = "Bearer"

    /** Attaches the access token, when there is one. */
    fun Request.Builder.withToken(token: String?): Request.Builder = apply {
        if (token != null) header(AUTHORIZATION, "$BEARER $token")
    }

    /** Asks for one window of a file, by absolute byte offsets. */
    fun Request.Builder.withRange(offset: Long, length: Int): Request.Builder =
        header(RANGE, "bytes=$offset-${offset + length - 1}")

    /** Asks for the rest of a file from [offset] on, which is how a download resumes. */
    fun Request.Builder.withRangeFrom(offset: Long): Request.Builder =
        header(RANGE, "bytes=$offset-")
}
