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

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

/**
 * A file that is already there and cannot be read.
 *
 * Seen on a real phone: the destination existed at the right length but every read of it
 * returned nothing. Hashing it threw an `IOException`, which `ModelDownloadWorker`
 * classifies as the transfer having failed, so the work went back on the queue — **four
 * attempts, a thirty second backoff between each, "Downloading 0%" on screen the whole
 * time and no bytes moving.** Retrying could never have helped: the same unreadable bytes
 * are there next time.
 *
 * The contract is that this fails once, immediately, and says what to do about it.
 */
class UnreadableDestinationTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val downloader = ModelDownloader(
        httpClient = OkHttpClient(),
        client = HuggingFaceClient(OkHttpClient(), HubTokenSource { null }),
        tokenSource = HubTokenSource { null },
    )

    @Test
    fun `an existing file that cannot be read fails once instead of retrying forever`() {
        val bytes = ByteArray(2_048) { (it % 251).toByte() }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val destination = folder.newFile("weights.gguf").apply { writeBytes(bytes) }

        // Not every filesystem or JVM honours this, and root ignores it outright; where it
        // does not take, the case under test cannot be staged and the assertion would be
        // about nothing.
        assumeTrue(destination.setReadable(false, false) && !destination.canRead())

        val failure = runCatching {
            runBlocking {
                downloader.download(
                    "owner/repo",
                    HubFile("models/weights.gguf", bytes.size.toLong(), sha),
                    destination,
                ).toList()
            }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(DownloadException::class.java)
        // The whole point: the worker reads this flag to decide whether to queue it again.
        assertThat((failure as DownloadException).isRetryable).isFalse()
        assertThat(failure.message).contains("could not be read")
    }
}
