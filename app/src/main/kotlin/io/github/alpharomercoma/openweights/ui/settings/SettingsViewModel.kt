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

package io.github.alpharomercoma.openweights.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.alpharomercoma.openweights.core.data.AppearanceRepository
import io.github.alpharomercoma.openweights.core.data.ThemeChoice
import io.github.alpharomercoma.openweights.core.data.TokenVault
import io.github.alpharomercoma.openweights.core.device.DeviceProfile
import io.github.alpharomercoma.openweights.core.device.DeviceProfiler
import io.github.alpharomercoma.openweights.core.engine.ComputeDevice
import io.github.alpharomercoma.openweights.core.engine.InferenceEngine
import io.github.alpharomercoma.openweights.core.hub.HubException
import io.github.alpharomercoma.openweights.core.hub.HuggingFaceClient
import io.github.alpharomercoma.openweights.ui.discover.readableMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

data class SettingsUiState(
    val hasToken: Boolean = false,
    val tokenStatus: String? = null,
    val isCheckingToken: Boolean = false,
    val computeDevices: List<ComputeDevice> = emptyList(),
    val device: DeviceProfile? = null,
    val engineInfo: String = "",
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val vault: TokenVault,
    private val client: HuggingFaceClient,
    private val engine: InferenceEngine,
    private val profiler: DeviceProfiler,
    private val appearance: AppearanceRepository,
) : ViewModel() {
    private val tokenMutex = Mutex()
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // Collected for the life of the view model and awaited by nobody, so a store that
            // will not open would take the process rather than the theme. The default look is
            // a fine thing to fall back to; a crash on opening Settings is not.
            appearance.themeChoice
                .catch { failure ->
                    Log.w("OpenWeights", "the theme choice could not be read", failure)
                }
                .collect { choice ->
                    _uiState.update { it.copy(theme = choice) }
                }
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    hasToken = vault.current() != null,
                    computeDevices = runCatching {
                        engine.computeDevices()
                    }.getOrDefault(emptyList()),
                    device = profiler.profile(),
                    engineInfo = runCatching { engine.systemInfo() }.getOrDefault(""),
                )
            }
        }
    }

    /**
     * Stores the token and immediately proves it works.
     *
     * Saving a credential without checking it just moves the failure to the next download,
     * where it is much harder to connect to what the user did.
     */
    fun saveToken(token: String) {
        viewModelScope.launch {
            // Serialised against every other write: two saves in flight could otherwise
            // have the older one's failure delete the newer one's valid token.
            tokenMutex.withLock {
                _uiState.update { it.copy(isCheckingToken = true, tokenStatus = null) }
                if (!vault.store(token)) {
                    // Nothing was written, in the clear or otherwise; see TokenVault.store.
                    // Checking a token that was not kept would only report on the network.
                    reportToken(
                        hasToken = false,
                        status = "Not saved: this device's key store is not answering, so " +
                            "the token cannot be kept safely.",
                    )
                    return@withLock
                }

                runCatching { client.whoAmI() }
                    .onSuccess { account -> reportToken(true, "Signed in as $account") }
                    .onFailure { failure -> handleVerificationFailure(failure) }
            }
        }
    }

    /**
     * A rejected token should be deleted; an unreachable Hub is not.
     *
     * Throwing away a credential the user just typed because their train went into a
     * tunnel is worse than keeping one that might be wrong.
     */
    private suspend fun handleVerificationFailure(failure: Throwable) {
        val rejected = (failure as? HubException)?.isAuthFailure == true
        if (rejected) vault.clear()

        reportToken(
            hasToken = !rejected,
            status = if (rejected) {
                failure.readableMessage()
            } else {
                "Saved, but it could not be checked right now: ${failure.readableMessage()}"
            },
        )
    }

    private fun reportToken(hasToken: Boolean, status: String) {
        _uiState.update {
            it.copy(isCheckingToken = false, hasToken = hasToken, tokenStatus = status)
        }
    }

    /** Applies immediately: the whole app is inside the theme this chooses. */
    fun setTheme(choice: ThemeChoice) {
        viewModelScope.launch { appearance.setThemeChoice(choice) }
    }

    fun clearToken() {
        viewModelScope.launch {
            tokenMutex.withLock {
                vault.clear()
                _uiState.update { it.copy(hasToken = false, tokenStatus = "Token removed") }
            }
        }
    }
}
