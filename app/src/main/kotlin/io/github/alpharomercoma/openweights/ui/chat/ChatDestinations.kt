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

package io.github.alpharomercoma.openweights.ui.chat

/**
 * Everywhere you can go from the conversation.
 *
 * One parameter rather than four, because `ChatScreen` already takes more arguments than
 * anything else in this app and four more callbacks would be four more things every test and
 * preview has to name. Defaulted to nothing, so a caller that only wants a chat screen still
 * gets one, and so the drawer footer could be built and reviewed before the routes behind it
 * existed.
 *
 * These are the destinations that used to be tabs. They are pushed over the conversation now
 * and come back to it, which is the difference between a place you visit and a place you
 * switch to.
 */
data class ChatDestinations(
    val onOpenGallery: () -> Unit = {},
    val onOpenTools: () -> Unit = {},
    val onOpenUsage: () -> Unit = {},
    /** Everything running on its own, and the one place to stop it. */
    val onOpenWatches: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    /** From inside the model picker, since browsing is what you do when none of yours fit. */
    val onBrowseModels: () -> Unit = {},
    /** The installed list, which owns deleting a model and watching one arrive. */
    val onManageModels: () -> Unit = {},
    /** On-device image generation with diffusion models. */
    val onOpenGenerate: () -> Unit = {},
)
