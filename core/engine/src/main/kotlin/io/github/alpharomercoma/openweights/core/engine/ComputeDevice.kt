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

package io.github.alpharomercoma.openweights.core.engine

/** What kind of silicon a compute device is. Ordinals mirror `ggml_backend_dev_type`. */
enum class ComputeDeviceKind {
    /** Order matches ggml_backend_dev_type, so the ordinal is the mapping. */
    CPU,
    GPU,
    INTEGRATED_GPU,
    ACCELERATOR,
    OTHER,
    ;

    companion object {
        fun fromNative(type: Int): ComputeDeviceKind = entries.getOrElse(type) { OTHER }
    }
}

/**
 * A device the engine can run layers on.
 *
 * Enumerated from the backends actually loaded on this phone rather than from a table of
 * chip names, so the list is the truth for the hardware in the user's hand.
 */
data class ComputeDevice(
    val id: String,
    val description: String,
    val kind: ComputeDeviceKind,
    val totalMemoryBytes: Long,
) {
    /** GPU offload is only meaningful on devices that are not the CPU. */
    val supportsOffload: Boolean get() = kind != ComputeDeviceKind.CPU

    internal companion object {
        /** Fields per device in the flattened array the JNI layer returns. */
        const val FIELDS_PER_DEVICE = 4

        fun parse(flattened: Array<String>): List<ComputeDevice> =
            flattened.toList().chunked(FIELDS_PER_DEVICE)
                .filter { it.size == FIELDS_PER_DEVICE }
                .map { fields ->
                    ComputeDevice(
                        id = fields[0],
                        description = fields[1],
                        kind = ComputeDeviceKind.fromNative(fields[2].toIntOrNull() ?: -1),
                        totalMemoryBytes = fields[3].toLongOrNull() ?: 0L,
                    )
                }
    }
}
