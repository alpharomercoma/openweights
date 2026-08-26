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

package io.github.alpharomercoma.openweights.core.generation

/**
 * Known curated on-device generation bundles.
 */
object GenerationCatalog {
    val STABLE_DIFFUSION_1_5 = GenerationBundleSpec(
        id = "stable-diffusion-v1-5-mnn-opencl",
        repoId = "taobao-mnn/stable-diffusion-v1-5-mnn-opencl",
        displayName = "Stable Diffusion 1.5 (MNN OpenCL)",
        description = "On-device text-to-image diffusion model running on OpenCL GPU / NPU.",
        task = GenerationTask.IMAGE,
        runtime = GenerationRuntime.MNN,
        mnnModelType = 0, // STABLE_DIFFUSION_1_5
        directoryName = "stable-diffusion-v1-5-mnn-opencl",
        totalSizeBytes = 1_154_240_776L,
        files = listOf(
            BundleFileSpec(
                name = "text_encoder.mnn",
                remotePath = "text_encoder.mnn",
                sizeBytes = 249_944L,
                sha256 = "5713fa5c83aa446b5b9c28a48a90c647a5bababc5ee5f254cf72e7f479551036",
            ),
            BundleFileSpec(
                name = "text_encoder.mnn.weight",
                remotePath = "text_encoder.mnn.weight",
                sizeBytes = 238_120_368L,
                sha256 = "c245ac80dd8a72279976414435801d579a8dcf83aba84e121c4e4a0b74bbed3d",
            ),
            BundleFileSpec(
                name = "unet.mnn",
                remotePath = "unet.mnn",
                sizeBytes = 1_248_536L,
                sha256 = "0aaad66712a3f86ef7891392517a5d7471327574a6f4c6defcefc10ce5e06fee",
            ),
            BundleFileSpec(
                name = "unet.mnn.weight",
                remotePath = "unet.mnn.weight",
                sizeBytes = 863_262_988L,
                sha256 = "67049e0d6ce8cb34ab1cd78e58909427db1b57f9fd048e16f2f9c86c39f7479b",
            ),
            BundleFileSpec(
                name = "vae_decoder.mnn",
                remotePath = "vae_decoder.mnn",
                sizeBytes = 128_248L,
                sha256 = "fa6f34c9e77fb715f57d27d65c930455c2ba11b42f2b956da8dcadb2b4bf14b2",
            ),
            BundleFileSpec(
                name = "vae_decoder.mnn.weight",
                remotePath = "vae_decoder.mnn.weight",
                sizeBytes = 49_639_112L,
                sha256 = "20db884599922383eb168fd2fd018892a7741bb45f3ad7073d4cfaf7d75f2241",
            ),
            // taobao-mnn/stable-diffusion-v1-5-mnn-opencl (and every other backend variant of
            // this repo) genuinely does not publish tokenizer.mtok — confirmed with a direct
            // HTTP request, not assumed; every download of this bundle 404ed on this one file
            // and the UI reported "That model no longer exists on Hugging Face," which was
            // misleading since the rest of the repo is fine. The engine requires this exact
            // MNN tokenizer format (see stable_diffusion.cpp's MNN_DIFFUSION_WITH_LLM_TOKENIZER
            // path) and there's no way to derive it on-device from vocab.json/merges.txt alone,
            // so a known-good copy ships as an app asset instead of a doomed download.
            BundleFileSpec(
                "tokenizer.mtok",
                sizeBytes = 1_322_521L,
                sha256 = "8ec18545cf3d318325f43887984e2798e9dd69fdecc933b73ce519217afa93c3",
                assetPath = "generation-bundles/stable-diffusion-v1-5-tokenizer.mtok",
            ),
            BundleFileSpec("vocab.json", "vocab.json", 1_059_962L),
            BundleFileSpec("merges.txt", "merges.txt", 524_619L),
            BundleFileSpec("alphas.txt", "alphas.txt", 6_999L),
        ),
        quantization = "FP16 OpenCL",
        minimumFreeBytes = 2_000_000_000L,
        licence = "CreativeML OpenRAIL-M",
    )

