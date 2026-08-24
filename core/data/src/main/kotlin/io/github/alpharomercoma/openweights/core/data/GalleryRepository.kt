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

import android.util.Log
import io.github.alpharomercoma.openweights.core.data.db.GalleryEntity
import io.github.alpharomercoma.openweights.core.data.db.OpenWeightsDatabase
import io.github.alpharomercoma.openweights.core.generation.Artifact
import io.github.alpharomercoma.openweights.core.generation.GalleryEntry
import io.github.alpharomercoma.openweights.core.generation.GenerationTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the phone made, and the files behind it.
 *
 * The row and the file are one thing here, which is the whole reason this is a repository
 * rather than a DAO used directly. A row whose file has gone is a broken thumbnail in a
 * grid; a file whose row has gone is storage nobody can see and nobody can reclaim. Both
 * are reachable, because writing a file and recording it cannot be one atomic act, so this
 * owns both ends and [prune] closes the gap either of them can leave.
 *
 * Deletion only ever touches files inside [directory]. The path in a row is data, and a row
 * that somehow named something else, a bad migration, a restored backup from another build,
 * must not be able to talk this into deleting it.
 */
@Singleton
class GalleryRepository @Inject constructor(
    private val database: OpenWeightsDatabase,
    private val store: GeneratedOutputStore,
) {
    /**
     * Everything, newest first, unsorted and unfiltered beyond that.
     *
     * Sorting and filtering happen above this in shared code, so an iOS gallery cannot
     * quietly disagree with an Android one about what a filter means. A few hundred rows is
     * nothing to sort in memory and one rule beats two.
     */
    fun observeAll(): Flow<List<GalleryEntry>> =
        database.gallery().observeAll().map { rows -> rows.map { it.asEntry() } }

    suspend fun all(): List<GalleryEntry> = database.gallery().all().map { it.asEntry() }

    suspend fun byId(id: Long): GalleryEntry? = database.gallery().byId(id)?.asEntry()

    /** Records one finished output and returns it with the id it was given. */
    suspend fun record(entry: GalleryEntry): GalleryEntry {
        val id = database.gallery().insert(entry.asEntity())
        return entry.copy(id = id)
    }

    suspend fun setFavourite(id: Long, favourite: Boolean) =
        database.gallery().setFavourite(id, favourite)

    /**
     * Forgets one output and removes the file behind it.
     *
     * The row goes first. If the order were the other way and the row write failed, the
     * gallery would keep showing something that is no longer there; this way the worst case
     * is a file nobody can see, which [prune] and the storage total both already account
     * for, and which costs the user nothing they can notice.
     */
    suspend fun delete(id: Long) {
        val row = database.gallery().byId(id) ?: return
        database.gallery().delete(id)
        store.remove(row.path)
    }

    /**
     * Drops rows whose file is no longer there, and returns how many went.
     *
     * The files live in the app's own storage, which Android may clear, which a user may
     * clear from Settings, and which a restore from backup can repopulate unevenly. None of
     * those tell the database anything. Without this the grid fills with entries that open
     * onto nothing.
     */
    suspend fun prune(): Int {
        val missing = database.gallery().all().filterNot { store.exists(it.path) }
        missing.forEach { database.gallery().delete(it.id) }
        if (missing.isNotEmpty()) {
            Log.i("OpenWeights", "dropped ${missing.size} gallery rows with no file behind them")
        }
        return missing.size
    }

    /** What the gallery is costing, measured from the rows rather than by walking the disk. */
    suspend fun totalBytes(): Long = database.gallery().totalBytes()

    suspend fun count(): Int = database.gallery().count()
}

/**
 * Where generated files live, and the only place this app will delete one.
 *
 * Its own directory rather than the attachments folder. An attachment is something the user
 * brought in and a generated file is something the app made, they are cleaned up on
 * different occasions, and the delete rule below is only safe while one directory has one
 * meaning.
 */
@Singleton
class GeneratedOutputStore @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) {
    /** Created on demand, because most installs never generate anything. */
    val directory: File get() = File(context.filesDir, "generated").apply { mkdirs() }

    fun exists(path: String): Boolean = File(path).isFile

    /**
     * Deletes a generated file, and refuses anything that is not one.
     *
     * Compared on the resolved parent rather than on the string, so a path with `..` in it
     * cannot spell its way into looking like a child. A row is data; it arrived from a
     * database that a restore, a migration or another build of this app could have written,
     * and none of those is a reason to delete a file somewhere else on the device.
     */
    fun remove(path: String): Boolean {
        val file = File(path).canonicalFile
        if (file.parentFile != directory.canonicalFile) {
            Log.w("OpenWeights", "refused to delete ${file.name}: not a generated file")
            return false
        }
        return file.delete()
    }
}

private fun GalleryEntity.asEntry() = GalleryEntry(
    id = id,
    artifact = Artifact(path = path, mediaType = mediaType),
    // A modality this build does not know is a row from a newer build, which a downgrade or
    // a restored backup can produce. Read as an image, because that is what the grid can
    // show, rather than crashing the screen that was opened to look at everything else.
    modality = GenerationTask.entries.firstOrNull { it.name == modality } ?: GenerationTask.IMAGE,
    prompt = prompt,
    negativePrompt = negativePrompt,
    bundleId = bundleId,
    bundleName = bundleName,
    seed = seed,
    createdAt = createdAt,
    totalMillis = totalMillis,
    backend = backend,
    width = width,
    height = height,
    durationMillis = durationMillis,
    sizeBytes = sizeBytes,
    isFavourite = isFavourite,
)

private fun GalleryEntry.asEntity() = GalleryEntity(
    id = id,
    path = artifact.path,
    mediaType = artifact.mediaType,
    modality = modality.name,
    prompt = prompt,
    negativePrompt = negativePrompt,
    bundleId = bundleId,
    bundleName = bundleName,
    seed = seed,
    createdAt = createdAt,
    totalMillis = totalMillis,
    backend = backend,
    width = width,
    height = height,
    durationMillis = durationMillis,
    sizeBytes = sizeBytes,
    isFavourite = isFavourite,
)
