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

    fun firstAvailableModel(): File? = availableModels().firstOrNull()

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
        const val MODELS_DIRECTORY = "models"
        const val GGUF_EXTENSION = GgufFileName.GGUF_SUFFIX
    }
}
