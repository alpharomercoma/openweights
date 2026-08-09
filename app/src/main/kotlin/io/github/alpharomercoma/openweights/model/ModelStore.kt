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
     * A projector is a separate GGUF holding the vision or audio encoder, published
     * alongside the model as `mmproj-<model>-<quant>.gguf`. Matching is by the model's
     * identity with its quantization suffix removed, because the two files are usually
     * quantized differently — an F16 projector paired with a Q4 model is the normal case.
     */
    fun projectorFor(model: File): File? {
        val identity = model.nameWithoutExtension.modelIdentity()
        if (identity.isEmpty()) return null
        return ggufFiles().firstOrNull { candidate ->
            candidate.isProjector && candidate.nameWithoutExtension.contains(identity, true)
        }
    }

    private fun ggufFiles(): List<File> =
        directory.listFiles { file -> file.isFile && file.name.endsWith(GGUF_EXTENSION) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    private val File.isProjector: Boolean get() = name.startsWith(PROJECTOR_PREFIX, true)

    /**
     * A model name with its quantization suffix removed.
     *
     * `LFM2.5-VL-1.6B-Q4_K_M` becomes `LFM2.5-VL-1.6B`, which is what the projector's own
     * name contains.
     */
    private fun String.modelIdentity(): String {
        val quantization = QUANTIZATION_SUFFIX.find(this) ?: return this
        return substring(0, quantization.range.first)
    }

    private companion object {
        const val MODELS_DIRECTORY = "models"
        const val GGUF_EXTENSION = ".gguf"
        const val PROJECTOR_PREFIX = "mmproj"

        /** `-Q4_K_M`, `-F16`, `-IQ3_XXS` and the rest of the GGUF quantization vocabulary. */
        val QUANTIZATION_SUFFIX = Regex("""-(I?Q\d[\w_]*|BF?16|F32)$""", RegexOption.IGNORE_CASE)
    }
}
