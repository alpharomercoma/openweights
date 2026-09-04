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
import io.github.alpharomercoma.openweights.core.common.model.ExecuTorchFileName
import io.github.alpharomercoma.openweights.core.common.model.GgufFileName
import io.github.alpharomercoma.openweights.core.common.model.ModelFormat
import io.github.alpharomercoma.openweights.core.engine.ExecuTorchSupport
import io.github.alpharomercoma.openweights.core.hub.DOWNLOAD_PARTIAL_SUFFIX
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

    /**
     * Reclaims space held by a generation bundle from before that feature was removed.
     *
     * A GGUF model is always a single file here, so a directory in this folder could only
     * be a leftover multi-file bundle (Sana, Stable Diffusion) — up to 1.6 GB each, and with
     * the screen that once listed and deleted them gone, otherwise unreachable without
     * clearing app data. Run once per process: real work only the first time a phone that
     * had one installed opens this build, a no-op every time after.
     */
    fun deleteLegacyGenerationBundles() {
        directory.listFiles { file -> file.isDirectory }?.forEach { it.deleteRecursively() }
    }

    /**
     * Every model currently on disk, newest first, whichever runtime reads it.
     *
     * Projectors are not models: they are half of one, and a user offered `mmproj-...gguf`
     * as something to load would get a failure they could not act on.
     */
    fun availableModels(): List<File> = modelFiles().filterNot { it.isProjector }

    /**
     * True while any download is still writing into this directory.
     *
     * Asked before opening a model nobody has asked for yet. Two reasons, and the second
     * is the one that would otherwise be a bug: a multimodal model and its projector are
     * two separate downloads, and the weights usually land first. Opening them the moment
     * they arrive would load the model without the projector that has not finished, and
     * the chat screen — which only loads a model when it finds none — would then leave it
     * that way, so the pictures would silently stop working. The first reason is plainer:
     * a load competes with the transfer for the same flash and the same memory.
     *
     * Read from the `.part` files [ModelDownloader] leaves behind rather than from
     * WorkManager, because that is a fact about this directory and needs no scheduler to
     * be asked about it. A `.part` orphaned by a cancelled download defers the warm and
     * costs nothing else: the chat screen still opens the model the moment somebody asks.
     */
    fun downloadsInFlight(): Boolean =
        directory.listFiles { file -> file.isFile && file.name.endsWith(DOWNLOAD_PARTIAL_SUFFIX) }
            ?.isNotEmpty() == true

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

    /**
     * True once this model has been caught reasoning after being told not to.
     *
     * The template is asked at load whether being told not to think changes the prompt, and
     * a template that ignores the flag never gets a switch. What that cannot catch is a
     * template that does branch on it and weights that pay no attention, because the only
     * way to know is to ask and read the answer.
     *
     * So the answer is read. A reply that comes back with reasoning in it when reasoning
     * was switched off is proof, and it is remembered against the file, so the switch costs
     * one wrong reply once rather than every time the model is loaded.
     */
    fun ignoresThinkingSwitch(model: String): Boolean =
        store.getBoolean(KEY_IGNORES_THINKING + model, false)

    /**
     * Remembers that it does.
     *
     * One way on purpose. One reply that arrives without reasoning proves nothing: a model
     * that usually obeys and sometimes does not is still a switch that does not work, while
     * a single reply that ignored it is conclusive.
     */
    fun rememberIgnoresThinkingSwitch(model: String) {
        store.edit().putBoolean(KEY_IGNORES_THINKING + model, true).apply()
    }

    /**
     * Who published the model in this file, as the Hub repository's owner.
     *
     * Recorded when a download starts, because it is the only moment the app knows: a
     * finished download is a `.gguf` in a folder and the filename says nothing about where
     * it came from. Null for a file put here by hand or downloaded before this was kept,
     * which is a real case and is why every reader has to cope with not knowing.
     */
    fun publisherOf(model: String): String? =
        store.getString(KEY_PUBLISHER + model, null)?.takeIf { it.isNotBlank() }

    /** Remembers it, at the one moment it is known. */
    fun rememberPublisher(model: String, publisher: String) {
        if (publisher.isBlank()) return
        store.edit().putString(KEY_PUBLISHER + model, publisher).apply()
    }

    /** Forgets it along with the file, so a redownload is not answered from a stale note. */
    fun forgetPublisher(model: String) {
        store.edit().remove(KEY_PUBLISHER + model).apply()
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

    /**
     * Anything on disk a runtime *in this build* can open.
     *
     * The flavour is part of the question. A `.pte` sideloaded onto a standard build has
     * nothing to run it, and listing it would offer the user a model that can only fail;
     * it is left out of the list rather than shown and then refused.
     */
    private fun modelFiles(): List<File> =
        directory.listFiles { file -> file.isFile && file.name.isRunnableHere() }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    /**
     * Whether this build can open a file of this name, right now.
     *
     * Two conditions, and the second is what keeps a part-finished install off the screen.
     * A `.pte` is useless without the tokenizer it was exported against, and the two arrive
     * as separate downloads, so a model listed the moment its weights land is a model the
     * user can tap and be refused by. Listing it only once both files are present means the
     * row appears exactly when it starts working, and a failed tokenizer download shows up
     * as a model that never arrived rather than one that is broken.
     */
    private fun String.isRunnableHere(): Boolean = when (ModelFormat.of(this)) {
        ModelFormat.GGUF -> true
        ModelFormat.PTE -> ExecuTorchSupport.AVAILABLE && tokenizerFor(this).isFile
        null -> false
    }

    /** Where the tokenizer for [modelFileName] belongs, whether or not it has arrived. */
    fun tokenizerFor(modelFileName: String): File =
        File(directory, ExecuTorchFileName.tokenizerNameFor(modelFileName))

    /**
     * Where a repository's compiled weights are saved.
     *
     * Named after the repository rather than after the file inside it, because every `.pte`
     * repository on the Hub calls its weights `model.pte`. Three installs would collide on
     * one name, and worse, the family a `.pte` belongs to can only be read off its name —
     * it carries no metadata the way a GGUF does — so `model.pte` would leave the prompt
     * template unchoosable. See `ExecuTorchFileName`.
     */
    fun compiledDestination(repoId: String, weightsPath: String = ""): File =
        File(directory, ExecuTorchFileName.modelNameFor(repoId, weightsPath))

    /**
     * GGUFs only, for pairing a model with its projector.
     *
     * Deliberately narrower than [modelFiles]: a projector is always a GGUF, so widening
     * this would only add files that can never match.
     */
    private fun ggufFiles(): List<File> =
        directory.listFiles { file -> file.isFile && file.name.endsWith(GGUF_EXTENSION) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    private val File.isProjector: Boolean get() = GgufFileName.isProjector(name)

    private companion object {
        const val KEY_LAST_MODEL = "last_model"

        /** Prefix, because the answer is per model file rather than per install. */
        const val KEY_IGNORES_THINKING = "ignores_thinking:"

        /** Prefixed per file, the way the thinking note above is. */
        const val KEY_PUBLISHER = "publisher:"
        const val MODELS_DIRECTORY = "models"
        const val GGUF_EXTENSION = GgufFileName.GGUF_SUFFIX
    }
}
