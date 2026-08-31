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
import okhttp3.Credentials
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

    /**
     * Whether library documentation is searched before the web.
     *
     * Off unless asked for, which is the opposite of every other source here and is a
     * conclusion rather than caution. Context7 answers any question, including ones with
     * nothing to do with code: the recorded response to "what is the weather in Manila right
     * now" has thirty results in it, and while the filter in [Context7Provider] throws out
     * the confident nonsense, what survives is a library genuinely called Weather. No filter
     * fixes that, because it is a correct match to a question that was not about code.
     *
     * Since the chain stops at the first source that answers, a wrong answer here costs the
     * web its turn. So the person decides: someone who switches it on is asking about
     * libraries, and that is a fact about them that no heuristic on the query can recover.
     */
    var searchesDocumentation: Boolean
        get() = store.getBoolean(DOCUMENTATION, false)
        set(value) = store.edit { putBoolean(DOCUMENTATION, value) }

    /**
     * The client search uses, which is the caller's with the proxy applied when there is one.
     *
     * Derived rather than replaced, so the connection pool, the timeouts and the interceptors
     * the app configured once are all still there. A proxy address that does not parse is
     * ignored rather than fatal: a typed setting should degrade to searching directly, not
     * to a search tool that throws.
     */
    fun client(httpClient: OkHttpClient): OkHttpClient {
        val hop = proxy.asProxy() ?: return httpClient
        val builder = httpClient.newBuilder().proxy(hop)
        // Credentials, when the address carries them. HTTP proxies take them per request
        // through the standard challenge; OkHttp has no per-client hook for SOCKS
        // authentication, and a process-global java.net.Authenticator would apply to every
        // connection this app makes, so for SOCKS the user:pass part is ignored rather
        // than half-honoured. The placeholder shows credentials on the HTTP example only.
        proxyCredentials()?.let { (user, pass) ->
            builder.proxyAuthenticator { _, response ->
                if (response.request.header("Proxy-Authorization") != null) {
                    null // Refused once already; repeating the same answer would loop.
                } else {
                    response.request.newBuilder()
                        .header("Proxy-Authorization", Credentials.basic(user, pass))
                        .build()
                }
            }
        }
        return builder.build()
    }

    /** The user:pass in the proxy address, or null when it carries none. */
    private fun proxyCredentials(): Pair<String, String>? {
        val info = runCatching { java.net.URI(proxy).userInfo }.getOrNull() ?: return null
        val user = info.substringBefore(':')
        val pass = info.substringAfter(':', missingDelimiterValue = "")
        return user.takeIf { it.isNotEmpty() }?.let { it to pass }
    }

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
        // First when it is on, because it is the precise one: a question it can answer it
        // answers better than the web, and one it cannot it now declines rather than
        // guessing. Behind DuckDuckGo it would only ever be reached when the web had
        // failed, which is exactly when a wrong answer is least welcome.
        if (searchesDocumentation) add(Context7Provider(httpClient))
        enabledEngines().forEach { engine ->
            add(
                when (engine) {
                    SearchEngine.DUCKDUCKGO -> DuckDuckGoProvider(httpClient)
                    SearchEngine.BRAVE -> BraveProvider(httpClient)
                    SearchEngine.BING -> BingProvider(httpClient)
                    SearchEngine.GOOGLE -> GoogleProvider(httpClient)
                },
            )
        }
    }.filter { it.isConfigured }

    /**
     * The engines to try, in order, skipping any the user has switched off.
     *
     * Order is fixed rather than configurable, and it is the order of how often each
     * actually answers from a phone rather than of how good its index is. Google has the
     * best index and refuses most often, so it is last: putting it first would mean most
     * searches waited for a refusal before doing anything useful.
     *
     * Everything on by default. The chain stops at the first engine that answers, so an
     * engine that is never reached costs nothing, and one that is switched off cannot be
     * the one that would have answered.
     */
    fun enabledEngines(): List<SearchEngine> = SearchEngine.entries.filter { isEnabled(it) }

    fun isEnabled(engine: SearchEngine): Boolean = store.getBoolean(engine.key, true)

    fun setEnabled(engine: SearchEngine, enabled: Boolean) {
        // Never all of them off. A search tool with no engine behind it is a tool that
        // reports the web is unreachable, which reads to the model as a fact about the web.
        if (!enabled && enabledEngines() == listOf(engine)) return
        store.edit { putBoolean(engine.key, enabled) }
    }

    /**
     * A proxy for search traffic only, or blank for none.
     *
     * Search only, deliberately. This app's promise is that nothing leaves the device
     * without being asked, and a proxy is a third party that would see every request the
     * app makes if it were global. Scoped here it sees what the user already chose to send
     * to a search engine, and nothing else: not model downloads, not fetched pages.
     *
     * Offered because Google and Bing both refuse outright from some networks and some
     * countries, and because a user who knows they are blocked has no other lever. It is
     * not a guarantee and the setting says so: a proxy that is itself blocked, or that the
     * engine has seen before, fails exactly as the direct connection did.
     *
     * `http`, `https` and `socks5` are the schemes `ddgs` supports and the ones supported
     * here, for the same reason: they are what people actually have.
     */
    var proxy: String
        get() = store.getString(PROXY, "").orEmpty()
        set(value) = store.edit { putString(PROXY, value.trim()) }

    private companion object {
        const val DOCUMENTATION = "documentation"
        const val PROXY = "proxy"
    }
}

/**
 * A general web engine the app can read.
 *
 * Four, and the list is a judgement rather than everything possible. `ddgs` reaches ten,
 * including Yahoo and Startpage, which resell Bing and Google respectively: adding them
 * would offer the user a longer list of the same two indexes. Yandex and Mojeek are real
 * independent indexes and are absent because neither is one this app can recommend for a
 * general question in English.
 *
 * So: two independent indexes, Brave and DuckDuckGo, and the two large ones.
 */
enum class SearchEngine(val key: String, val label: String, val detail: String) {
    /** Answers without a key or an account, and the only one measured to do so reliably. */
    DUCKDUCKGO("engine_duckduckgo", "DuckDuckGo", "Answers without an account. The default."),

    /** Its own index, which is what makes it worth having beside the large two. */
    BRAVE("engine_brave", "Brave", "An index of its own, not a front end for another."),

    BING("engine_bing", "Bing", "Also the index behind Yahoo and several others."),

    /** Last: best index, most likely to refuse a phone. See [SearchSettings.proxy]. */
    GOOGLE("engine_google", "Google", "The best index, and the most likely to refuse."),
}

/**
 * A typed proxy address, or null for anything this cannot use.
 *
 * Null covers blank, unparseable, a missing host or port, and a scheme that is not one of
 * the three. All of them mean the same thing to the caller: search directly. A typed setting
 * should degrade to working rather than to a tool that raises.
 */
private fun String.asProxy(): java.net.Proxy? {
    if (isBlank()) return null
    val parsed = runCatching { java.net.URI(this) }.getOrNull() ?: return null
    val host = parsed.host
    val port = parsed.port.takeIf { it > 0 }
    // socks5h resolves names at the proxy, which is the point of using one to get past a
    // block. Java has one SOCKS type and resolves at the proxy for an unresolved address,
    // so both spellings land in the same place.
    val kind = when (parsed.scheme?.lowercase()) {
        "socks5", "socks5h", "socks" -> java.net.Proxy.Type.SOCKS
        "http", "https" -> java.net.Proxy.Type.HTTP
        else -> null
    }
    if (host == null || port == null || kind == null) return null
    return java.net.Proxy(kind, java.net.InetSocketAddress.createUnresolved(host, port))
}
