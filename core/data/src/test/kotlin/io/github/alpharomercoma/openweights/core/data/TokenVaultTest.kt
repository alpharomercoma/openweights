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

package io.github.alpharomercoma.openweights.core.data

import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.KeyGenerator

/**
 * The envelope the one credential this app holds is stored in.
 *
 * A key out of the Android Keystore is the platform's business and there is no keystore on
 * a host to test it against, so the key here is an ordinary AES one and everything around
 * it is real: the same transformation, the same tag length, the same initialisation vector
 * written in front of the same ciphertext. What is being checked is the part that is ours
 * and the part that can be quietly wrong, which is the layout, and the part that has to
 * hold when something has gone wrong, which is that an unreadable value reads as no token
 * rather than as a crash on the screen somebody opened to fix it.
 */
@RunWith(RobolectricTestRunner::class)
class TokenVaultTest {
    private val cipher =
        TokenCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
    private val token = "hf_a_token_that_looks_real"

    @Test
    fun `a sealed token opens again`() {
        assertThat(cipher.open(cipher.seal(token))).isEqualTo(token)
    }

    @Test
    fun `the sealed form does not contain the token`() {
        // The whole point of the exercise. Everything else here would still pass if this
        // wrote the token out in the clear.
        val sealed = cipher.seal(token)

        assertThat(sealed).doesNotContain(token)
        assertThat(String(Base64.decode(sealed, Base64.NO_WRAP))).doesNotContain(token)
    }

    @Test
    fun `sealing twice gives two different values`() {
        // A fresh initialisation vector every time is what makes that true, and reusing one
        // with the same key is the way to lose a GCM key's guarantees entirely. Two equal
        // ciphertexts for one token is what that failure looks like from outside.
        assertThat(cipher.seal(token)).isNotEqualTo(cipher.seal(token))
    }

    @Test
    fun `a token with characters outside ASCII survives the round trip`() {
        // The bytes are taken as UTF-8 at both ends rather than in whatever the platform
        // defaults to, which is the kind of thing that agrees with itself on one machine.
        val awkward = "hf_ключ_鍵_مفتاح"

        assertThat(cipher.open(cipher.seal(awkward))).isEqualTo(awkward)
    }

    @Test
    fun `a value that is not base64 reads as no token`() {
        assertThat(cipher.open("not base64 at all")).isNull()
    }

    @Test
    fun `a value too short to hold an initialisation vector reads as no token`() {
        // A half-finished write, which is the shape a killed process leaves behind. Both
        // lengths already came back null, one through an index and one through a missing
        // tag; asserted so that a future reading of the layout has to keep saying so.
        assertThat(cipher.open(Base64.encodeToString(ByteArray(4), Base64.NO_WRAP))).isNull()
        assertThat(cipher.open(Base64.encodeToString(ByteArray(12), Base64.NO_WRAP))).isNull()
    }

    @Test
    fun `a value somebody edited reads as no token`() {
        // GCM refuses on its tag rather than decrypting into rubbish, and that refusal has
        // to arrive as a null: a token nobody can vouch for is not a token.
        val bytes = Base64.decode(cipher.seal(token), Base64.NO_WRAP)
        bytes[bytes.lastIndex] = (bytes[bytes.lastIndex] + 1).toByte()

        assertThat(cipher.open(Base64.encodeToString(bytes, Base64.NO_WRAP))).isNull()
    }

    @Test
    fun `a value sealed with another key reads as no token`() {
        val other = TokenCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())

        assertThat(cipher.open(other.seal(token))).isNull()
    }

    @Test
    fun `an empty token is refused before anything is encrypted`() = runTest {
        // Checked before the key is touched, which is what lets this run at all on a host
        // with no keystore, and is also the right order: there is nothing to protect.
        val vault = TokenVault(ApplicationProvider.getApplicationContext())

        assertThat(runCatching { vault.store("   ") }.isFailure).isTrue()
    }
}
