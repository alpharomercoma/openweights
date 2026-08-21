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

package io.github.alpharomercoma.openweights

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.svg.SvgDecoder
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * The application, WorkManager's configuration, and the image loader.
 *
 * The download worker takes an injected downloader, which the default worker factory cannot
 * construct: it only knows how to call a two argument constructor. Handing WorkManager
 * Hilt's factory instead is what lets a worker have dependencies, and the manifest removes
 * WorkManager's own startup initializer so this configuration is the one that is used.
 */
@HiltAndroidApp
class OpenWeightsApplication :
    Application(),
    Configuration.Provider,
    SingletonImageLoader.Factory {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /**
     * The image loader, with SVG decoding named rather than discovered.
     *
     * Coil finds its decoders through a service locator, which works until R8 runs: the
     * release build minifies and shrinks, and a component nothing references by name is
     * exactly what that is for. An avatar that decodes in debug and silently does not in
     * the build people install is a bug that only shows up after shipping, so the one
     * decoder this app depends on is added by hand.
     *
     * The network fetcher is named for the same reason and not because the service
     * locator would miss it. Every image here is an https URL, so a build where that
     * component went missing shows no avatars at all, and one line is a cheap price for
     * never having to work out whether the locator survived a given R8 pass.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory())
                add(SvgDecoder.Factory())
            }
            .build()
}
