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
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Where a proxy's credentials go, and where they never do.
 *
 * The sealer here reverses the string, which is not a cipher and is not meant to be: it is
 * enough to tell a sealed value from a plain one in the file, and to open it again. What
 * is being checked is the split, that the address stays a setting and the `user:pass` in
 * front of it does not, and the two ways that can go wrong on a phone whose Keystore has
 * stopped answering, neither of which may end with a password in the plain file.
 */
@RunWith(RobolectricTestRunner::class)
class SearchSettingsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The plain file, read directly, which is the whole point. */
    private val plain = context.getSharedPreferences("search_settings", Context.MODE_PRIVATE)

    private class ReversingSealer(private val opens: Boolean = true) : SecretSealer {
        override fun seal(value: String): String = "sealed:" + value.reversed()

        override fun open(stored: String): String? =
            stored.removePrefix("sealed:").takeIf { opens && it != stored }?.reversed()
    }

    private fun plainValuesHolding(text: String): List<Any?> =
        plain.all.values.filter { text in it.toString() }

    @Test
    fun `credentials are sealed and the address stays a plain setting`() {
        val settings = SearchSettings(context, ReversingSealer())

        settings.proxy = "http://user:secret@host:3128"

        assertThat(settings.proxy).isEqualTo("http://user:secret@host:3128")
        assertThat(plain.getString("proxy", null)).isEqualTo("http://host:3128")
        assertThat(plain.getString("proxy_credentials", null)).isEqualTo("sealed:terces:resu")
        assertThat(plainValuesHolding("secret")).isEmpty()
    }

    @Test
    fun `an address without credentials leaves nothing sealed`() {
        val settings = SearchSettings(context, ReversingSealer())
        settings.proxy = "http://user:secret@host:3128"

        settings.proxy = "socks5h://127.0.0.1:9150"

        assertThat(settings.proxy).isEqualTo("socks5h://127.0.0.1:9150")
        assertThat(plain.contains("proxy_credentials")).isFalse()
    }

    @Test
    fun `a value saved before the split is moved the first time it is read`() {
        plain.edit { putString("proxy", "http://old:pass@host:8080") }
        val settings = SearchSettings(context, ReversingSealer())

        assertThat(settings.proxy).isEqualTo("http://old:pass@host:8080")

        assertThat(plain.getString("proxy", null)).isEqualTo("http://host:8080")
        assertThat(plain.getString("proxy_credentials", null)).isEqualTo("sealed:ssap:dlo")
        assertThat(plainValuesHolding("pass")).isEmpty()
    }

    @Test
    fun `credentials that cannot be sealed are not kept in the clear`() {
        val settings = SearchSettings(context, SecretSealer.Unavailable)

        settings.proxy = "http://user:secret@host:3128"

        // The address survives and the field shows it without the credentials, which is
        // how the person finds out; nothing in the file holds the password.
        assertThat(settings.proxy).isEqualTo("http://host:3128")
        assertThat(plainValuesHolding("secret")).isEmpty()
    }

    @Test
    fun `a value that cannot be moved yet keeps working as it was`() {
        plain.edit { putString("proxy", "http://old:pass@host:8080") }
        val settings = SearchSettings(context, SecretSealer.Unavailable)

        assertThat(settings.proxy).isEqualTo("http://old:pass@host:8080")

        // Untouched, and so tried again next time rather than lost to a Keystore hiccup.
        assertThat(plain.getString("proxy", null)).isEqualTo("http://old:pass@host:8080")
        assertThat(plain.contains("proxy_credentials")).isFalse()
    }

    @Test
    fun `credentials that no longer open read as the address alone`() {
        val settings = SearchSettings(context, ReversingSealer(opens = false))

        settings.proxy = "http://user:secret@host:3128"

        assertThat(settings.proxy).isEqualTo("http://host:3128")
    }

    @Test
    fun `the client still authenticates from the sealed credentials`() {
        val settings = SearchSettings(context, ReversingSealer())
        val client = OkHttpClient()

        settings.proxy = "http://user:secret@host:3128"
        val withCredentials = settings.client(client).proxyAuthenticator
        settings.proxy = "http://host:3128"
        val without = settings.client(client).proxyAuthenticator

        assertThat(withCredentials).isNotSameInstanceAs(Authenticator.NONE)
        assertThat(without).isSameInstanceAs(Authenticator.NONE)
    }

    @Test
    fun `an address with no scheme still has its credentials kept out of the plain file`() {
        val settings = SearchSettings(context, ReversingSealer())

        settings.proxy = "user:secret@host:3128"

        assertThat(settings.proxy).isEqualTo("user:secret@host:3128")
        assertThat(plainValuesHolding("secret")).isEmpty()
    }
}
