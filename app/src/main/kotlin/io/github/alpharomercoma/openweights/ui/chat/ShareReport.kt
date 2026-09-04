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

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent

/**
 * Hands a finished report to whatever the reader wants to do with it.
 *
 * The share sheet rather than a destination of our own choosing, and that is the whole
 * design. A hard-coded target would have to be a public issue tracker, which needs an
 * account most people do not have and would publish a model reply plus whatever context the
 * reader put in their note under their real name. Handing it to Android instead means the
 * destination is a decision made by the person who wrote it, on a screen that shows them
 * the text first, with cancelling always available.
 *
 * This is also the only shape that keeps the app's promise intact. OpenWeights still has no
 * server and still sends nothing on its own: what leaves the phone leaves because somebody
 * chose an app to send it to.
 *
 * A device with nothing that accepts text is not a crash. It is a phone that cannot share,
 * and the report simply does not go anywhere, which is where it was going before this
 * existed.
 */
fun Context.shareReport(modelName: String, reason: ReportReason, replyText: String, note: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, reportSubject(modelName))
        putExtra(Intent.EXTRA_TEXT, reportText(modelName, reason, replyText, note))
    }
    try {
        startActivity(Intent.createChooser(intent, null))
    } catch (_: ActivityNotFoundException) {
        // Nothing installed that takes text. Nowhere to go, and nothing to say about it
        // that the empty chooser would not have said louder.
    }
}

/** The one line a mail client puts in its subject field. */
internal fun reportSubject(modelName: String): String = "OpenWeights report: $modelName"

/**
 * The report as text, which is the only form it has now that nothing stores it.
 *
 * Labelled and in a fixed order so that two reports about the same model can be read side
 * by side, and so the reply is unmistakably the model's words rather than the reader's. The
 * note is left out entirely when it is blank: an empty "Note:" heading tells a reader that
 * somebody declined to write one, which is not information.
 */
internal fun reportText(
    modelName: String,
    reason: ReportReason,
    replyText: String,
    note: String,
): String = buildString {
    appendLine("Model: $modelName")
    appendLine("Reason: ${reason.label}")
    note.trim().takeIf { it.isNotEmpty() }?.let { appendLine("Note: $it") }
    appendLine()
    appendLine("Reply:")
    append(replyText.trim().ifEmpty { "(an empty reply)" })
}
