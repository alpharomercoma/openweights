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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.alpharomercoma.openweights.core.common.model.ModelFormat
import io.github.alpharomercoma.openweights.core.designsystem.theme.Motion
import io.github.alpharomercoma.openweights.ui.archive.ArchiveViewModel
import io.github.alpharomercoma.openweights.ui.archive.ArchivedScreen
import io.github.alpharomercoma.openweights.ui.canvas.CanvasScreen
import io.github.alpharomercoma.openweights.ui.canvas.CanvasViewModel
import io.github.alpharomercoma.openweights.ui.chat.ChatDestinations
import io.github.alpharomercoma.openweights.ui.chat.ChatScreen
import io.github.alpharomercoma.openweights.ui.chat.ChatViewModel
import io.github.alpharomercoma.openweights.ui.chat.ConversationActions
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
import io.github.alpharomercoma.openweights.ui.tools.ToolsScreen
import io.github.alpharomercoma.openweights.ui.tools.ToolsViewModel
import io.github.alpharomercoma.openweights.ui.watch.WatchScreen
import io.github.alpharomercoma.openweights.ui.watch.WatchViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The routes, which are no longer tabs.
 *
 * There was a bottom bar here with five destinations and a comment arguing that all five
 * earned their place. They did not. Chat is what the app is for and the other four are
 * errands you run occasionally: a bar gave a quarter of the bottom edge, permanently, to
 * things a person opens once a week, and it made the conversation one tab among five rather
 * than the surface the app is.
 *
 * Chat is the only destination now. The rest are pushed over it and come back, which is what
 * a back arrow means and what a tab never did.
 */
private object Routes {
    const val CHAT = "chat"
    const val MODELS = "models"
    const val DISCOVER = "discover"
    const val TOOLS = "tools"
    const val USAGE = "usage"
    const val WATCHES = "watches"
    const val ARCHIVE = "archive"
    const val SETTINGS = "settings"
    const val CANVAS = "canvas"
}

/** A twelfth of the screen: enough travel to read as arrival, small enough to stay quick. */
private const val PUSH_SLIDE = 12

/**
 * Pushes a screen over the conversation.
 *
 * Plain navigate, where the bar needed `popUpTo(start) { saveState }` to make a tab replace
 * a tab. That option is exactly what must not happen now: Settings sits on top of the chat
 * and returns to it, so the chat is never torn down and the back gesture means the one
 * obvious thing. `launchSingleTop` only so a double tap in the drawer cannot stack two.
 */
private fun NavHostController.push(route: String) {
    navigate(route) { launchSingleTop = true }
}

