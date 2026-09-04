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

package io.github.alpharomercoma.openweights.download

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Says when a model file has finished arriving on disk.
 *
 * The whole warm mechanism turns a first turn from a minute into a second, and it does it
 * by having computed the prefix already. On every model but the first that is free: the
 * warm was computed and written to `cacheDir/warm` on some earlier launch. On a model
 * that has just been downloaded there is nothing to restore, so somebody has to pay for
 * the one computed warm — and until now that somebody was whoever sent the first message.
 *
 * This is what lets it be paid earlier instead. The download ends while the user is still
 * on the models screen reading what they just fetched, and that is minutes of a phone
 * doing nothing, which is exactly the budget the warm needs.
 *
 * A [SharedFlow] with no replay, deliberately. A model that arrived during a previous
 * process is not news: its warm either exists on disk or will be computed by the load
 * that opens it. Replaying it would reload weights on every cold start for a download the
 * user may have long since moved on from.
 */
@Singleton
class ModelArrivals @Inject constructor() {
    private val _arrivals = MutableSharedFlow<File>(extraBufferCapacity = ARRIVAL_BUFFER)

    /** Model files that finished downloading while this process was alive. */
    val arrivals: SharedFlow<File> = _arrivals.asSharedFlow()

    /**
     * Records that [file] is now complete on disk.
     *
     * Non-suspending and lossy under back pressure, because the caller is a download
     * worker finishing its work and must not be held up by whether anybody is listening.
     * Dropping the announcement costs the warm its head start, which is the same position
     * the app was in before any of this existed.
     */
    fun announce(file: File) {
        _arrivals.tryEmit(file)
    }

    private companion object {
        /** Enough for a model and the projector that comes with it, several times over. */
        const val ARRIVAL_BUFFER = 8
    }
}
