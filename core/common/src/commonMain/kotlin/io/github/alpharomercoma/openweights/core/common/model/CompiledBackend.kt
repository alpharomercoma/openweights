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
 * The silicon a compiled model was built for, which is decided when it is exported.
 *
 * Not a setting. A `.pte` holds delegate identifiers and delegate-call instructions, and
 * loading resolves those exact identifiers — so which processor runs it was fixed on
 * somebody's desktop before it was published, and no runtime switch can move it. An
 * XNNPACK export stays on the CPU even in a build that ships the Vulkan runtime; running
 * it on the GPU means a different file, exported with a different partitioner.
 *
 * This matters beyond labelling. A model whose backend is not linked into the app **fails
 * to load** — the runtime reports the backend is not registered or not available, and
 * there is no fallback to the CPU. Without checking first, a user downloads a gigabyte and
 * is told at the end that it cannot open.
 */
enum class CompiledBackend(val processor: Processor) {
    /** CPU, through XNNPACK. What the published `executorch-android` artifact carries. */
    XNNPACK(Processor.CPU),

    /** GPU, through Vulkan. A separate artifact and a separately exported model. */
    VULKAN(Processor.GPU),

    /** Qualcomm's NPU, through QNN. Qualcomm devices only. */
    QNN(Processor.NPU),

    /** MediaTek's NPU, through NeuroPilot. No published artifact; a source build only. */
    NEUROPILOT(Processor.NPU),

    /** The name said nothing, so nothing is claimed. */
    UNKNOWN(Processor.CPU),
    ;

    /** What kind of silicon this delegate runs on. */
    enum class Processor { CPU, GPU, NPU }

    companion object {
        /**
         * The backend named in [text], which is a file or repository name.
         *
         * Read from the name because a `.pte` carries no metadata the app can inspect and
         * the runtime has no API that reports which delegates a loaded model contains —
         * `getRegisteredBackends` says what the *runtime* has, not what the *file* uses.
         * Publishers do put it in the name (`…-ExecuTorch-XNNPACK`, `…-qnn-executorch`),
         * so that is the only signal available short of downloading and parsing the file.
         *
         * [UNKNOWN] when the name is silent, and callers treat that as "worth trying"
         * rather than "broken": most published exports are XNNPACK, and refusing every
         * unlabelled model would hide almost all of them.
         */
        fun of(text: String): CompiledBackend {
            val name = text.lowercase()
            return when {
                "xnnpack" in name -> XNNPACK
                "vulkan" in name -> VULKAN
                "qnn" in name || "qualcomm" in name || "htp" in name -> QNN
                "neuropilot" in name || "mediatek" in name || "mtk" in name -> NEUROPILOT
                else -> UNKNOWN
            }
        }
    }
}
