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

package io.github.alpharomercoma.openweights.core.tools

import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the user has told the app to search.
 *
 * Measured before this was built: there is no keyless general web search left to default
 * to. DuckDuckGo answers its lite and html endpoints with a challenge page rather than
 * results, its Instant Answer API returns empty fields, and public SearXNG instances
 * return 403 or 429 to anonymous JSON. So the choice is either a provider the user
 * configures or an encyclopedia, and both are offered rather than one being imposed.
 *
 * DuckDuckGo needs neither a key nor an account and answers general questions, so it is
 * the default and nothing has to be configured for search to work on a fresh install.
 * This holds only what the user changes: an instance address and whether the encyclopedia
 * may answer, neither of which is a secret.
 */
@Singleton
class SearchSettings @Inject constructor() {

    /**
     * The providers to try, in order.
     *
     * One, for now. A provider that is rate limited says so rather than returning nothing,
     * which is what will make the order meaningful once there is more than one.
     *
     * A SearXNG instance used to be second. It went for the same reason Wikipedia did, in
     * reverse: not because it was hardcoded, but because it was a field in a screen that
     * nobody was ever going to fill in, and it has no index of its own anyway, so it moved
     * the blocking to a machine the user had to run rather than removing it.
     *
     * Wikipedia used to sit at the end of this list. It went because it was a hardcoded
     * site nobody could see or switch off, and because of what it did to answers: the model
     * talked about encyclopedia articles when it had been asked about the web, and a
     * stranger's name came back as an unrelated senator rather than as nothing.
     *
     * A keyed provider belongs here too and is deliberately absent: the key would have to
     * live in the encrypted store this module cannot see, and an unwired settings field is
     * worse than a missing one.
     */
    fun providers(httpClient: OkHttpClient): List<SearchProvider> = buildList {
        add(DuckDuckGoProvider(httpClient))
    }.filter { it.isConfigured }
}
