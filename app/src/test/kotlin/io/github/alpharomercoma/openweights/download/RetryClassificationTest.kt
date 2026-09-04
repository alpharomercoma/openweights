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

package io.github.alpharomercoma.openweights.download

import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.hub.DownloadException
import io.github.alpharomercoma.openweights.core.hub.HubException
import org.junit.Test
import java.io.IOException

/**
 * Which download failures go back on the queue.
 *
 * The worker has five attempts and a backoff in front of it, and the whole value of that
 * is which failures reach it. A download resumes from the bytes already on disk, so a wait
 * costs nothing but time, and a wait in front of a permission error costs the user four
 * pointless minutes before the same sentence.
 */
class RetryClassificationTest {
    @Test
    fun `a dropped connection is worth another attempt`() {
        assertThat(IOException("connection reset").isWorthRetrying()).isTrue()
    }

    @Test
    fun `a Hub that said it was busy is worth another attempt`() {
        // The failure the backoff most exists for, and the one that used to skip it: a rate
        // limit or a 5xx arrives as a plain HubException and matched nothing retryable.
        val busy = HubException("rate limited", isRetryable = true)

        assertThat(busy.isWorthRetrying()).isTrue()
    }

    @Test
    fun `an answer that will not change is not waited on five times`() {
        assertThat(HubException("no access", isAuthFailure = true).isWorthRetrying()).isFalse()
        assertThat(HubException("gone").isWorthRetrying()).isFalse()
        assertThat(DownloadException("checksum did not match").isWorthRetrying()).isFalse()
    }

    @Test
    fun `a transfer that stopped short resumes`() {
        assertThat(DownloadException("ended early", isRetryable = true).isWorthRetrying()).isTrue()
    }
}
