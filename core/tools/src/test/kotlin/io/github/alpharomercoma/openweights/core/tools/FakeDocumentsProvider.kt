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

import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import org.robolectric.Robolectric
import java.io.File
import java.io.FileNotFoundException

/**
 * A [DocumentsProvider] over a directory on the host, enough of one for [Workspace] to use.
 *
 * Document ids are paths under that directory, so the root is a name and a child's id is
 * its parent's with a segment added, which is all the tree check needs. Two switches stand
 * in for the ways real providers differ: one refuses to open certain names for writing,
 * one refuses to rename at all, as a provider without `FLAG_SUPPORTS_RENAME` does.
 */
class FakeDocumentsProvider : DocumentsProvider() {
    /** Which names this provider will not open for writing. */
    var refusesWritesTo: (String) -> Boolean = { false }

    var renames = true

    /** Every open as `name:mode`, so a test can say what was never opened for writing. */
    val opens = mutableListOf<String>()

    // Under the per-test cache directory, which Robolectric makes fresh and removes.
    private val base: File by lazy {
        File(requireNotNull(context).cacheDir, "documents").also { File(it, ROOT).mkdirs() }
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<String>?): Cursor =
        MatrixCursor(projection ?: COLUMNS)

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor =
        MatrixCursor(projection ?: COLUMNS).apply { add(documentId) }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?,
    ): Cursor = MatrixCursor(projection ?: COLUMNS).apply {
        fileFor(parentDocumentId).list().orEmpty().sorted()
            .forEach { add("$parentDocumentId/$it") }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId.startsWith("$parentDocumentId/")

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val file = File(fileFor(parentDocumentId), displayName)
        val made = if (mimeType == Document.MIME_TYPE_DIR) file.mkdir() else file.createNewFile()
        if (!made) throw FileNotFoundException("Could not create $displayName")
        return "$parentDocumentId/$displayName"
    }

    override fun deleteDocument(documentId: String) {
        if (!fileFor(documentId).deleteRecursively()) throw FileNotFoundException(documentId)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        if (!renames) throw UnsupportedOperationException("Rename not supported")
        val from = fileFor(documentId)
        if (!from.renameTo(File(from.parentFile, displayName))) {
            throw FileNotFoundException(documentId)
        }
        return "${documentId.substringBeforeLast('/')}/$displayName"
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val name = documentId.substringAfterLast('/')
        opens += "$name:$mode"
        if ('w' in mode && refusesWritesTo(name)) {
            throw FileNotFoundException("$name is not for writing")
        }
        return ParcelFileDescriptor.open(fileFor(documentId), ParcelFileDescriptor.parseMode(mode))
    }

    private fun fileFor(documentId: String) = File(base, documentId)

    private fun MatrixCursor.add(documentId: String) {
        val file = fileFor(documentId)
        val row = mapOf(
            Document.COLUMN_DOCUMENT_ID to documentId,
            Document.COLUMN_DISPLAY_NAME to file.name,
            Document.COLUMN_MIME_TYPE to
                if (file.isDirectory) Document.MIME_TYPE_DIR else "text/plain",
            Document.COLUMN_SIZE to file.length(),
        )
        addRow(columnNames.map(row::get))
    }

    companion object {
        private const val AUTHORITY = "io.github.alpharomercoma.openweights.test.documents"
        private const val ROOT = "root"
        private val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
        )

        /** The uri a picker hands back for the root, which is what [WorkspaceGrant.remember] takes. */
        val TREE: Uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT)

        /** Registers a fresh provider with Robolectric, declared the way a DocumentsProvider insists on. */
        fun register(): FakeDocumentsProvider {
            val info = ProviderInfo().apply {
                authority = AUTHORITY
                name = FakeDocumentsProvider::class.java.name
                exported = true
                grantUriPermissions = true
                readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
                writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
            }
            return Robolectric.buildContentProvider(FakeDocumentsProvider::class.java)
                .create(info)
                .get()
        }
    }
}
