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
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * Resuming a download, which is the riskiest code here and had no test at all.
 *
 * A model is one to two gigabytes over a phone connection, so the interesting path is never
 * the happy one. What matters is what happens to the half a file already on disk when the
 * network drops, the app is killed, or a publisher replaces the file with a different one
 * under the same name. Each of those has a branch in `download`, and each branch is a way to
 * either lose the bytes or wedge the retry forever.
 *
 * Against a real server rather than a stub of one, because the mechanism under test is an
 * HTTP `Range` request and a `Content-Range` reply, and a stub that answered them the way
 * this code expects would be testing the expectation.
 */
class ModelDownloaderResumeTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer().apply { start() }

    /**
     * Points the downloader's hardcoded host at the test server.
     *
     * The host belongs in the client rather than in a constructor argument, since an app
     * that could be told to fetch weights from anywhere is a worse app. Rewriting it here
     * keeps that true and still lets the transfer be driven.
     */
    private val httpClient = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                val rewritten = chain.request().newBuilder()
                    .url(
                        chain.request().url.newBuilder()
                            .scheme(server.url("/").scheme)
                            .host(server.url("/").host)
                            .port(server.url("/").port)
                            .build(),
                    )
                    .build()
                chain.proceed(rewritten)
            },
        )
        .build()

    private val downloader = ModelDownloader(
        httpClient = httpClient,
        client = HuggingFaceClient(httpClient, HubTokenSource { null }),
        tokenSource = HubTokenSource { null },
    )

    @After
    fun tearDown() = server.close()

    private val whole = ByteArray(2_048) { (it % 251).toByte() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun hubFile(size: Long = whole.size.toLong(), sha: String? = sha256(whole)) =
        HubFile("models/weights.gguf", size, sha)

    private fun body(bytes: ByteArray) = Buffer().apply { write(bytes) }

    private fun fetch(destination: File) = runBlocking {
        downloader.download("owner/repo", hubFile(), destination).toList()
    }

    private fun markPartial(
        destination: File,
        repoId: String = "owner/repo",
        file: HubFile = hubFile(),
    ) {
        File(folder.root, destination.name + ".part.source")
            .writeText(downloadIdentity(repoId, file))
    }

    @Test
    fun `a model installed before provenance was recorded is adopted on its checksum`() {
        // The upgrade path. Everything already on the device predates the .source sidecar,
        // so nothing has one. A repository that publishes a checksum, which is every LFS
        // tracked GGUF on the Hub, still proves the file is the right one, and the sidecar
        // is written so the check after this one is cheap.
        val destination = File(folder.root, "weights.gguf")
        destination.writeBytes(whole)

        val progress = runBlocking {
            downloader.download("owner/repo", hubFile(), destination).toList()
        }

        assertThat(progress.last()).isInstanceOf(DownloadProgress.Finished::class.java)
        assertThat(destination.readBytes()).isEqualTo(whole)
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(File(folder.root, "weights.gguf.source").readText())
            .isEqualTo(downloadIdentity("owner/repo", hubFile()))
    }

    @Test
    fun `a download that died halfway asks for the rest and keeps what it had`() {
        val destination = File(folder.root, "weights.gguf")
        val already = 800
        File(folder.root, "weights.gguf.part")
            .writeBytes(whole.copyOfRange(0, already))
        markPartial(destination)

        server.enqueue(
            MockResponse.Builder()
                .code(206)
                .addHeader("Content-Range", "bytes $already-${whole.size - 1}/${whole.size}")
                .body(body(whole.copyOfRange(already, whole.size)))
                .build(),
        )

        val progress = fetch(destination)

        val asked = server.takeRequest()
        assertThat(asked.headers["Range"]).isEqualTo("bytes=$already-")
        assertThat(destination.readBytes()).isEqualTo(whole)
        assertThat(progress.last()).isInstanceOf(DownloadProgress.Finished::class.java)
        // The partial is gone, not left beside the file it became.
        assertThat(File(folder.root, "weights.gguf.part").exists()).isFalse()
    }

    @Test
    fun `nothing on disk asks for the whole file, with no range at all`() {
        val destination = File(folder.root, "weights.gguf")
        server.enqueue(MockResponse.Builder().code(200).body(body(whole)).build())

        fetch(destination)

        // No header rather than "bytes=0-". A range on a fresh download is a request some
        // mirrors answer with a 206 and others with a 200, and asking for the whole file is
        // the one phrasing every server agrees on.
        assertThat(server.takeRequest().headers["Range"]).isNull()
        assertThat(destination.readBytes()).isEqualTo(whole)
    }

    @Test
    fun `a partial longer than the file is thrown away rather than resumed forever`() {
        // What happens when a publisher replaces a file with a smaller one under the same
        // name. Asking to resume past the end returns 416, and without this the retry loops
        // on that answer with nothing on screen offering a way out.
        val destination = File(folder.root, "weights.gguf")
        File(folder.root, "weights.gguf.part").writeBytes(ByteArray(whole.size + 500))
        markPartial(destination)

        server.enqueue(MockResponse.Builder().code(200).body(body(whole)).build())

        fetch(destination)

        // Started over, which is the point: no range, so the server sends the new file
        // whole rather than a 416 the retry would loop on.
        assertThat(server.takeRequest().headers["Range"]).isNull()
        assertThat(destination.readBytes()).isEqualTo(whole)
    }

    @Test
    fun `a partial that is already complete is verified rather than re-fetched`() {
        // A download that finished and died before the rename. Asking the server to resume
        // from the end is a 416, so it must not ask at all.
        val destination = File(folder.root, "weights.gguf")
        File(folder.root, "weights.gguf.part").writeBytes(whole)
        markPartial(destination)

        val progress = fetch(destination)

        assertThat(server.requestCount).isEqualTo(0)
        assertThat(destination.readBytes()).isEqualTo(whole)
        assertThat(progress.last()).isInstanceOf(DownloadProgress.Finished::class.java)
    }

    @Test
    fun `a file already in place is not downloaded again`() {
        val destination = File(folder.root, "weights.gguf").apply { writeBytes(whole) }

        val progress = fetch(destination)

        assertThat(server.requestCount).isEqualTo(0)
        assertThat(progress).hasSize(1)
    }

    @Test
    fun `a server that stops early leaves the bytes for the next attempt`() {
        val destination = File(folder.root, "weights.gguf")
        // Half the file and then the connection goes, which is a phone leaving a lift.
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(body(whole.copyOfRange(0, 1_000)))
                .build(),
        )

        val failure = runCatching { fetch(destination) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(DownloadException::class.java)
        assertThat((failure as DownloadException).isRetryable).isTrue()
        // The point of the whole design: what arrived is still there.
        assertThat(File(folder.root, "weights.gguf.part").length()).isEqualTo(1_000)
        assertThat(destination.exists()).isFalse()
    }

    @Test
    fun `a file whose checksum is wrong is not put into place`() {
        val destination = File(folder.root, "weights.gguf")
        val corrupt = whole.copyOf().also { it[10] = (it[10] + 1).toByte() }
        server.enqueue(MockResponse.Builder().code(200).body(body(corrupt)).build())

        val failure = runCatching { fetch(destination) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(DownloadException::class.java)
        assertThat(destination.exists()).isFalse()
    }

    @Test
    fun `a file already there is checked against the published hash`() = runBlocking {
        // A publisher replacing bytes under the same name and the same size used to be
        // invisible: the length matched, the download reported Finished, and the app kept
        // running the old weights with nothing on screen disagreeing.
        val destination = File(folder.root, "weights.gguf")
        // Same length as `whole`, different bytes: exactly the replaced-upstream case.
        destination.writeBytes(ByteArray(whole.size) { 9 })

        server.enqueue(MockResponse.Builder().code(200).body(body(whole)).build())

        val states = downloader.download("owner/repo", hubFile(), destination).toList()

        assertThat(states.last()).isInstanceOf(DownloadProgress.Finished::class.java)
        assertThat(destination.readBytes().toList()).isEqualTo(whole.toList())
    }

    @Test
    fun `a file already there with a matching hash is not fetched again`() = runBlocking {
        // The counterweight: rehashing is cheaper than redownloading, but redownloading
        // every time would be worse than either.
        val destination = File(folder.root, "weights.gguf")
        destination.writeBytes(whole)

        val before = server.requestCount

        val states = downloader.download("owner/repo", hubFile(), destination).toList()

        assertThat(states.single()).isInstanceOf(DownloadProgress.Finished::class.java)
        assertThat(server.requestCount).isEqualTo(before)
    }

    @Test
    fun `a partial from another repository is never resumed`() = runBlocking {
        val destination = File(folder.root, "weights.gguf")
        File(folder.root, "weights.gguf.part").writeBytes(ByteArray(800) { 9 })
        markPartial(destination, repoId = "other/repo")
        server.enqueue(MockResponse.Builder().code(200).body(body(whole)).build())

        downloader.download("owner/repo", hubFile(), destination).toList()

        assertThat(server.takeRequest().headers["Range"]).isNull()
        assertThat(destination.readBytes()).isEqualTo(whole)
    }

    @Test
    fun `an unverified same-name file from another source is preserved`() = runBlocking {
        val destination = File(folder.root, "weights.gguf").apply { writeBytes(whole) }
        val unverified = hubFile(sha = null)

        val failure = runCatching {
            downloader.download("another/repo", unverified, destination).toList()
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(DownloadException::class.java)
        assertThat(destination.readBytes()).isEqualTo(whole)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a 416 that does not say how long the file is does not finish the download`() {
        val destination = File(folder.root, "weights.gguf")
        val file = hubFile(size = 0L)
        File(folder.root, "weights.gguf.part").writeBytes(whole.copyOf(whole.size / 2))
        markPartial(destination, file = file)

        // Unknown size and a 416 with no total: nothing here says the half on disk is the
        // whole file, and it used to be verified and installed as one.
        server.enqueue(MockResponse.Builder().code(416).build())

        val failure = runCatching {
            runBlocking { downloader.download("owner/repo", file, destination).toList() }
        }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(destination.exists()).isFalse()
    }

    @Test
    fun `a resume that receives 416 when already at end of file succeeds`() {
        val destination = File(folder.root, "weights.gguf")
        val file = hubFile(size = 0L)
        File(folder.root, "weights.gguf.part").writeBytes(whole)
        markPartial(destination, file = file)

        // When sizeBytes was unknown (0) or partial equals remote total, server sends 416
        server.enqueue(
            MockResponse.Builder()
                .code(416)
                .addHeader("Content-Range", "bytes */${whole.size}")
                .build(),
        )

        val progress = runBlocking {
            downloader.download("owner/repo", file, destination).toList()
        }

        assertThat(destination.readBytes()).isEqualTo(whole)
        assertThat(progress.last()).isInstanceOf(DownloadProgress.Finished::class.java)
        assertThat(File(folder.root, "weights.gguf.part").exists()).isFalse()
    }
}
