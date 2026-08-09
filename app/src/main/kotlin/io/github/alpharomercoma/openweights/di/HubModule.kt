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
import io.github.alpharomercoma.openweights.core.data.TokenVault
import io.github.alpharomercoma.openweights.core.hub.HubTokenSource
import io.github.alpharomercoma.openweights.core.hub.HuggingFaceClient
import io.github.alpharomercoma.openweights.core.hub.ModelDownloader
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object HubModule {

    @Provides
    @Singleton
    fun provideHttpClient(): OkHttpClient = OkHttpClient.Builder()
        // Model files are gigabytes; a read timeout tuned for JSON would abort them.
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Provides
    @Singleton
    fun provideTokenSource(vault: TokenVault): HubTokenSource = HubTokenSource { vault.current() }

    @Provides
    @Singleton
    fun provideHuggingFaceClient(
        httpClient: OkHttpClient,
        tokenSource: HubTokenSource,
    ): HuggingFaceClient = HuggingFaceClient(httpClient, tokenSource)

    @Provides
    @Singleton
    fun provideModelDownloader(
        httpClient: OkHttpClient,
        client: HuggingFaceClient,
        tokenSource: HubTokenSource,
    ): ModelDownloader = ModelDownloader(httpClient, client, tokenSource)

    private const val CONNECT_TIMEOUT_SECONDS = 20L
    private const val READ_TIMEOUT_SECONDS = 60L
}
