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

package io.github.alpharomercoma.openweights.core.data.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.alpharomercoma.openweights.core.data.Clock
import io.github.alpharomercoma.openweights.core.data.db.OpenWeightsDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): OpenWeightsDatabase =
        Room.databaseBuilder(context, OpenWeightsDatabase::class.java, OpenWeightsDatabase.NAME)
            .addMigrations(
                OpenWeightsDatabase.MIGRATION_1_2,
                OpenWeightsDatabase.MIGRATION_2_3,
            )
            .build()

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.System
}
