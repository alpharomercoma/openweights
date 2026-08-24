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
 * Something the model can ask the app to do.
 *
 * Described with a JSON Schema because that is what instruction-tuned models were trained
 * on. The engine renders it into whichever tool syntax the loaded model actually uses
 * Hermes, Llama 3.x, Functionary and the rest all differ, so callers describe the tool
 * once and it works across models.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    /** JSON Schema for the arguments object. */
    val parametersJson: String,
) {
    init {
        require(name.isNotBlank()) { "a tool needs a name" }
        require(parametersJson.isNotBlank()) { "a tool needs a parameter schema" }
    }
}

/** A call the model asked for, not yet executed. */
data class ToolCall(
    val id: String,
    val name: String,
    /** Arguments as JSON, exactly as the model produced them. */
    val argumentsJson: String,
)
