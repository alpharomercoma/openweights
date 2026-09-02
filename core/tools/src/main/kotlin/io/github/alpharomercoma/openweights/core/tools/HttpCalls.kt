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

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The request, awaited rather than executed.
 *
 * `execute()` blocks the thread it is on and answers to nothing until the socket does: the
 * tools ran it inside `withContext(Dispatchers.IO)`, where a cancellation from Stop marks
 * the coroutine and then waits for the read to finish, which a server dripping a byte a
 * minute makes never. The engine stays claimed for the whole of it, so no turn and no
 * watch can run. Enqueued instead, the call is cancelled the moment the coroutine is, and
 * the socket goes with it.
 */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, _, _ -> response.close() }
            }
        },
    )
    continuation.invokeOnCancellation { runCatching { cancel() } }
}

/**
 * The shared client with a ceiling on how long one of the model's requests may take.
 *
 * The client the app builds once has no call timeout, deliberately: it also fetches model
 * files, which are gigabytes. A tool's request is a page or a search result, and the same
 * open-ended clock there meant a slow host could hold a turn for as long as it kept the
 * connection alive. Sixty seconds is longer than any page worth reading takes to arrive
 * and far shorter than a user waits before deciding the app has hung.
 */
internal fun OkHttpClient.forTools(): OkHttpClient =
    newBuilder().callTimeout(TOOL_CALL_DEADLINE_SECONDS, TimeUnit.SECONDS).build()

private const val TOOL_CALL_DEADLINE_SECONDS = 60L
