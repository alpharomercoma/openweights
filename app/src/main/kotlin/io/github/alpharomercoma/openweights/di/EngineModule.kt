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

package io.github.alpharomercoma.openweights.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.alpharomercoma.openweights.core.common.context.CompactionPolicy
import io.github.alpharomercoma.openweights.core.common.model.ModelFormat
import io.github.alpharomercoma.openweights.core.engine.InferenceEngine
import io.github.alpharomercoma.openweights.core.engine.LlamaCppEngine
import io.github.alpharomercoma.openweights.core.engine.RoutingInferenceEngine
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object EngineModule {
    /**
     * One engine for the whole process: a loaded model is hundreds of megabytes of
     * resident memory, so there must never be two.
     *
     * What callers get is a router rather than a backend. Which runtime reads a model is a
     * property of the file — GGUF for llama.cpp, `.pte` for ExecuTorch — and answering that
     * here keeps it out of the five places that ask the engine for a reply. The router
     * builds a backend the first time a model needs it and holds weights in only one at a
     * time, so registering a second entry costs a phone that never opens one nothing.
     */
    @Provides
    @Singleton
    fun provideInferenceEngine(): InferenceEngine = RoutingInferenceEngine(
        backends = mapOf(
            ModelFormat.GGUF to { LlamaCppEngine() },
        ),
    )

    /** Defaults are tuned for phone-sized context windows; see the policy's documentation. */
    @Provides
    fun provideCompactionPolicy(): CompactionPolicy = CompactionPolicy()
}
