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

package io.github.alpharomercoma.openweights.core.common.model

/**
 * What an ExecuTorch model is called once it is on the phone.
 *
 * Renaming on the way in, the way projectors already are, and for a sharper reason. Every
 * `.pte` repository on the Hub names its weights `model.pte`, so three installed models
 * would be three files called `model.pte` — and the family a `.pte` belongs to can only be
 * read off its name, since unlike a GGUF it carries no metadata the app can inspect.
 * `PromptTemplates` would have nothing to work with. The repository id is the only place
 * that information exists, so it becomes the file name.
 */
object ExecuTorchFileName {

    /** Punctuation a model name is allowed to keep. Everything else becomes a dash. */
    private const val KEPT_PUNCTUATION = "-_."

    /** A tokenizer saved beside the model it was exported with. */
    const val TOKENIZER_SUFFIX = ".tokenizer.json"

    /** The names a repository publishes its tokenizer under, best first. */
    val REMOTE_TOKENIZERS: List<String> = listOf("tokenizer.json", "tokenizer.model")

    /**
     * What to save the weights of [repoId] as: the repository's own name, plus `.pte`.
     *
     * `larryliu0820/Qwen3-1.7B-INT8-INT4-ExecuTorch-XNNPACK` becomes
     * `Qwen3-1.7B-INT8-INT4-ExecuTorch-XNNPACK.pte`, which still says "Qwen3" — and that
     * is the whole point, because that is how the prompt template is chosen.
     *
     * [weightsPath] joins in when the file inside the repository has a name of its own.
     * The official repositories publish exactly one `model.pte`, and for those the
     * repository name alone is the file name — which is also what every already-installed
     * model was saved as. But a repository like software-mansion's publishes three sizes
     * of one family (`1_7b/xnnpack/smollm2_1_7b_xnnpack_8da4w.pte`), and naming them all
     * after the repository would make the second download silently overwrite the first.
     */
    fun modelNameFor(repoId: String, weightsPath: String = ""): String {
        val repo = repoId.substringAfterLast('/').sanitized()
        val file = weightsPath.substringAfterLast('/')
        val stem = if (file.endsWith(ModelFormat.PTE.suffix, ignoreCase = true)) {
            file.dropLast(ModelFormat.PTE.suffix.length)
        } else {
            file
        }
        // A generic `model.pte` says nothing, but its folder does: a repository holding
        // `1b/model.pte` and `3b/model.pte` would otherwise install both under one name
        // and the second download would silently replace the first (codex QA). The root
        // `model.pte` keeps the bare repository name, which is what every model already
        // installed was saved as.
        val directory = weightsPath.substringBeforeLast('/', "")
            .takeIf { it.isNotEmpty() }
            ?.sanitized()
        val distinct = stem.sanitized().takeIf { it.isNotEmpty() && !it.equals("model", true) }
            ?: directory
        return when (distinct) {
            null -> repo + ModelFormat.PTE.suffix
            else -> "$repo-$distinct" + ModelFormat.PTE.suffix
        }
    }

    /** Where the tokenizer for [modelFileName] lives: beside it, under the same stem. */
    fun tokenizerNameFor(modelFileName: String): String =
        modelFileName.substringBeforeLast('.') + TOKENIZER_SUFFIX

    /** True for a file in a repository that is the tokenizer we need. */
    fun isRemoteTokenizer(path: String): Boolean = path.substringAfterLast('/') in REMOTE_TOKENIZERS

    /**
     * A repository name reduced to something safe to use as a file name.
     *
     * A repository is somebody else's data and its name reaches a path here, so anything
     * that is not plainly part of a name becomes a dash — which covers the separators of
     * this system and of others.
     *
     * A dot is kept, and that is not an oversight. Model names are full of them: `1.7B`,
     * `Qwen2.5`, `LFM2.5`. Dropping them rewrote `Qwen3-1.7B` as `Qwen3-1-7B`, which is a
     * different model as far as anybody reading the list is concerned. What makes keeping
     * them safe is trimming them from the ends, so a repository called `..` reduces to
     * nothing and falls back to a name rather than reaching a parent directory.
     */
    private fun String.sanitized(): String =
        map { if (it.isLetterOrDigit() || it in KEPT_PUNCTUATION) it else '-' }
            .joinToString("")
            .trim('-', '.')
            .ifEmpty { "model" }
}
