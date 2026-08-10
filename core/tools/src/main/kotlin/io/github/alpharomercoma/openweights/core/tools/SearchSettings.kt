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

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
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
class SearchSettings @Inject constructor(@param:ApplicationContext context: Context) {
    private val store = context.getSharedPreferences("search_settings", Context.MODE_PRIVATE)

    /** The SearXNG instance to query, or blank when none is set. */
    var searxUrl: String
        get() = store.getString(KEY_SEARX_URL, "").orEmpty()
        set(value) = store.edit { putString(KEY_SEARX_URL, value.trim()) }

    /**
     * Whether Wikipedia may answer when nothing else can.
     *
     * On by default so that a fresh install can answer something, off for anyone who would
     * rather see "no search is configured" than an encyclopedia article about a question
     * that was not encyclopedic.
     */
    var wikipediaFallback: Boolean
        get() = store.getBoolean(KEY_WIKIPEDIA, true)
        set(value) = store.edit { putBoolean(KEY_WIKIPEDIA, value) }

    /**
     * The providers to try, in order.
     *
     * A general engine first and the encyclopedia last, so an answer only falls back to
     * Wikipedia when the web could not be reached at all. Each is tried until one answers;
     * a provider that is rate limited says so rather than returning nothing, which is what
     * makes the order meaningful instead of decorative.
     *
     * A keyed provider belongs here too and is deliberately absent: the key would have to
     * live in the encrypted store this module cannot see, and an unwired settings field is
     * worse than a missing one.
     */
    fun providers(httpClient: OkHttpClient): List<SearchProvider> = buildList {
        add(DuckDuckGoProvider(httpClient))
        add(SearxProvider(httpClient, searxUrl))
        if (wikipediaFallback) add(WikipediaProvider(httpClient))
    }.filter { it.isConfigured }

    private companion object {
        const val KEY_SEARX_URL = "searx_url"
        const val KEY_WIKIPEDIA = "wikipedia_fallback"
    }
}
