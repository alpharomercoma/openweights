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

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores the user's Hugging Face access token.
 *
 * The token is encrypted with an AES-GCM key that lives in the Android Keystore and never
 * leaves it, so the stored value is useless to anything that reads the app's files. This
 * is the modern replacement for `EncryptedSharedPreferences`, which Google deprecated:
 * DataStore does the storage, the Keystore does the cryptography, and neither pretends to
 * do the other's job.
 */
@Singleton
class TokenVault @Inject constructor(@ApplicationContext private val context: Context) {
    /** The stored token, or null when none is set. */
    val token: Flow<String?> = context.settingsDataStore.data.map { preferences ->
        preferences[TOKEN_KEY]?.let(::decrypt)
    }

    suspend fun current(): String? = token.first()

    suspend fun store(rawToken: String) {
        val trimmed = rawToken.trim()
        require(trimmed.isNotEmpty()) { "refusing to store an empty token" }
        context.settingsDataStore.edit { it[TOKEN_KEY] = encrypt(trimmed) }
    }

    suspend fun clear() {
        context.settingsDataStore.edit { it.remove(TOKEN_KEY) }
    }

    private fun encrypt(value: String): String = TokenCipher(key).seal(value)

    private fun decrypt(stored: String): String? = TokenCipher(key).open(stored)

    /**
     * The key, fetched once and kept for the life of the process.
     *
     * Read every time before, which was two problems in one line. It reloads the keystore on
     * every read of the token, on whichever thread is collecting; and, worse, two callers
     * arriving together both found no entry and both generated one. Generating twice under a
     * single alias replaces the first, so a token sealed with it could never be opened
     * again: the screen would say the token was saved and every gated download afterwards
     * would be refused for having none.
     *
     * `lazy` is synchronized by default, which is exactly the guarantee that was missing. A
     * failure is not cached, so a keystore that was briefly unavailable is tried again
     * rather than poisoning the vault for the life of the process.
     */
    private val key: SecretKey by lazy { keystoreKey() }

    private fun keystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "openweights.hf_token"
        val TOKEN_KEY = stringPreferencesKey("hugging_face_token")
    }
}

/**
 * The envelope the token is stored in, separated from where its key comes from.
 *
 * Two different things live in this file. Getting a key out of the Android Keystore is the
 * platform's job and there is nothing here to be wrong about it; laying an initialisation
 * vector next to a ciphertext, and reading it back out at the right offset, is ours, and
 * off-by-one there is the kind of mistake that still round trips on the machine that made
 * it. Split so that half can be tested on a host, where there is no keystore at all.
 */
internal class TokenCipher(private val key: SecretKey) {
    /**
     * Encrypts [value] as one storable string.
     *
     * The initialisation vector is generated fresh for every call and written in front of
     * the ciphertext. It is not a secret and does not need to be; what it must never be is
     * reused with the same key, which is why it is taken from the cipher after `init`
     * rather than chosen here.
     */
    fun seal(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    /**
     * Reads a sealed token back, or null if it cannot be read.
     *
     * Null covers every way this can fail and they all mean the same thing to the app: a
     * key invalidated by a device credential change, a value truncated by a half-finished
     * write, or bytes somebody edited, which GCM refuses on its tag rather than decrypting
     * into rubbish. None of those is worth a crash on the screen somebody opened to fix it.
     */
    fun open(stored: String): String? = runCatching {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        require(bytes.size > GCM_IV_BYTES) { "too short to hold an IV and a ciphertext" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(GCM_TAG_BITS, bytes, 0, GCM_IV_BYTES),
        )
        String(cipher.doFinal(bytes, GCM_IV_BYTES, bytes.size - GCM_IV_BYTES), Charsets.UTF_8)
    }.getOrNull()

    internal companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
