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

package io.github.alpharomercoma.openweights.core.hub

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The window each export in a publisher's `config.json` was compiled with.
 *
 * Software Mansion publishes one of these beside every `.pte`, following their
 * react-native-executorch spec: a `variants` list, each naming its `file` and repeating the
 * export's constant methods under `methods`. `get_max_context_len` is the window the runtime
 * enforces, which is why it is read first; `get_max_seq_len` is the longest single prompt
 * and is smaller on the Llama exports, so it is only a fallback; and an export that states
 * neither still declares the shape of its `forward` input, whose second dimension is the
 * window less one for a static shape or its `max` for a dynamic one.
 *
 * A `config.json` that is a transformers config rather than the spec has no `variants`,
 * and yields nothing rather than a guess: `max_position_embeddings` there is what the
 * model was trained to, not what was exported.
 */
object ExportConfig {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** File name (as published, without its folder) to window in tokens. */
    fun windowsIn(configJson: String): Map<String, Int> {
        val root = runCatching { json.parseToJsonElement(configJson).jsonObject }.getOrNull()
            ?: return emptyMap()
        val variants = (root["variants"] as? JsonArray) ?: return emptyMap()
        return variants.mapNotNull { variant ->
            val entry = variant as? JsonObject ?: return@mapNotNull null
            val file = (entry["file"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val methods = entry["methods"] as? JsonObject ?: return@mapNotNull null
            val window = methods.int("get_max_context_len")
                ?: methods.int("get_max_seq_len")
                ?: methods.forwardWindow()
                ?: return@mapNotNull null
            file to window
        }.toMap()
    }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }

    /** The `forward` input's token dimension: `[1, 2047]` static, or `[1, {min, max}]`. */
    private fun JsonObject.forwardWindow(): Int? {
        val inputs = (this["forward"] as? JsonObject)?.get("inputs") as? JsonArray ?: return null
        val shape = (inputs.firstOrNull() as? JsonObject)?.get("shape")?.jsonArray ?: return null
        val tokens = shape.getOrNull(1) ?: return null
        return when (tokens) {
            is JsonPrimitive -> tokens.intOrNull?.plus(1)
            is JsonObject -> tokens["max"]?.jsonPrimitive?.intOrNull
            else -> null
        }?.takeIf { it > 0 }
    }
}
