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

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * What a path written by a model may be talked into meaning.
 *
 * The path is chosen by the model, which may have read it off a file in the very folder the
 * user shared, and a file is something anyone can write. So these are the same class of test
 * as [PublicOnlyDnsTest]: not "does it work", but "what does it refuse".
 *
 * Nothing here touches a provider. Every path is decided before anything is looked up, which
 * is the point of splitting the decision out of the walk.
 */
class WorkspacePathTest {
    @Test
    fun `an ordinary relative path becomes the names to walk`() {
        assertThat("notes/todo.md".workspaceSegments())
            .containsExactly("notes", "todo.md")
            .inOrder()
    }

    @Test
    fun `a single name is a path of one`() {
        assertThat("todo.md".workspaceSegments()).containsExactly("todo.md")
    }

    @Test
    fun `names with spaces in them are ordinary names`() {
        // The check began life refusing these, which is not safer, only broken: a phone is
        // full of files called "My something", and the folder a user shares is exactly where
        // they live.
        assertThat("My Notes/Trip 2026.md".workspaceSegments())
            .containsExactly("My Notes", "Trip 2026.md")
            .inOrder()
    }

    @Test
    fun `the paths that would leave the folder are refused`() {
        val refused = listOf(
            ".." to "the parent of the folder itself",
            "../secrets.txt" to "climbing out of it",
            "notes/../../etc/passwd" to "climbing out from somewhere inside it",
            "notes/./todo.md" to "a step that resolves to nowhere",
            "." to "the folder itself, named as a file",
            "/etc/passwd" to "an absolute path, which is a different request entirely",
            "//etc/passwd" to "an absolute path wearing two slashes",
            "notes//todo.md" to "an empty name in the middle",
            "notes/" to "an empty name at the end",
            "" to "nothing at all",
            "   " to "a name that is only spacing",
            """C:\Users\me\notes.txt""" to "a Windows path, whose separator is not ours",
            "notes\\todo.md" to "a backslash used as a separator",
            "content://com.android.externalstorage.documents/tree/primary" to "a content uri",
            "file:///data/data/io.github.alpharomercoma.openweights" to "a file uri",
            "primary:Documents/todo.md" to "a document id, which is not a path",
        )

        refused.forEach { (path, what) ->
            assertWithMessage("refused because it is %s", what)
                .that(path.workspaceSegments())
                .isNull()
        }
    }

    @Test
    fun `a path too deep to be a path is refused`() {
        // Past a dozen levels a walk is really a search, and each level is a round trip to
        // the provider. A model that has lost track of where it is should be told so rather
        // than have the app spend a second finding out for it.
        val deep = (1..13).joinToString("/") { "level$it" }

        assertThat(deep.workspaceSegments()).isNull()
    }

    @Test
    fun `a name longer than any filesystem allows is refused`() {
        assertThat(("a".repeat(256) + ".md").workspaceSegments()).isNull()
    }

    @Test
    fun `a terminator hidden in a name is refused`() {
        // A name is carried to the provider as a string and compared against one it handed
        // back. Nothing legitimate arrives with a terminator buried in it.
        assertThat("notes/todo\u0000.md".workspaceSegments()).isNull()
    }

    @Test
    fun `a name that merely starts like the folder is still just a name`() {
        // The structural sibling of https://example.com@10.0.0.1/ in FetchUrlToolTest: a
        // string that reads like it belongs somewhere it does not. Here it is harmless,
        // because nothing is decided by comparing prefixes, and this test exists to fail if
        // anyone ever reintroduces that.
        assertThat("notes-evil/todo.md".workspaceSegments())
            .containsExactly("notes-evil", "todo.md")
            .inOrder()
    }
}
