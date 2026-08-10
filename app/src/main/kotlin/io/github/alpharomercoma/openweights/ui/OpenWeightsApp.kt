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

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.InsertChart
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.alpharomercoma.openweights.core.designsystem.theme.Motion
import io.github.alpharomercoma.openweights.ui.chat.ChatScreen
import io.github.alpharomercoma.openweights.ui.chat.ChatViewModel
import io.github.alpharomercoma.openweights.ui.chat.MediaViewModel
import io.github.alpharomercoma.openweights.ui.chat.ReportViewModel
import io.github.alpharomercoma.openweights.ui.dashboard.DashboardScreen
import io.github.alpharomercoma.openweights.ui.dashboard.DashboardViewModel
import io.github.alpharomercoma.openweights.ui.discover.DiscoverScreen
import io.github.alpharomercoma.openweights.ui.discover.DiscoverViewModel
import io.github.alpharomercoma.openweights.ui.models.ModelsScreen
import io.github.alpharomercoma.openweights.ui.models.ModelsViewModel
import io.github.alpharomercoma.openweights.ui.settings.SettingsScreen
import io.github.alpharomercoma.openweights.ui.settings.SettingsViewModel

/** The app's four destinations. */
private enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    CHAT("chat", "Chat", Icons.Rounded.Chat),
    DISCOVER("discover", "Discover", Icons.Rounded.Explore),
    MODELS("models", "Models", Icons.Rounded.Storage),
    USAGE("usage", "Usage", Icons.Rounded.InsertChart),
    SETTINGS("settings", "Settings", Icons.Rounded.Settings),
}

/** A twelfth of the screen: enough rise to read as arrival, small enough to stay quick. */
private const val TAB_SLIDE = 12

