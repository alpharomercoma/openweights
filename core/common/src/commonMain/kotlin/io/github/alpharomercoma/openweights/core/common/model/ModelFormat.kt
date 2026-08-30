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
 * How a model's weights are packaged, which is what decides the runtime that can read them.
 *
 * The two are the same question on this device. GGUF is self-describing and llama.cpp reads
 * it directly, so any repo a user finds works. A `.pte` is compiled ahead of time on a
 * desktop against one backend and, for an NPU, one SoC, so it can only have come from a
 * build we ran — but it is the only way to reach an accelerator that has no ggml backend.
 *
 * Recorded per file rather than per install because a phone holds both at once, and which
 * engine to hand a file to is answered by looking at the file.
 */
enum class ModelFormat(val suffix: String) {
    /** llama.cpp. Any GGUF on the Hub, downloaded and run as found. */
    GGUF(".gguf"),

    /** ExecuTorch. Exported ahead of time; see `docs/research/executorch.md`. */
    PTE(".pte"),
    ;

    companion object {
        /** The format [fileName] is in, or null when it is not a model this app can run. */
        fun of(fileName: String): ModelFormat? =
            entries.firstOrNull { fileName.endsWith(it.suffix, ignoreCase = true) }

        /** Every suffix a model file can carry, for directory scans. */
        val suffixes: List<String> = entries.map { it.suffix }
    }
}
