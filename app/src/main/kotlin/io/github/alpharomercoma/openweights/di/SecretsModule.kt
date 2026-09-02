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
import io.github.alpharomercoma.openweights.core.tools.SecretSealer
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SecretsModule {
    /**
     * The tools' sealed store is the token vault's cipher.
     *
     * The vault lives in `:core:data`, which `:core:tools` does not see, so the join is made
     * here. One Keystore key serves both the token and the proxy credentials: a fresh
     * initialisation vector on every seal is what makes two secrets under one key safe, and
     * a second alias would only be a second thing to lose on a device whose Keystore has
     * already lost one.
     */
    @Provides
    @Singleton
    fun provideSecretSealer(vault: TokenVault): SecretSealer = object : SecretSealer {
        override fun seal(value: String): String? = vault.seal(value)

        override fun open(stored: String): String? = vault.open(stored)
    }
}
