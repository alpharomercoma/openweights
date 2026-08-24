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

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.alpharomercoma.openweights.core.data.db.OpenWeightsDatabase
import io.github.alpharomercoma.openweights.core.generation.Artifact
import io.github.alpharomercoma.openweights.core.generation.GalleryEntry
import io.github.alpharomercoma.openweights.core.generation.GenerationTask
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The row and the file, which are one thing.
 *
 * Writing a file and recording it cannot be a single atomic act, so both halves of the gap
 * are reachable: a row whose file has gone opens onto nothing, and a file whose row has gone
 * is storage nobody can see or reclaim. What is asserted here is that the repository closes
 * both, and that the one operation which reaches outside the database, deleting a file,
 * cannot be talked into touching anything it did not write.
 */
@RunWith(RobolectricTestRunner::class)
class GalleryRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: OpenWeightsDatabase
    private lateinit var store: GeneratedOutputStore
    private lateinit var gallery: GalleryRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, OpenWeightsDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = GeneratedOutputStore(context)
        store.directory.listFiles()?.forEach { it.delete() }
        gallery = GalleryRepository(database, store)
    }

    @After
    fun tearDown() {
        database.close()
        store.directory.deleteRecursively()
    }

    private fun made(name: String, bytes: Int = 16): File =
        File(store.directory, name).apply { writeBytes(ByteArray(bytes)) }

    private fun entry(file: File, at: Long = 100, task: GenerationTask = GenerationTask.IMAGE) =
        GalleryEntry(
            artifact = Artifact(file.absolutePath, "image/png"),
            modality = task,
            prompt = "a lighthouse",
            bundleId = "sd15",
            bundleName = "Stable Diffusion 1.5",
            seed = 42,
            createdAt = at,
            totalMillis = 9_000,
            backend = "OpenCL",
            width = 512,
            height = 512,
            sizeBytes = file.length(),
        )

    @Test
    fun `a recorded output comes back with everything it was recorded with`() = runTest {
        val saved = gallery.record(entry(made("one.png")))

        val read = gallery.byId(saved.id)
        assertThat(read).isEqualTo(saved)
        assertThat(read?.seed).isEqualTo(42)
        assertThat(read?.backend).isEqualTo("OpenCL")
    }

    @Test
    fun `recording gives back the id it was given rather than zero`() = runTest {
        // Everything downstream, favouriting, opening, deleting, is by id, and the caller
        // has only what this returns.
        assertThat(gallery.record(entry(made("one.png"))).id).isGreaterThan(0L)
    }

    @Test
    fun `the same file recorded twice is one entry`() = runTest {
        // A generation writes its file and then records it. Killed between the two, the
        // retry writes the same path again; two rows would be the same picture twice, and
        // deleting one of them would leave the other pointing at nothing.
        val file = made("one.png")
        gallery.record(entry(file, at = 100))
        gallery.record(entry(file, at = 200))

        assertThat(gallery.count()).isEqualTo(1)
        // The newer account wins: the retry knows what actually happened.
        assertThat(gallery.all().single().createdAt).isEqualTo(200)
    }

    @Test
    fun `deleting takes the file with the row`() = runTest {
        val file = made("one.png")
        val saved = gallery.record(entry(file))

        gallery.delete(saved.id)

        assertThat(gallery.byId(saved.id)).isNull()
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun `deleting something already gone is not an error`() = runTest {
        val file = made("one.png")
        val saved = gallery.record(entry(file))
        file.delete()

        gallery.delete(saved.id)

        assertThat(gallery.byId(saved.id)).isNull()
    }

    @Test
    fun `deleting an id nobody has is not an error`() = runTest {
        gallery.delete(404)

        assertThat(gallery.count()).isEqualTo(0)
    }

    @Test
    fun `a row whose file has gone is dropped rather than shown`() = runTest {
        // Android may clear this storage, the user may clear it from Settings, and a restore
        // can repopulate it unevenly. None of those tell the database anything.
        val kept = made("kept.png")
        val vanished = made("gone.png")
        gallery.record(entry(kept))
        gallery.record(entry(vanished))
        vanished.delete()

        assertThat(gallery.prune()).isEqualTo(1)
        assertThat(gallery.all().map { it.artifact.path }).containsExactly(kept.absolutePath)
    }

    @Test
    fun `pruning a gallery that is whole changes nothing`() = runTest {
        gallery.record(entry(made("one.png")))

        assertThat(gallery.prune()).isEqualTo(0)
        assertThat(gallery.count()).isEqualTo(1)
    }

    @Test
    fun `a favourite stays marked`() = runTest {
        val saved = gallery.record(entry(made("one.png")))

        gallery.setFavourite(saved.id, true)
        assertThat(gallery.byId(saved.id)?.isFavourite).isTrue()

        gallery.setFavourite(saved.id, false)
        assertThat(gallery.byId(saved.id)?.isFavourite).isFalse()
    }

    @Test
    fun `the total is what the rows say rather than a walk of the disk`() = runTest {
        gallery.record(entry(made("one.png", bytes = 100)))
        gallery.record(entry(made("two.png", bytes = 250)))

        assertThat(gallery.totalBytes()).isEqualTo(350)
    }

    @Test
    fun `an empty gallery totals nothing rather than failing to total`() = runTest {
        assertThat(gallery.totalBytes()).isEqualTo(0)
    }

    @Test
    fun `a speech entry keeps its duration and no size`() = runTest {
        val file = made("one.wav")
        val spoken = entry(file, task = GenerationTask.SPEECH)
            .copy(durationMillis = 4_200, width = null, height = null)

        val read = gallery.byId(gallery.record(spoken).id)

        assertThat(read?.modality).isEqualTo(GenerationTask.SPEECH)
        assertThat(read?.durationMillis).isEqualTo(4_200)
        assertThat(read?.width).isNull()
    }

    @Test
    fun `a file outside the generated folder is never deleted`() = runTest {
        // The path in a row is data. It arrived from a database that a restore, a migration
        // or another build of this app could have written, and none of those is a reason to
        // delete something else on the device.
        val elsewhere = File(context.filesDir, "not-ours.png").apply { writeBytes(ByteArray(4)) }

        assertThat(store.remove(elsewhere.absolutePath)).isFalse()
        assertThat(elsewhere.exists()).isTrue()
        elsewhere.delete()
    }

    @Test
    fun `a path that spells its way out of the folder is never deleted`() = runTest {
        val elsewhere = File(context.filesDir, "not-ours.png").apply { writeBytes(ByteArray(4)) }
        val spelled = File(store.directory, "../not-ours.png").path

        assertThat(store.remove(spelled)).isFalse()
        assertThat(elsewhere.exists()).isTrue()
        elsewhere.delete()
    }

    @Test
    fun `deleting a row that names a file elsewhere still forgets the row`() = runTest {
        // The row is ours to remove whatever it points at. Refusing the whole delete
        // because the file is not ours would leave an entry nobody could get rid of.
        val elsewhere = File(context.filesDir, "not-ours.png").apply { writeBytes(ByteArray(4)) }
        val saved = gallery.record(
            entry(made("placeholder.png")).copy(
                artifact = Artifact(elsewhere.absolutePath, "image/png"),
            ),
        )

        gallery.delete(saved.id)

        assertThat(gallery.byId(saved.id)).isNull()
        assertThat(elsewhere.exists()).isTrue()
        elsewhere.delete()
    }
}
