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

package io.github.alpharomercoma.openweights.core.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.alpharomercoma.openweights.core.common.model.ModelLoadParams
import io.github.alpharomercoma.openweights.core.common.model.SamplerParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings saved against one model.
 *
 * Per-model rather than global because the right values genuinely differ: a 1 B model
 * needs a much more explicit system prompt than a 7 B one, model cards recommend
 * particular temperatures, and the context length a phone can afford depends on the file.
 */
@Serializable
data class ModelPreferences(
    val temperature: Float = SamplerParams.DEFAULT_TEMPERATURE,
    val topK: Int = SamplerParams.DEFAULT_TOP_K,
    val topP: Float = SamplerParams.DEFAULT_TOP_P,
    val minP: Float = SamplerParams.DEFAULT_MIN_P,
    val repeatPenalty: Float = SamplerParams.DEFAULT_REPEAT_PENALTY,
    val maxTokens: Int = 0,
    val contextLength: Int = ModelLoadParams.DEFAULT_CONTEXT_LENGTH,
    val systemPrompt: String = "",
) {
    fun toSamplerParams() = SamplerParams(
        temperature = temperature,
        topK = topK,
        topP = topP,
        minP = minP,
        repeatPenalty = repeatPenalty,
        maxTokens = maxTokens,
    )

    fun toLoadParams() = ModelLoadParams(contextLength = contextLength)
}

/**
 * Stores per-model settings.
 *
 * Keyed by model file name, which is what the user recognises and what survives the app
 * being reinstalled alongside a models folder.
 */
@Singleton
class ModelPreferencesRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun observe(modelName: String): Flow<ModelPreferences> =
        context.settingsDataStore.data.map { preferences ->
            preferences[key(modelName)]?.let { stored ->
                // A settings file written by an older build must not stop the model
                // loading; falling back to defaults is always safe.
                runCatching { json.decodeFromString<ModelPreferences>(stored) }.getOrNull()
            } ?: ModelPreferences()
        }

    suspend fun current(modelName: String): ModelPreferences = observe(modelName).first()

    suspend fun save(modelName: String, preferences: ModelPreferences) {
        context.settingsDataStore.edit { store ->
            store[key(modelName)] = json.encodeToString(preferences)
        }
    }

    suspend fun reset(modelName: String) {
        context.settingsDataStore.edit { store -> store.remove(key(modelName)) }
    }

    private fun key(modelName: String) = stringPreferencesKey("$PREFIX$modelName")

    private companion object {
        const val PREFIX = "model_prefs_"
    }
}
