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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
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
import io.github.alpharomercoma.openweights.ui.models.ModelsTab
import io.github.alpharomercoma.openweights.ui.models.ModelsTabs
import io.github.alpharomercoma.openweights.ui.models.ModelsViewModel
import io.github.alpharomercoma.openweights.ui.models.rememberModelsTab
import io.github.alpharomercoma.openweights.ui.settings.SettingsScreen
import io.github.alpharomercoma.openweights.ui.settings.SettingsViewModel
import io.github.alpharomercoma.openweights.ui.tools.ToolsScreen
import io.github.alpharomercoma.openweights.ui.tools.ToolsViewModel

/**
 * The app's five destinations, which is the most a bottom bar can hold.
 *
 * Discover is not among them any more: it is half of Models, reached by a tab there, since
 * finding a model and using one are two steps of the same job. That freed the slot for
 * Tools, which had nowhere to live and is the screen that says what the model can actually
 * do besides talk.
 *
 * Called Tools rather than Capabilities or Context. Capabilities is long enough to wrap on
 * a narrow phone next to four short words, and Context already means the context window,
 * shown as a percentage two screens away.
 *
 * The icons are one family and one idea: a chip for the weights, because that is what this
 * app is about and a database cylinder said the opposite, files on a server somewhere. Five
 * destinations is the most Material allows and all five earn it. Four of them are the parts
 * no hosted assistant has, and burying them behind a menu to tidy the bar would be hiding
 * the reason to use this at all.
 */
private enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    CHAT("chat", "Chat", Icons.Rounded.Chat),
    MODELS("models", "Models", Icons.Rounded.Memory),
    TOOLS("tools", "Tools", Icons.Rounded.Build),
    USAGE("usage", "Usage", Icons.Rounded.BarChart),
    SETTINGS("settings", "Settings", Icons.Rounded.Settings),
}

/** A twelfth of the screen: enough rise to read as arrival, small enough to stay quick. */
private const val TAB_SLIDE = 12

/**
 * Moves to a tab, from the bar or from anywhere else that sends the user to one.
 *
 * One function because there were two ways to reach a tab and only the bar used the options
 * that make it a switch rather than a push. Tapping the model name in the chat header called
 * plain navigate, so Models went on top of Chat instead of replacing it, and the tab the bar
 * highlighted was no longer the tab the back stack thought it was on. Tapping Chat then
 * landed on a second copy of Chat, and from there the way back was a back gesture nobody
 * would guess at from a bottom bar.
 *
 * saveState and restoreState are what let Discover keep its search results and Chat keep its
 * scroll position while the user goes to look at something else.
 */
private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

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
            // A hairline and the page's own colour, rather than a raised slab. Every screen
            // above it is drawn on `background`, so a bar in `surfaceContainer` read as a
            // separate panel bolted to the bottom. One line is enough to say where the page
            // ends, and it lets the five destinations look like part of the app rather than
            // a tray the app is sitting in.
            Column {
                HorizontalDivider(
                    // outline, not outlineVariant. The palette calls the variant decorative
                    // and says it is never the only boundary, and measured against this
                    // background it is 1.3:1, which is a line nobody can see. This one is
                    // 3.8:1 in light and 4.3:1 in dark: still a hairline, actually there.
                    thickness = Dp.Hairline,
                    color = MaterialTheme.colorScheme.outline,
                )
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.background,
                    tonalElevation = 0.dp,
                ) {
                    Destination.entries.forEach { destination ->
                        val selected = currentDestination?.hierarchy
                            ?.any { it.route == destination.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.switchTab(destination.route) },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(destination.label) },
                            // Brass, at container weight. The palette's first rule is that
                            // the accent means "you can act here" and names the active tab
                            // as one of the four places it belongs, so a neutral tab would
                            // have been a quieter bar bought by breaking the design system.
                            // What was wrong was the weight: a filled accent slab with a
                            // reversed icon reads as a button dropped into a row of icons.
                            // The container is a warm tint at 1.4:1 against the bar, and the
                            // icon and label on top of it are the accent itself at 6.9:1.
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor =
                                MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
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
                    onOpenModels = { navController.switchTab(Destination.MODELS.route) },
                    onOpenConversation = chatViewModel::openConversation,
                    onDeleteConversation = chatViewModel::deleteConversation,
                    onSavePreferences = chatViewModel::savePreferences,
                    onResetPreferences = chatViewModel::resetPreferences,
                    onAttach = chatViewModel::attach,
                    onAttachDocument = chatViewModel::stageDocument,
                    onRemoveDocument = { chatViewModel.stageDocument(null) },
                    onRemoveStaged = chatViewModel::removeStaged,
                    onToggleReadAloud = mediaViewModel::toggleReadAloud,
                    isSpeaking = isSpeaking,
                    newCaptureUri = mediaViewModel::newCaptureUri,
                    dictation = dictation,
                    canDictate = mediaViewModel.canDictate,
                    onDictate = mediaViewModel::toggleDictation,
                    onMode = chatViewModel::setMode,
                    onApproval = chatViewModel::resolveApproval,
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

            composable(Destination.MODELS.route) {
                val tab = rememberModelsTab()
                val tabs: @Composable () -> Unit =
                    { ModelsTabs(selected = tab.selected, onSelect = tab.select) }

                when (tab.selected) {
                    ModelsTab.INSTALLED -> {
                        val state by modelsViewModel.uiState.collectAsStateWithLifecycle()

                        ModelsScreen(
                            state = state,
                            onUse = { model ->
                                // Keeps whatever chat is open. Switching model partway
                                // through a conversation is a normal thing to do, and
                                // throwing the conversation away to do it is not a trade
                                // anyone would choose.
                                chatViewModel.loadModel(model.file, keepConversation = true)
                                navController.switchTab(Destination.CHAT.route)
                            },
                            onDelete = modelsViewModel::delete,
                            onCancelDownload = modelsViewModel::cancel,
                            tabs = tabs,
                        )
                    }

                    ModelsTab.DISCOVER -> {
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
                                state.files.firstOrNull {
                                    it.file.path == path
                                }?.file?.let { file ->
                                    modelsViewModel.download(
                                        repoId,
                                        path,
                                        file.sizeBytes,
                                        file.sha256,
                                    )
                                    // The projector is not optional for a multimodal model:
                                    // without it the weights load but every attachment is
                                    // refused, which reads as a broken app rather than a
                                    // missing file.
                                    state.detail?.pairedProjector(file)?.let { projector ->
                                        modelsViewModel.downloadProjector(
                                            repoId,
                                            projector,
                                            file.fileName,
                                        )
                                    }
                                    // Back to Installed, which is where the download it has
                                    // just started shows its progress.
                                    tab.select(ModelsTab.INSTALLED)
                                }
                            },
                            tabs = tabs,
                        )
                    }
                }
            }

            composable(Destination.TOOLS.route) {
                val viewModel: ToolsViewModel = hiltViewModel()
                val state by viewModel.uiState.collectAsStateWithLifecycle()

                ToolsScreen(
                    state = state,
                    onToggle = viewModel::setEnabled,
                    onSearxUrl = viewModel::setSearxUrl,
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
