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

package io.github.alpharomercoma.openweights.watch

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.alpharomercoma.openweights.core.tools.Watches
import javax.inject.Singleton

/**
 * Joins the watch tool to the place watches are actually kept.
 *
 * The tool asks for a [Watches]; the app is what has a database and a scheduler. Starting a
 * watch has to do both, in that order, because the scheduler needs the id storage assigns.
 */
@Module
@InstallIn(SingletonComponent::class)
object WatchModule {
    @Provides
    @Singleton
    fun watches(
        repository: io.github.alpharomercoma.openweights.core.data.WatchRepository,
        scheduler: WatchScheduler,
    ): Watches = Watches { task, everyMinutes ->
        repository.add(task, everyMinutes, System.currentTimeMillis())?.also {
            scheduler.schedule(it)
        }
    }
}
