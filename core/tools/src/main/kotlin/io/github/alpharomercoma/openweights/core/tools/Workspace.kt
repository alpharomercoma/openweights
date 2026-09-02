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
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Reader
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fills as much of [buffer] as the stream will part with.
 *
 * One read rarely returns everything asked for, and a single call would quietly return a
 * fraction of the window and look like a short file.
 */
/**
 * What to tell the provider a new file is, worked out from its name.
 *
 * Not a nicety: providers use this to decide whether the display name needs an extension
 * *appended*. ExternalStorageProvider asks the platform MIME map what extension fits the
 * declared type, and a name whose extension does not match gets the "right" one glued on.
 * Declaring everything text/plain is how `slides.md` came back as `slides.md.txt` on a
 * real device - and then the replace path could not find `slides.md` and saved a
 * `slides.md (1).txt` beside it, so the deck the canvas was pointed at never changed. So
 * the type is asked of the same platform map the provider consults, extension for
 * extension, and a name the map does not know is declared a plain byte stream, which no
 * provider decorates.
 */
private fun mediaTypeFor(name: String): String {
    val extension = name.substringAfterLast('.', "").lowercase()
    return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        ?: "application/octet-stream"
}

/**
 * A name for the sibling new text is staged in before it replaces a file: hidden by the
 * dot, unique by the suffix, and ending in a way that tells anyone who finds one after a
 * crash what it is and which file it was for.
 */
private fun stagingNameFor(name: String): String =
    ".$name.${UUID.randomUUID().toString().substringBefore('-')}.tmp"

internal fun Reader.readAsMuchAs(buffer: CharArray): Int {
    var filled = 0
    while (filled < buffer.size) {
        val read = read(buffer, filled, buffer.size - filled)
        if (read < 0) break
        filled += read
    }
    return filled
}

/**
 * Advances exactly [count] characters unless the stream ends.
 *
 * [Reader.skip] is explicitly allowed to advance fewer characters than requested. Treating
 * one call as exact repeats part of the previous page with providers that use short skips.
 * A zero skip falls back to one read so a legal but unhelpful provider cannot spin forever.
 */
@Suppress("LoopWithTooManyJumpStatements")
internal fun Reader.skipAsMuchAs(count: Long): Long {
    var skipped = 0L
    while (skipped < count) {
        val step = skip(count - skipped)
        if (step > 0) {
            skipped += step
            continue
        }
        if (read() < 0) break
        skipped++
    }
    return skipped
}

/** Something found in the shared folder, named the way the model was taught to name it. */
data class Entry(
    /** Relative to the shared folder, which is the only kind of path the model ever sees. */
    val path: String,
    val documentId: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val mediaType: String,
)

/** A hidden sibling of a file being replaced, where the new text lands before the old goes. */
private class Staged(val uri: Uri, val name: String)

/** What creating a file came to: the name the folder gave it, and whether the text got in. */
private class Landed(val served: String, val written: Boolean)

/**
 * The folder the user shared, and the only way into it.
 *
 * Everything the file tools do goes through here, so there is one place that knows how a
 * name the model wrote becomes a document a provider will answer for. That translation is
 * the whole security boundary. [workspaceSegments] is the half of it that decides what a
 * path may mean; this half is the questions put to the provider, and since the real ones
 * exist only on a device, `WorkspaceReplaceTest` puts them to a small provider of its own.
 *
 * Children are listed with a single cursor query per directory rather than through
 * `DocumentFile`, which issues a query per child to populate each object it returns. On a
 * folder of a few thousand entries that difference has been measured at twenty seconds
 * against under one, and twenty seconds inside a turn is not a tool, it is a hang.
 */
