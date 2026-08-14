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

package io.github.alpharomercoma.openweights.core.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** What the app may still do inside the folder the user picked. */
enum class GrantState {
    /** Nobody has chosen a folder. The file tools are not offered at all. */
    NONE,

    /** A folder was chosen, and the grant no longer holds. Choosing again is the fix. */
    LOST,

    /** Readable, but the provider will not accept new files. */
    READ_ONLY,

    READ_WRITE,
}

/**
 * The one folder the model may look inside, and whether that is still true.
 *
 * Kept beside [ToolSwitches] rather than in `core:data` with the other settings, and on
 * `SharedPreferences` rather than DataStore, because [Tool.isAvailable] is an ordinary
 * property read while a turn is being assembled. A flow cannot answer that question without
 * blocking on it, and blocking a turn on a disk read to decide whether to mention a tool is
 * worse than the duplication.
 *
 * The stored string is not the permission. A user can revoke the grant in Settings, unmount
 * the card it pointed at, or uninstall the provider that served it, and none of those tell
 * the app anything: the string sits there afterwards looking exactly as valid as it did the
 * day it was written. So the grant is re-read from the system every time it is asked for,
 * which is cheap, rather than cached at startup, which would be wrong within the hour.
 */
@Singleton
class WorkspaceGrant @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val store = context.getSharedPreferences("workspace", Context.MODE_PRIVATE)

    /** The folder to work in, or null when there is not one this app may still use. */
    val folder: Uri?
        get() = when (state()) {
            GrantState.READ_ONLY, GrantState.READ_WRITE -> stored()
            GrantState.NONE, GrantState.LOST -> null
        }

    /**
     * What the app can do in that folder as things stand.
     *
     * Separated from [folder] because the three ways of having nothing are three different
     * sentences to the user: never chose one, chose one that has since gone, and chose one
     * that will not take new files. Reporting all of them as "no folder" sends someone to
     * the picker to fix something the picker cannot fix.
     */
    fun state(): GrantState {
        val tree = stored() ?: return GrantState.NONE
        val held = context.contentResolver.persistedUriPermissions
            .firstOrNull { it.uri == tree } ?: return GrantState.LOST
        return when {
            !held.isReadPermission -> GrantState.LOST
            held.isWritePermission -> GrantState.READ_WRITE
            else -> GrantState.READ_ONLY
        }
    }

    /**
     * Takes the grant the picker just handed over and keeps it across restarts.
     *
     * Without [android.content.ContentResolver.takePersistableUriPermission] the grant dies
     * with the activity, which is the thing that made attachments have to be copied into the
     * app in the first place. A workspace cannot be copied in: the point is that the files
     * stay where the user put them.
     */
    fun remember(tree: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(tree, flags) }
        store.edit { putString(KEY_TREE, tree.toString()) }
    }

    /** Hands the folder back, both the record of it and the permission itself. */
    fun forget() {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        stored()?.let {
            runCatching { context.contentResolver.releasePersistableUriPermission(it, flags) }
        }
        store.edit { remove(KEY_TREE) }
    }

    private fun stored(): Uri? = store.getString(KEY_TREE, null)?.let(Uri::parse)

    private companion object {
        const val KEY_TREE = "tree_uri"
    }
}