    val SANA_EDIT_V2 = GenerationBundleSpec(
        id = "sana-edit-v2-mnn",
        repoId = "taobao-mnn/MNN-Sana-Edit-V2",
        displayName = "Sana Edit V2 (MNN)",
        description = "LLM-powered text-to-image and image editing with Qwen3-0.6B prompt encoder.",
        task = GenerationTask.IMAGE,
        runtime = GenerationRuntime.MNN,
        mnnModelType = 2, // SANA_DIFFUSION
        directoryName = "sana-edit-v2-mnn",
        totalSizeBytes = 1_601_624_654L,
        files = listOf(
            // config.json — MNN reads this to discover model paths within the bundle
            BundleFileSpec("config.json", "config.json", 810L),
            BundleFileSpec("connector.mnn", "connector.mnn", 99_096L),
            BundleFileSpec("connector.mnn.weight", "connector.mnn.weight", 76_268_760L,
                sha256 = "7128351d2de561932741f7f874b116ea3b4e5979296d8adf41472a93fcb889cd"),
            BundleFileSpec("projector.mnn", "projector.mnn", 2_416L),
            BundleFileSpec("projector.mnn.weight", "projector.mnn.weight", 2_387_206L,
                sha256 = "34b5afdb0c3b1fc815cdee7f3ed293e8d8f1f377328e5785cad2bd1768a843c3"),
            BundleFileSpec("transformer.mnn", "transformer.mnn", 1_454_264L,
                sha256 = "092dd75e8b8c12694ffe43476addcbde07fe7227774a1a40b19420f87b217386"),
            BundleFileSpec("transformer.mnn.weight", "transformer.mnn.weight", 884_435_680L,
                sha256 = "b3bab45fbabc8dabd05840b52ea3cd9bd3e54dd990e153ff6fbecd8b6c17f331"),
            BundleFileSpec("vae_decoder.mnn", "vae_decoder.mnn", 751_784L,
                sha256 = "9fbe51979b27339b7685cf88f1010a0ff3ab7ff1a7d873fba321eea94b762911"),
            BundleFileSpec("vae_decoder.mnn.weight", "vae_decoder.mnn.weight", 162_011_594L,
                sha256 = "a6ef7a13ba9af29754adf9b97651cb29a7eaee20b716c16dbe079f500d5eddae"),
            BundleFileSpec("vae_encoder.mnn", "vae_encoder.mnn", 761_568L,
                sha256 = "06da21081f8ee98792bd1838990068e7284351157cafbfa8793282b611eacb24"),
            BundleFileSpec("vae_encoder.mnn.weight", "vae_encoder.mnn.weight", 155_787_522L,
                sha256 = "b44ac00f4683697add9578ef4c0f561fb5753fe24a3f4525e7f492028409d05e"),
            // llm/ subdirectory — the Qwen3-0.6B prompt encoder
            BundleFileSpec("llm/llm.mnn", "llm/llm.mnn", 504_504L,
                sha256 = "a3e32dc50e8988e78d416031023345048f4b6cf152db021da6ee1de921d45096"),
            BundleFileSpec("llm/llm.mnn.weight", "llm/llm.mnn.weight", 373_018_866L,
                sha256 = "79db6ac8267ec6a7c9172a363112fa613c0cf17d6f46121d947a75c987ccf49a"),
            BundleFileSpec("llm/meta_queries.mnn", "llm/meta_queries.mnn", 1_048_824L,
                sha256 = "5e80d4e591af78cca31b6e4cf4ee4ead410e9d2f64ee34c73cf5b633def16e0c"),
            BundleFileSpec("llm/tokenizer.txt", "llm/tokenizer.txt", 3_193_562L),
            BundleFileSpec("llm/llm.mnn.json", "llm/llm.mnn.json", 1_006_495L),
            // Without this, the loader has no way to know this checkpoint's embedding table is
            // tied into llm.mnn.weight (its "tie_embeddings" field) rather than a separate file,
            // falls back to the wrong default of a standalone embeddings_bf16.bin -- which this
            // checkpoint doesn't ship -- and the LLM prompt encoder silently fails to load.
            BundleFileSpec("llm/llm_config.json", "llm/llm_config.json", 4_638L,
                sha256 = "2e45095efda4d17853d8b565f7f354210d3f14f97ac24b24a87a5ab771f5980a"),
            // SanaLlm's C++ constructor opens "<llm dir>/config.json" directly (not
            // llm.mnn.json or llm_config.json above) for its own runtime settings.
            BundleFileSpec("llm/config.json", "llm/config.json", 210L,
                sha256 = "c4bd25dbbc950feffccc3b154d634fdfbce96fbed453dd738bda4abfc763b73a"),
        ),
        quantization = "Q4_K (4-bit)",
        minimumFreeBytes = 3_500_000_000L,
        licence = "Apache 2.0",
    )