@Singleton
class Workspace @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val grant: WorkspaceGrant,
) {
    /** Whether there is a folder to work in at all, asked before any tool is offered. */
    val isReady: Boolean get() = grant.folder != null

    /** Whether the provider will accept a new file, which not every one of them will. */
    val acceptsNewFiles: Boolean get() = grant.state() == GrantState.READ_WRITE

    /** What to tell the model when there is nothing to work in, in its own words. */
    /**
     * The same answer as [unavailable], typed, for a tool about to hand it to the runner.
     *
     * Null when there is a folder, which reads as "carry on". A missing folder is refused
     * rather than merely failed: it is not going to appear part way through a turn, and a
     * model told "no folder has been shared" will otherwise ask the next file tool the same
     * question and spend the turn's whole budget discovering the same thing three times.
     */
    fun refusal(): ToolExecution? = unavailable().takeIf { it.isNotEmpty() }
        ?.let(ToolExecution::rejected)

    fun unavailable(): String = when (grant.state()) {
        GrantState.NONE ->
            "No folder has been shared with this app. Ask the user to choose one under Tools."
        GrantState.LOST ->
            "The shared folder is no longer reachable. It may have been disconnected, or the " +
                "permission withdrawn. Ask the user to choose it again under Tools."
        GrantState.READ_ONLY, GrantState.READ_WRITE -> ""
    }

    /**
     * Walks a path down from the shared folder, a name at a time.
     *
     * Never builds a child's identifier by joining strings onto its parent's. A provider's
     * document ids are its own business and need share nothing with each other, so the only
     * way to learn a child's id is to ask the parent for its children and match on the name
     * it gives back. That is also what makes the walk safe: at no point is there an id in
     * hand that the granted folder did not hand over.
     */
    suspend fun resolve(path: String): Entry? = withContext(Dispatchers.IO) {
        val tree = grant.folder ?: return@withContext null
        val segments = path.workspaceSegments() ?: return@withContext null

        var parentId = DocumentsContract.getTreeDocumentId(tree)
        var found: Entry? = null
        for ((index, name) in segments.withIndex()) {
            val parentPath = segments.take(index).joinToString("/")
            found = children(tree, parentId, parentPath).firstOrNull { it.name == name }
                ?: return@withContext null
            parentId = found.documentId
        }
        found
    }

    /**
     * Everything directly inside a directory, in one query.
     *
     * [under] is the path of the directory itself, carried through only so each entry can
     * report where it is in the terms the model uses. The provider never sees it.
     */
    suspend fun children(tree: Uri, parentId: String, under: String): List<Entry> =
        withContext(Dispatchers.IO) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            runCatching {
                // The query-arguments form, which is the one the framework carries to a
                // provider: the selection form is folded into it on every device, and a
                // DocumentsProvider refuses the selection form when handed it directly,
                // which is what a host test does.
                context.contentResolver.query(childrenUri, PROJECTION, null, null)
                    ?.use { cursor -> cursor.entries(under) }
            }.getOrNull().orEmpty()
        }

    /** The children of a directory already resolved, which is what a search walks. */
    suspend fun children(of: Entry): List<Entry> {
        val tree = grant.folder ?: return emptyList()
        return children(tree, of.documentId, of.path)
    }

    /** The children of the shared folder itself, where every walk and every search starts. */
    suspend fun root(): List<Entry> {
        val tree = grant.folder ?: return emptyList()
        return children(tree, DocumentsContract.getTreeDocumentId(tree), "")
    }

    /** The document uri for an entry, which is the only thing a stream can be opened on. */
    fun uriFor(entry: Entry): Uri? =
        grant.folder?.let { DocumentsContract.buildDocumentUriUsingTree(it, entry.documentId) }

    /**
     * Makes a file that was not there before, and says where it went.
     *
     * Creation only, which is what keeps this tool out of the class of things that can
     * destroy work: there is no path through here that opens an existing document for
     * writing, so an interrupted call leaves a short file rather than an empty one where
     * something used to be.
     */
    suspend fun create(parent: Entry?, name: String, mediaType: String): Uri? =
        withContext(Dispatchers.IO) {
            val tree = grant.folder ?: return@withContext null
            val parentId = parent?.documentId ?: DocumentsContract.getTreeDocumentId(tree)
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
            runCatching {
                DocumentsContract.createDocument(
                    context.contentResolver,
                    parentUri,
                    mediaType,
                    name,
                )
            }.getOrNull()
        }

    /** The name the provider actually stored, which is not always the one asked for. */
    private suspend fun displayNameOf(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
    }

    /** Asks the provider to rename a document, returning the name it ends up with. */
    private suspend fun rename(uri: Uri, to: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            DocumentsContract.renameDocument(context.contentResolver, uri, to)
                ?.let { renamed -> displayNameOf(renamed) ?: to }
        }.getOrNull()
    }

    /**
     * Reads a window of a document without pulling the rest of it into memory.
     *
     * Bounded while the bytes are still arriving, rather than read whole and then trimmed.
     * The trimming version is what the attachment path does, and it is safe there because
     * the file came out of a picker a person tapped. Here the name came from a model, and
     * the folder it names can hold a two gigabyte video.
     */
    suspend fun readText(entry: Entry, skip: Int, take: Int): String? =
        withContext(Dispatchers.IO) {
            val uri = uriFor(entry) ?: return@withContext null
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.reader().use { reader ->
                        reader.skipAsMuchAs(skip.toLong())
                        val buffer = CharArray(take)
                        val read = reader.readAsMuchAs(buffer)
                        String(buffer, 0, read)
                    }
                }
            }.getOrNull()
        }

    /**
     * Saves text to a path that has nothing at it yet, and says what happened in a sentence.
     *
     * Phrased here rather than in the tool because every way this can fail is a fact about
     * the folder, and the tool would only be translating them back. Refusing a path that
     * already exists is the load-bearing line: without it a model that read half a long file
     * would write that half back over the whole, and report success for having done it.
     */
    // Each return is a distinct storage refusal; flattening them would obscure the boundary.
    @Suppress("ReturnCount")
    suspend fun put(path: String, text: String, replace: Boolean = false): ToolExecution {
        val segments = path.workspaceSegments()
            ?: return ToolExecution.rejected(
                "$path is not a path inside the shared folder. Try one like notes/todo.md.",
            )
        val existing = resolve(path)
        if (existing != null) {
            // Still refused by default, for the reason above. What changed is that a caller
            // can now say it means it. The danger the default guards against is a model
            // writing back the half of a file it happened to read; a caller that passes
            // replace has decided to, and the transcript records that it did. Without this
            // there is no way to fix a script and run it again, which is the loop the
            // sandbox exists for.
            if (!replace) {
                return ToolExecution.rejected(
                    "$path already exists, and this tool does not replace files unless " +
                        "asked. Call it again with replace set to true to overwrite it, or " +
                        "choose a name that is not taken.",
                )
            }
            if (existing.isDirectory) {
                return ToolExecution.rejected("$path is a folder, not a file.")
            }
            return replace(existing, segments, text, path)
        }
        val parent = ensureFolders(segments.dropLast(1))
            ?: return ToolExecution.failure(
                "The folders leading to $path could not be created.",
            )
        return putInto(parent.takeIf { it.path.isNotEmpty() }, segments.last(), text, path)
    }

    /**
     * The raw bytes of [entry], for serving over the canvas — images included.
     *
     * Bounded, because this feeds an HTTP response built in memory and the folder is the
     * user's: a stray video would otherwise become one allocation the size of the video.
     */
    suspend fun readBytes(entry: Entry, limit: Int = MAX_SERVED_BYTES): ByteArray? =
        withContext(Dispatchers.IO) {
            val uri = uriFor(entry) ?: return@withContext null
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    // By hand rather than readNBytes, which arrived in API 33 and this
                    // app still serves 31.
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(SERVE_CHUNK)
                    var total = 0
                    while (total <= limit) {
                        val read = stream.read(chunk)
                        if (read < 0) break
                        buffer.write(chunk, 0, read)
                        total += read
                    }
                    if (total > limit) null else buffer.toByteArray()
                }
            }.getOrNull()
        }

    /**
     * The folder those segments name, created a level at a time where it is missing.
     *
     * Creating folders is additive the way creating files is, which is why this happens
     * without ceremony: an agent laying out a project writes `site/css/style.css` and the
     * folders are part of the file's name, not a separate favour to ask for. The returned
     * entry for the root is a placeholder with an empty path, which [put] reads as "no
     * parent" the way it always has.
     */
    private suspend fun ensureFolders(segments: List<String>): Entry? {
        if (segments.isEmpty()) return ROOT
        var walked = ""
        var parent: Entry? = null
        for (name in segments) {
            walked = if (walked.isEmpty()) name else "$walked/$name"
            val found = resolve(walked)
            parent = when {
                found == null ->
                    create(parent, name, DocumentsContract.Document.MIME_TYPE_DIR)
                        ?.let { resolve(walked) }
                found.isDirectory -> found
                else -> null
            } ?: return null
        }
        return parent
    }

    /**
     * Removes what that path names, file or folder, and says which it could not.
     *
     * Deleting through the provider rather than any path arithmetic, for the same reason
     * [resolve] walks: the only ids in hand are ones the granted folder handed over.
     */
    suspend fun delete(path: String): ToolExecution {
        val entry = resolve(path)
            ?: return ToolExecution.rejected("There is no $path to delete.")
        val uri = uriFor(entry)
            ?: return ToolExecution.failure("$path could not be opened.")
        return if (remove(uri)) {
            ToolExecution("Deleted $path.")
        } else {
            ToolExecution.failure("$path could not be deleted.")
        }
    }

    /** Asks the provider to delete a document, and says whether it did. */
    private suspend fun remove(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            .getOrDefault(false)
    }

    private suspend fun putInto(
        parent: Entry?,
        name: String,
        text: String,
        path: String,
    ): ToolExecution {
        val landed = land(parent, name, text)
            ?: return ToolExecution.failure(
                "$path could not be created. The folder may not accept new files.",
            )
        return when {
            // The worst outcome here, and the one worth naming: a file exists with the
            // right name and nothing in it. Reported as a failure so that a goal step
            // cannot count it as the work having been done.
            !landed.written ->
                ToolExecution.failure("$path was created but nothing could be written into it.")
            landed.served != name ->
                ToolExecution(
                    "Saved ${text.length} characters, but the folder stored it as " +
                        "\"${landed.served}\" rather than \"$name\". Use that name.",
                )
            else -> ToolExecution("Saved ${text.length} characters to $path.")
        }
    }

    /**
     * Creates a file under a name and writes the text into it, holding the provider to the
     * name asked for.
     *
     * The belt to the media type's braces: a provider that still decorated the name is
     * asked to change it back. Verified rather than assumed, because the file the model is
     * about to tell the user about has to exist under the name it says - the canvas
     * resolves that exact path, and a silently renamed file is a deck that never updates.
     * A provider that refuses the rename keeps the decorated name, which is handed back so
     * the caller can say so instead of claiming a path that is not there.
     */
    private suspend fun land(parent: Entry?, name: String, text: String): Landed? {
        val uri = create(parent, name, mediaTypeFor(name)) ?: return null
        val actual = displayNameOf(uri)
        val served = if (actual != null && actual != name) rename(uri, name) ?: actual else name
        return Landed(served, write(uri, text))
    }

    /**
     * Puts new contents under a name that already has some, without a moment at which the
     * folder holds neither.
     *
     * This used to open the document and truncate it, the one thing this class did that
     * could destroy work: a provider error or a process death between the truncate and the
     * last byte left an empty file where the user's had been. Now the text goes whole into
     * a hidden sibling first, and only once it is all there does the original give way,
     * deleted and the sibling renamed into its name. The framework has no rename-over, so
     * the swap is not atomic; what holds instead is that the full text is in the folder
     * under some name at every step, and the worst a death can leave is a
     * `.notes.md.3f9a2c1b.tmp` beside a missing `notes.md` rather than an empty one.
     *
     * Not every provider renames. One that refuses has the name created afresh, as a first
     * save would, and the text written into it from memory; the sibling goes only once the
     * text has landed under the right name. Every failure says what state the folder was
     * left in, and the ones that leave the text only in the sibling say its name.
     */
    // Each return is a distinct step that can fail, with its own account of what is left.
    @Suppress("ReturnCount")
    private suspend fun replace(
        existing: Entry,
        segments: List<String>,
        text: String,
        path: String,
    ): ToolExecution {
        val original = uriFor(existing)
            ?: return ToolExecution.failure("$path could not be opened for writing.")
        val parentPath = segments.dropLast(1).joinToString("/")
        val parent = if (parentPath.isEmpty()) {
            null
        } else {
            resolve(parentPath) ?: return ToolExecution.failure(
                "The folder holding $path could not be read, so it is unchanged.",
            )
        }
        val staged = stage(parent, existing.name)
            ?: return ToolExecution.failure(
                "$path could not be replaced: the folder would not take a new file to " +
                    "write into, so it is unchanged.",
            )
        if (!write(staged.uri, text)) {
            return ToolExecution.failure(
                discard(staged, "$path could not be written, so it is unchanged."),
            )
        }
        if (!remove(original)) {
            return ToolExecution.failure(
                discard(
                    staged,
                    "$path could not be replaced: the old file would not delete, so it " +
                        "is unchanged.",
                ),
            )
        }
        return swap(staged, parent, existing.name, text, path)
    }

    /**
     * A hidden sibling for the new text to land in before the original is touched.
     *
     * Declared with the target's media type rather than the sibling's own, for a provider
     * that records the type at creation and keeps it through a rename: the file the swap
     * ends with should be the markdown it is, not a byte stream. A provider that fits
     * names to types may decorate the sibling's for it, which costs nothing - it is
     * addressed by uri and reported by whatever name it was actually given.
     */
    private suspend fun stage(parent: Entry?, name: String): Staged? {
        val asked = stagingNameFor(name)
        val uri = create(parent, asked, mediaTypeFor(name)) ?: return null
        return Staged(uri, displayNameOf(uri) ?: asked)
    }

    /** Removes a staged sibling that will not be used, and says so if it could not be. */
    private suspend fun discard(staged: Staged, why: String): String = if (remove(staged.uri)) {
        why
    } else {
        "$why A file called \"${staged.name}\" was left beside it and could not be removed."
    }

    /**
     * The step past which the original is gone and the text lives under the staged name.
     *
     * Rename is asked for first, because it is one operation and the bytes move with it.
     * A provider without it has the name made afresh, the way [putInto] makes one, and the
     * text written in again from memory; only then is the sibling let go of.
     */
    private suspend fun swap(
        staged: Staged,
        parent: Entry?,
        name: String,
        text: String,
        path: String,
    ): ToolExecution {
        val renamed = rename(staged.uri, name)
        if (renamed != null) return replaced(path, name, renamed, text.length)
        val landed = land(parent, name, text)
            ?: return ToolExecution.failure(
                "$path was removed but could not be created again. Its new contents are " +
                    "in \"${staged.name}\" beside where it was.",
            )
        if (!landed.written) {
            return ToolExecution.failure(
                "$path was created again but nothing could be written into it. Its new " +
                    "contents are in \"${staged.name}\" beside it.",
            )
        }
        val result = replaced(path, name, landed.served, text.length)
        return if (remove(staged.uri)) {
            result
        } else {
            ToolExecution(
                "${result.text} A copy called \"${staged.name}\" was left beside it and " +
                    "could not be removed.",
            )
        }
    }

    /** The sentence for a swap that landed, allowing for a provider that renamed as it went. */
    private fun replaced(path: String, name: String, served: String, count: Int): ToolExecution =
        if (served == name) {
            ToolExecution("Replaced $path with $count characters.")
        } else {
            ToolExecution(
                "Replaced $path with $count characters, but the folder stored the new file " +
                    "as \"$served\" rather than \"$name\". Use that name.",
            )
        }

    /** Puts text into a document that was just created, and says whether all of it landed. */
    suspend fun write(uri: Uri, text: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.writer().use { it.write(text) }
                true
            } ?: false
        }.getOrDefault(false)
    }

    private fun Cursor.entries(under: String): List<Entry> {
        val out = mutableListOf<Entry>()
        while (moveToNext() && out.size < MAX_CHILDREN) {
            val name = getString(NAME)
            val documentId = getString(ID)
            // A provider is within its rights to answer with neither, and an entry with no
            // name is one the model could never ask for again.
            if (name != null && documentId != null) out += entry(under, name, documentId)
        }
        return out
    }

    private fun Cursor.entry(under: String, name: String, documentId: String): Entry {
        val mediaType = getString(TYPE).orEmpty()
        return Entry(
            path = if (under.isEmpty()) name else "$under/$name",
            documentId = documentId,
            name = name,
            isDirectory = mediaType == DocumentsContract.Document.MIME_TYPE_DIR,
            // Size is optional in the contract, and a directory rarely reports one.
            sizeBytes = if (isNull(SIZE)) 0L else getLong(SIZE),
            mediaType = mediaType,
        )
    }

    private companion object {
        /** 16 MB: generous for a page and its assets, small enough to allocate calmly. */
        const val MAX_SERVED_BYTES = 16 * 1024 * 1024

        const val SERVE_CHUNK = 64 * 1024

        /** Stands in for the granted folder itself, which no [Entry] otherwise names. */
        val ROOT = Entry(
            path = "",
            documentId = "",
            name = "",
            isDirectory = true,
            sizeBytes = 0L,
            mediaType = DocumentsContract.Document.MIME_TYPE_DIR,
        )

        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
        )

        /** Where each column of [PROJECTION] lands, named so the reads are not four numbers. */
        const val ID = 0
        const val TYPE = 1
        const val NAME = 2
        const val SIZE = 3

        /**
         * A cap on one directory, not on the search.
         *
         * A folder with more entries than this in it is a download directory or a photo
         * roll, and reading all of them into memory to answer one question is how a phone
         * runs out of it. The search has its own, smaller budget on top.
         */
        const val MAX_CHILDREN = 2_000
    }
}
