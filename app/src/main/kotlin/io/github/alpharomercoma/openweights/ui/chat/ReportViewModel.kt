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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.alpharomercoma.openweights.core.data.ContentReportRepository
import io.github.alpharomercoma.openweights.core.data.ReportReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Reporting a reply, kept apart from the chat itself.
 *
 * Its own view model rather than another responsibility on the chat's, which already
 * coordinates the engine, the transcript, storage and compaction. Reporting shares nothing
 * with any of that: it takes a reply and a reason and writes a row.
 */
@HiltViewModel
class ReportViewModel @Inject constructor(private val reports: ContentReportRepository) :
    ViewModel() {
    private val _confirmation = MutableStateFlow<String?>(null)

    /** Set once a report is filed, so the screen can say so and then forget. */
    val confirmation: StateFlow<String?> = _confirmation.asStateFlow()

    /**
     * Files a report against something a model said.
     *
     * Stored on the device and sent nowhere, because there is nowhere to send it. Play
     * requires an app that generates AI content to offer this without the user leaving the
     * app, and it is also the only signal about model behaviour that an app measuring
     * nothing remotely can have.
     */
    fun report(modelName: String?, replyText: String, reason: ReportReason, note: String) {
        // A report has to name the model, or it says nothing that could be acted on.
        val model = modelName?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            reports.report(
                modelName = model,
                reason = reason,
                replyText = replyText,
                note = note,
            )
            _confirmation.value = "Reported. It stays on this device."
        }
    }

    fun dismissConfirmation() {
        _confirmation.value = null
    }
}