@Composable
fun OpenWeightsApp(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentDestination = backStack?.destination

    // Chat and Models get one view model each, hoisted above the NavHost, so a download
    // keeps running and a loaded model stays loaded while the user moves around the app.
    val chatViewModel: ChatViewModel = hiltViewModel()
    val mediaViewModel: MediaViewModel = hiltViewModel()
    val modelsViewModel: ModelsViewModel = hiltViewModel()

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        // The navigation bar handles the gesture inset itself, and each screen handles the
        // status bar. Letting this scaffold apply system insets too padded everything
        // twice, which is what left the top bar and the composer floating mid-screen.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Destination.entries.forEach { destination ->
                    val selected =
                        currentDestination?.hierarchy?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(destination.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.CHAT.route,
            // Consuming what has just been applied is what lets a screen's imePadding()
            // add only the difference. Without it the keyboard pushes the composer up by
            // its full height while the navigation bar's reserved space stays behind it,
            // leaving a band of dead screen between the two.
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
            // Compose Navigation defaults to 700 ms fades, which reads as the app thinking
            // when it is only switching tabs. A tab switch should feel like it already
            // happened; the slight rise gives it direction without costing time.
            enterTransition = {
                fadeIn(Motion.quick()) + slideInVertically(Motion.quick()) { it / TAB_SLIDE }
            },
            exitTransition = { fadeOut(Motion.instant()) },
            popEnterTransition = {
                fadeIn(Motion.quick()) + slideInVertically(Motion.quick()) { it / TAB_SLIDE }
            },
            popExitTransition = { fadeOut(Motion.instant()) },
        ) {
            composable(Destination.CHAT.route) {
                val state by chatViewModel.uiState.collectAsStateWithLifecycle()
                val isSpeaking by mediaViewModel.isSpeaking.collectAsStateWithLifecycle()
                val dictation by mediaViewModel.dictationState.collectAsStateWithLifecycle()
                val reportViewModel: ReportViewModel = hiltViewModel()

                LaunchedEffect(Unit) {
                    // The view model outlives the composition, so returning to this tab
                    // must not reload the model and wipe the conversation.
                    if (!chatViewModel.hasModel) chatViewModel.loadDefaultModel()
                }

                ChatScreen(
                    state = state,
                    onSend = chatViewModel::send,
                    onStop = chatViewModel::stop,
                    onRegenerate = chatViewModel::regenerate,
                    onNewChat = chatViewModel::newChat,
                    onCompact = chatViewModel::compactNow,
                    onOpenModels = { navController.navigate(Destination.MODELS.route) },
                    onOpenConversation = chatViewModel::openConversation,
                    onDeleteConversation = chatViewModel::deleteConversation,
                    onSavePreferences = chatViewModel::savePreferences,
                    onResetPreferences = chatViewModel::resetPreferences,
                    onAttach = chatViewModel::attach,
                    onRemoveStaged = chatViewModel::removeStaged,
                    onToggleReadAloud = mediaViewModel::toggleReadAloud,
                    isSpeaking = isSpeaking,
                    newCaptureUri = mediaViewModel::newCaptureUri,
                    dictation = dictation,
                    canDictate = mediaViewModel.canDictate,
                    onDictate = mediaViewModel::toggleDictation,
                    onReport = { entry, reason, note ->
                        reportViewModel.report(
                            modelName = state.modelName,
                            replyText = entry.answer.ifEmpty { entry.text },
                            reason = reason,
                            note = note,
                        )
                    },
                )
            }

            composable(Destination.DISCOVER.route) {
                val viewModel: DiscoverViewModel = hiltViewModel()
                val state by viewModel.uiState.collectAsStateWithLifecycle()

                DiscoverScreen(
                    state = state,
                    onQueryChange = viewModel::onQueryChange,
                    onSearch = { viewModel.search() },
                    onSortChange = viewModel::onSortChange,
                    onFiltersChange = viewModel::onQueryChange,
                    onPhoneSizedChange = viewModel::onPhoneSizedChange,
                    onClearFilters = viewModel::clearFilters,
                    onOpenModel = viewModel::openModel,
                    onCloseModel = viewModel::closeModel,
                    onContextLengthChange = viewModel::onContextLengthChange,
                    onDownload = { repoId, path ->
                        state.files.firstOrNull { it.file.path == path }?.file?.let { file ->
                            modelsViewModel.download(repoId, path, file.sizeBytes, file.sha256)
                            // The projector is not optional for a multimodal model: without
                            // it the weights load but every attachment is refused, which
                            // reads as a broken app rather than a missing file.
                            state.detail?.pairedProjector(file)?.let { projector ->
                                modelsViewModel.downloadProjector(repoId, projector, file.fileName)
                            }
                            navController.navigate(Destination.MODELS.route)
                        }
                    },
                )
            }

            composable(Destination.MODELS.route) {
                val state by modelsViewModel.uiState.collectAsStateWithLifecycle()

                ModelsScreen(
                    state = state,
                    onUse = { model ->
                        // Keeps whatever chat is open. Switching model is a normal thing to
                        // do partway through a conversation, and throwing the conversation
                        // away to do it is not a trade anyone would choose.
                        chatViewModel.loadModel(model.file, keepConversation = true)
                        navController.navigate(Destination.CHAT.route)
                    },
                    onDelete = modelsViewModel::delete,
                    onCancelDownload = modelsViewModel::cancel,
                )
            }

            composable(Destination.USAGE.route) {
                val viewModel: DashboardViewModel = hiltViewModel()
                val summary by viewModel.uiState.collectAsStateWithLifecycle()

                DashboardScreen(summary = summary)
            }

            composable(Destination.SETTINGS.route) {
                val viewModel: SettingsViewModel = hiltViewModel()
                val state by viewModel.uiState.collectAsStateWithLifecycle()

                SettingsScreen(
                    state = state,
                    onSaveToken = viewModel::saveToken,
                    onClearToken = viewModel::clearToken,
                    onSelectTheme = viewModel::setTheme,
                )
            }
        }
    }
}