@Composable
fun OpenWeightsApp(modifier: Modifier = Modifier) {
    val navController = rememberNavController()

    // Chat and Models get one view model each, hoisted above the NavHost, so a download
    // keeps running and a loaded model stays loaded while the user moves around the app.
    val chatViewModel: ChatViewModel = hiltViewModel()
    val mediaViewModel: MediaViewModel = hiltViewModel()
    val modelsViewModel: ModelsViewModel = hiltViewModel()

    // A download, and not a reply.
    //
    // It used to be either, on the argument that both are a wait. They are not the same
    // wait: a download runs for minutes and is the thing anybody leaves the app during,
    // while a reply on this hardware is tens of seconds with the phone still in the hand.
    // Asking during generation put a system dialog over the first finished answer a new
    // user ever saw, which is both the worst moment for it and the moment its own reason
    // had just stopped applying.
    //
    // A flow rather than a collected state, because the chat state changes with every token
    // and nothing above here should recompose for that.
    AskAboutNotifications(
        waiting = remember(modelsViewModel) {
            modelsViewModel.uiState
                .map { it.downloads.isNotEmpty() }
                .distinctUntilChanged()
        },
    )

    // No Scaffold here any more. With the bar gone and zero insets it supplied nothing but a
    // container colour, and all six screens set that on their own Scaffold already.
    NavHost(
        navController = navController,
        startDestination = Routes.CHAT,
        modifier = modifier.fillMaxSize(),
        // Compose Navigation defaults to 700 ms fades, which reads as the app thinking when
        // it is only moving. These slide sideways now rather than rising: a rise said "a tab
        // arrived", and what happens now is a screen coming in over the conversation and
        // leaving again, which is a horizontal idea.
        enterTransition = {
            fadeIn(Motion.quick()) + slideInHorizontally(Motion.quick()) { it / PUSH_SLIDE }
        },
        exitTransition = { fadeOut(Motion.instant()) },
        popEnterTransition = { fadeIn(Motion.quick()) },
        popExitTransition = {
            fadeOut(Motion.instant()) + slideOutHorizontally(Motion.quick()) { it / PUSH_SLIDE }
        },
    ) {
        composable(Routes.CHAT) {
            // The canvas opens itself when the model shows something new — one push per
            // show call, tracked by generation so stepping back does not bounce the user
            // straight back in while the model keeps editing.
            val canvasViewModel: CanvasViewModel = hiltViewModel()
            val canvasShowing by canvasViewModel.showing.collectAsStateWithLifecycle()
            var openedGeneration by rememberSaveable { mutableIntStateOf(0) }
            LaunchedEffect(canvasShowing?.generation) {
                val generation = canvasShowing?.generation ?: return@LaunchedEffect
                if (generation > openedGeneration) {
                    openedGeneration = generation
                    navController.push(Routes.CANVAS)
                }
            }

            // Collected here rather than above the NavHost. This scope already
            // recomposes on every token, so a download ticking costs nothing extra in
            // it, whereas hoisting it would make the whole shell recompose for both.
            val modelsUiState by modelsViewModel.uiState.collectAsStateWithLifecycle()
            val installedModels by remember { derivedStateOf { modelsUiState.models } }
            // Derived the same way and for the same reason: the picker wants logos and the
            // shell must not recompose because one arrived.
            val publisherAvatars by remember { derivedStateOf { modelsUiState.avatars } }
            val activeDownloads by remember { derivedStateOf { modelsUiState.downloads } }

            val state by chatViewModel.uiState.collectAsStateWithLifecycle()
            val isSpeaking by mediaViewModel.isSpeaking.collectAsStateWithLifecycle()
            val dictation by mediaViewModel.dictationState.collectAsStateWithLifecycle()
            val reportViewModel: ReportViewModel = hiltViewModel()
            // Collected from the board rather than mirrored into the chat state: it is
            // already a flow, and a second copy would be a second thing to keep in step.
            val plan by chatViewModel.planning.plan.collectAsStateWithLifecycle()
            val goal by chatViewModel.goal.collectAsStateWithLifecycle()
            val question by chatViewModel.asking.pending.collectAsStateWithLifecycle()
            val chatSearch by chatViewModel.search.state.collectAsStateWithLifecycle()

            LaunchedEffect(Unit) {
                // The view model outlives the composition, so returning to this tab
                // must not reload the model and wipe the conversation.
                if (!chatViewModel.hasModel) chatViewModel.loadDefaultModel()
            }

            ChatScreen(
                canvasActive = canvasShowing != null,
                onOpenCanvas = { navController.push(Routes.CANVAS) },
                state = state,
                onSend = chatViewModel::send,
                onStop = {
                    if (goal?.isRunning == true) chatViewModel.stopGoal() else chatViewModel.stop()
                },
                onRegenerate = chatViewModel::regenerate,
                onNewChat = chatViewModel::newChat,
                onCompact = chatViewModel::compactNow,
                onGoal = chatViewModel::startGoal,
                onResearch = chatViewModel::startResearch,
                onEditAndResend = chatViewModel::editAndResend,
                onBranchFrom = chatViewModel::branchFrom,
                destinations = ChatDestinations(
                    onOpenTools = { navController.push(Routes.TOOLS) },
                    onOpenUsage = { navController.push(Routes.USAGE) },
                    onOpenWatches = { navController.push(Routes.WATCHES) },
                    onOpenArchive = { navController.push(Routes.ARCHIVE) },
                    onOpenSettings = { navController.push(Routes.SETTINGS) },
                    onBrowseModels = { navController.push(Routes.DISCOVER) },
                    onManageModels = { navController.push(Routes.MODELS) },
                ),
                installedModels = installedModels,
                activeDownloads = activeDownloads,
                publisherAvatars = publisherAvatars,
                onSelectModel = { model ->
                    chatViewModel.loadModel(model.file, keepConversation = true)
                },
                onUnloadModel = chatViewModel::unloadModel,
                onOpenConversation = {
                    // The search has done its job once a chat is open, and leaving it set
                    // meant reopening the drawer later showed a list still filtered by a
                    // word the user had stopped thinking about.
                    chatViewModel.search.clear()
                    chatViewModel.openConversation(it)
                },
                chatSearch = chatSearch,
                onSearchConversations = chatViewModel.search::search,
                conversationActions = ConversationActions(
                    onRename = chatViewModel::renameConversation,
                    onPin = chatViewModel::setConversationPinned,
                    onArchive = chatViewModel::setConversationArchived,
                    onDelete = {
                        chatViewModel.deleteConversation(it)
                        // Results are a list rather than a live query, so the row it just
                        // deleted would otherwise stay on screen, tappable, opening nothing.
                        // The other three actions need no such help: the row reads its
                        // title and its state from the live list. See `SearchResults`.
                        chatViewModel.search.forget(it)
                    },
                ),
                onSavePreferences = chatViewModel::savePreferences,
                onResetPreferences = chatViewModel::resetPreferences,
                onAttach = chatViewModel::attach,
                onAttachAll = chatViewModel::attachAll,
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
                plan = plan,
                onTickStep = chatViewModel.planning::tick,
                goal = goal,
                onStopGoal = chatViewModel::stopGoal,
                onSteerGoal = chatViewModel::steerGoal,
                onDismissGoal = chatViewModel::dismissGoal,
                question = question,
                onAnswerQuestion = chatViewModel.asking::answer,
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

        composable(Routes.MODELS) {
            val state by modelsViewModel.uiState.collectAsStateWithLifecycle()

            ModelsScreen(
                state = state,
                onUse = { model ->
                    // Keeps whatever chat is open. Switching model partway through a
                    // conversation is a normal thing to do, and throwing the
                    // conversation away to do it is not a trade anyone would choose.
                    chatViewModel.loadModel(model.file, keepConversation = true)
                    navController.popBackStack(Routes.CHAT, inclusive = false)
                },
                onDelete = modelsViewModel::delete,
                onCancelDownload = modelsViewModel::cancel,
                onBack = navController::popBackStack,
            )
        }
        composable(Routes.DISCOVER) {
            val viewModel: DiscoverViewModel = hiltViewModel()
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val modelsState by modelsViewModel.uiState.collectAsStateWithLifecycle()

            DiscoverScreen(
                state = state,
                onQueryChange = viewModel::onQueryChange,
                onSearch = { viewModel.search() },
                onLoadMore = viewModel::loadMore,
                onSortChange = viewModel::onSortChange,
                onRuntimeToggled = viewModel::onRuntimeToggled,
                onFiltersChange = viewModel::onQueryChange,
                onPhoneSizedChange = viewModel::onPhoneSizedChange,
                onOfficialOnlyChange = viewModel::onOfficialOnlyChange,
                onRecommendedOnlyChange = viewModel::onRecommendedOnlyChange,
                onClearFilters = viewModel::clearFilters,
                onOpenModel = viewModel::openModel,
                onCloseModel = viewModel::closeModel,
                onContextLengthChange = viewModel::onContextLengthChange,
                // What is already being fetched, so this screen stops offering a download
                // for a file it has one running for. Keyed by destination filename, which
                // is what ModelsViewModel keys a download by.
                downloading = modelsState.downloads
                    .filterNot { it.error != null }
                    .associate { it.key to it.fraction },
                onDownload = { repoId, path ->
                    state.files.firstOrNull { it.file.path == path }?.file?.let { file ->
                        val tokenizer = state.detail?.tokenizerFor(file)
                        if (ModelFormat.of(file.fileName) == ModelFormat.PTE && tokenizer != null) {
                            // Compiled weights are an install of two files, renamed on the
                            // way in. Neither is usable alone.
                            modelsViewModel.downloadCompiled(repoId, file, tokenizer)
                        } else {
                            modelsViewModel.download(repoId, path, file.sizeBytes, file.sha256)
                            // The projector is not optional for a multimodal model: without
                            // it the weights load but every attachment is refused, which
                            // reads as a broken app rather than a missing file.
                            state.detail?.pairedProjector(file)?.let { projector ->
                                modelsViewModel.downloadProjector(repoId, projector, file.fileName)
                            }
                        }
                        // To the installed list, which is where the download it has just
                        // started shows its progress.
                        navController.push(Routes.MODELS)
                    }
                },
                onBack = navController::popBackStack,
            )
        }
        composable(Routes.TOOLS) {
            val viewModel: ToolsViewModel = hiltViewModel()
            val state by viewModel.uiState.collectAsStateWithLifecycle()

            ToolsScreen(
                state = state,
                onToggle = viewModel::setEnabled,
                onChooseFolder = viewModel::chooseFolder,
                onForgetFolder = viewModel::forgetFolder,
                onEngineEnabled = viewModel::setEngineEnabled,
                onProxy = viewModel::setProxy,
                onBack = navController::popBackStack,
            )
        }

        composable(Routes.USAGE) {
            val viewModel: DashboardViewModel = hiltViewModel()
            val summary by viewModel.uiState.collectAsStateWithLifecycle()

            DashboardScreen(summary = summary, onBack = navController::popBackStack)
        }

        composable(Routes.ARCHIVE) {
            val viewModel: ArchiveViewModel = hiltViewModel()
            val state by viewModel.uiState.collectAsStateWithLifecycle()

            ArchivedScreen(
                state = state,
                onSearch = viewModel::search,
                onOpen = {
                    chatViewModel.openConversation(it)
                    navController.popBackStack()
                },
                // The same four actions the drawer offers, through the same view model:
                // deleting has to collect the files a conversation's messages referred to
                // before the rows naming them are gone, and that lives in one place.
                actions = ConversationActions(
                    onRename = chatViewModel::renameConversation,
                    onPin = chatViewModel::setConversationPinned,
                    onArchive = chatViewModel::setConversationArchived,
                    onDelete = chatViewModel::deleteConversation,
                ),
                onBack = navController::popBackStack,
            )
        }

        composable(Routes.CANVAS) {
            CanvasScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.WATCHES) {
            val viewModel: WatchViewModel = hiltViewModel()
            val watches by viewModel.state.collectAsStateWithLifecycle()

            WatchScreen(
                watches = watches,
                onStop = viewModel::stop,
                onForget = viewModel::forget,
                onBack = navController::popBackStack,
            )
        }

        composable(Routes.SETTINGS) {
            val viewModel: SettingsViewModel = hiltViewModel()
            val state by viewModel.uiState.collectAsStateWithLifecycle()

            SettingsScreen(
                state = state,
                onSaveToken = viewModel::saveToken,
                onClearToken = viewModel::clearToken,
                onSelectTheme = viewModel::setTheme,
                onBack = navController::popBackStack,
            )
        }
    }
}
