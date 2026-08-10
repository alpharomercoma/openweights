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

package io.github.alpharomercoma.openweights.model

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.alpharomercoma.openweights.core.common.model.GgufFileName
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where downloaded models live on disk.
 *
 * App-specific external storage: large enough for multi-gigabyte weights, needs no runtime
 * permission, is removed when the app is uninstalled, and stays reachable over ADB during
 * development. Phase 2 replaces the folder scan with a real catalog backed by the database.
 */
@Singleton
class ModelStore @Inject constructor(@ApplicationContext private val context: Context) {
    val directory: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, MODELS_DIRECTORY)
            .apply { mkdirs() }

    /** Every GGUF currently on disk, newest first. Projectors are not models. */
    fun availableModels(): List<File> = ggufFiles().filterNot { it.isProjector }

    /**
     * The model to open with: the one last chosen, or any that is present.
     *
     * Falling back to whatever came first in the directory listing was what it did before,
     * and on a phone with three models installed that meant choosing a model, closing the
     * app, and finding a different one loaded. Which model is loaded decides whether tools
     * work at all, so getting this wrong looked like the agent being broken.
     */
    fun preferredModel(): File? {
        val installed = availableModels()
        val chosen = store.getString(KEY_LAST_MODEL, null)
        return installed.firstOrNull { it.name == chosen } ?: installed.firstOrNull()
    }

    /** Records the choice, so the next launch opens the same model. */
    fun rememberChoice(model: File) {
        store.edit().putString(KEY_LAST_MODEL, model.name).apply()
    }

    private val store =
        context.getSharedPreferences("model_store", Context.MODE_PRIVATE)

    /**
     * The multimodal projector that belongs to [model], if it has been downloaded.
     *
     * Downloads are saved under a name derived from the model file, so the normal case is
     * an exact lookup. The convention match behind it covers projectors put here by hand
     * or by an older build, where the name is whatever the publisher chose.
     */
    fun projectorFor(model: File): File? {
        val paired = File(directory, GgufFileName.projectorNameFor(model.name))
        if (paired.isFile) return paired

        val identity = GgufFileName.modelIdentity(model.name)
        if (identity.isEmpty()) return null
        return ggufFiles().firstOrNull { candidate ->
            candidate.isProjector &&
                GgufFileName.modelIdentity(candidate.name).equals(identity, ignoreCase = true)
        }
    }

    /** Where a projector downloaded for [modelFileName] is saved. */
    fun projectorDestination(modelFileName: String): File =
        File(directory, GgufFileName.projectorNameFor(modelFileName))

    private fun ggufFiles(): List<File> =
        directory.listFiles { file -> file.isFile && file.name.endsWith(GGUF_EXTENSION) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    private val File.isProjector: Boolean get() = GgufFileName.isProjector(name)

    private companion object {
        const val KEY_LAST_MODEL = "last_model"
        const val MODELS_DIRECTORY = "models"
        const val GGUF_EXTENSION = GgufFileName.GGUF_SUFFIX
    }
}