    val SUPERTONIC_TTS = GenerationBundleSpec(
        id = "supertonic-tts-mnn",
        repoId = "yunfengwang/supertonic-tts-mnn",
        displayName = "Supertonic Voice (MNN)",
        description = "High-fidelity neural text-to-speech voice synthesizer on-device.",
        task = GenerationTask.SPEECH,
        runtime = GenerationRuntime.MNN,
        directoryName = "supertonic-tts-mnn",
        totalSizeBytes = 133_215_022L,
        files = listOf(
            BundleFileSpec(
                name = "duration_predictor.mnn",
                remotePath = "mnn_models/fp16/duration_predictor.mnn",
                sizeBytes = 846_908L,
                sha256 = "f7ffb96a044afd430a564c0de062c45bd619760d744b1f7303bdf03ff325d989",
            ),
            BundleFileSpec(
                name = "text_encoder.mnn",
                remotePath = "mnn_models/fp16/text_encoder.mnn",
                sizeBytes = 13_883_664L,
                sha256 = "35f8ea632a11eaa991ca2956a2a8b092d43b0dcb02ef2c2f7604ac99eeed6e16",
            ),
            BundleFileSpec(
                name = "vector_estimator.mnn",
                remotePath = "mnn_models/fp16/vector_estimator.mnn",
                sizeBytes = 66_568_232L,
                sha256 = "fd7c25de7c44058629d7fd2b2e1f946dfd718180dcb6dbe23a85b382abec418c",
            ),
            BundleFileSpec(
                name = "vocoder.mnn",
                remotePath = "mnn_models/fp16/vocoder.mnn",
                sizeBytes = 50_803_764L,
                sha256 = "501fb8afb5038796a5485ad7f090ec4035c32c20e54c8de7cab724ab4a774a75",
            ),
            BundleFileSpec("tts.json", "mnn_models/tts.json", 8_645L),
            BundleFileSpec("unicode_indexer.json", "mnn_models/unicode_indexer.json", 262_134L),
            BundleFileSpec("voice_f1.json", "voice_styles/F1.json", 420_622L),
            BundleFileSpec("voice_m1.json", "voice_styles/M1.json", 421_053L),
        ),
        quantization = "FP16",
        minimumFreeBytes = 300_000_000L,
        licence = "OpenRAIL",
    )

    // SANA_EDIT_V2 was excluded from ALL for a while: it's an image-*editing* model (conditioned
    // primarily on a reference image), not a text-to-image one, and fed the all-zero reference
    // latent that pure text-to-image mode requires, it converges but produces content unrelated
    // to the prompt — confirmed against Alibaba's own reference usage of this exact checkpoint,
    // not a wiring bug on our side. Stable Diffusion 1.5 remains the app's text-to-image model.
    // Re-listed now that GenerateScreen has a reference-image picker gated on
    // ImageCapability.supportsImageEdit, so this bundle is only ever driven the way it was
    // trained to be.
    val ALL: List<GenerationBundleSpec> = listOf(STABLE_DIFFUSION_1_5, SANA_EDIT_V2, SUPERTONIC_TTS)

    val ALL_INCLUDING_UNLISTED: List<GenerationBundleSpec> =
        listOf(STABLE_DIFFUSION_1_5, SANA_EDIT_V2, SUPERTONIC_TTS)

    // Looked up by id/directory against the full set (including SANA_EDIT_V2), not just ALL, so a
    // bundle already downloaded before it was delisted still resolves, and tests can still find it.
    fun findById(id: String): GenerationBundleSpec? = ALL_INCLUDING_UNLISTED.firstOrNull { it.id == id }

    fun findByDirectory(dirName: String): GenerationBundleSpec? =
        ALL_INCLUDING_UNLISTED.firstOrNull { it.directoryName == dirName }
}

data class GenerationBundleSpec(
    val id: String,
    val repoId: String,
    val displayName: String,
    val description: String,
    val task: GenerationTask,
    val runtime: GenerationRuntime,
    /** MNN's DiffusionModelType enum value, or 0 when the runtime is not MNN. */
    val mnnModelType: Int = 0,
    val directoryName: String,
    val totalSizeBytes: Long,
    val files: List<BundleFileSpec>,
    val quantization: String,
    val minimumFreeBytes: Long,
    val licence: String,
)

data class BundleFileSpec(
    val name: String,
    val remotePath: String = name,
    val sizeBytes: Long = 0L,
    val sha256: String? = null,
    /**
     * When set, this file is copied from the app's own assets instead of downloaded from
     * [GenerationBundleSpec.repoId] — [remotePath] is then unused. For a file the repo doesn't
     * actually publish (confirmed by hitting its HTTP 404 for real, not assumed), shipping a
     * known-good copy in the APK is more honest than a download spec that always fails.
     */
    val assetPath: String? = null,
)
