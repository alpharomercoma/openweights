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

package io.github.alpharomercoma.openweights.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.alpharomercoma.openweights.model.ModelStore
import io.github.alpharomercoma.openweights.ui.chat.ChatScreen
import io.github.alpharomercoma.openweights.ui.chat.ChatViewModel

/**
 * Root composable.
 *
 * Phase 1 has a single screen and auto-loads whichever model is sitting in the app's
 * models folder. Navigation, the model browser, and histories arrive in later phases.
 */
@Composable
fun OpenWeightsApp(modifier: Modifier = Modifier) {
    val viewModel: ChatViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        ModelStore(context).firstAvailableModel()?.let(viewModel::loadModel)
    }

    ChatScreen(
        state = state,
        onSend = viewModel::send,
        onStop = viewModel::stop,
        modifier = modifier,
    )
}
