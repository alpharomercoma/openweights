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

package io.github.alpharomercoma.openweights.ui.canvas

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What the canvas WebView lets through: its own server, and nothing off the phone. */
@RunWith(RobolectricTestRunner::class)
class CanvasEgressTest {
    private fun stays(address: String): Boolean = Uri.parse(address).staysOnDevice(PORT)

    @Test
    fun `the server and what a page already holds stay on the device`() {
        assertThat(stays("http://127.0.0.1:43122/abc/site/index.html")).isTrue()
        assertThat(stays("http://127.0.0.1:43122/abc/__ow__/doc?file=a.md")).isTrue()
        assertThat(stays("data:image/png;base64,iVBORw0KGgo=")).isTrue()
        assertThat(stays("blob:http://127.0.0.1:43122/uuid")).isTrue()
        assertThat(stays("about:blank")).isTrue()
    }

    @Test
    fun `the loopback on any other port is somebody else's server`() {
        // The page's policy keeps its own requests on its origin, but nothing in it covers
        // a navigation, and the loopback has other listeners: a debug bridge, another app.
        assertThat(stays("http://127.0.0.1:8080/")).isFalse()
        assertThat(stays("http://127.0.0.1/")).isFalse()
    }

    @Test
    fun `any other host is refused, however it is spelled`() {
        assertThat(stays("https://example.com/collect?d=secret")).isFalse()
        assertThat(stays("http://localhost:43122/abc/site/index.html")).isFalse()
        assertThat(stays("http://[::1]:43122/abc/site/index.html")).isFalse()
        assertThat(stays("http://127.0.0.1.example.com/")).isFalse()
        assertThat(stays("ws://127.0.0.1:43122/")).isFalse()
        assertThat(stays("file:///sdcard/notes.md")).isFalse()
        assertThat(stays("content://media/external/images/1")).isFalse()
        assertThat(stays("intent://scan/#Intent;scheme=zxing;end")).isFalse()
        assertThat(stays("mailto:someone@example.com")).isFalse()
    }
}

private const val PORT = 43122
