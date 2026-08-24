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
            BundleFileSpec("vocab.json", "vocab.json", 1_059_962L),
            BundleFileSpec("merges.txt", "merges.txt", 524_619L),
            BundleFileSpec("alphas.txt", "alphas.txt", 6_999L),
        ),
        quantization = "FP16 OpenCL",
        minimumFreeBytes = 2_000_000_000L,
        licence = "CreativeML OpenRAIL-M",
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

    val ALL: List<GenerationBundleSpec> = listOf(STABLE_DIFFUSION_1_5, SUPERTONIC_TTS)

    fun findById(id: String): GenerationBundleSpec? = ALL.firstOrNull { it.id == id }

    fun findByDirectory(dirName: String): GenerationBundleSpec? =
        ALL.firstOrNull { it.directoryName == dirName }
}

data class GenerationBundleSpec(
    val id: String,
    val repoId: String,
    val displayName: String,
    val description: String,
    val task: GenerationTask,
    val runtime: GenerationRuntime,
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
)
