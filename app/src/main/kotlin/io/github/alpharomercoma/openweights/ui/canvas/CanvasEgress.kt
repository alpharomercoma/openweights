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

/**
 * Whether a request the canvas makes stays on this phone.
 *
 * The page on the canvas is the model's own work, served from the user's folder over
 * loopback, and this app's promise is that nothing leaves the device without being asked.
 * The server sends every page a Content-Security-Policy that says the same, and this is
 * the WebView's half of it: a fetch, an image, a form or a navigation to any host but the
 * loopback address is refused before a socket opens, whatever the page says. `data:` and
 * `blob:` URLs carry only what the page already has, and `about:blank` is the empty tab.
 *
 * Only the literal IPv4 loopback, because that is the only address the server binds and
 * the only one its URLs name; a name that happens to resolve here is somebody else's. And
 * only the server's own [port]: the page's policy keeps its fetches and images to its own
 * origin, but a navigation is not covered by it, and a page could otherwise send the
 * WebView to whatever else is listening on the loopback.
 */
internal fun Uri.staysOnDevice(port: Int): Boolean = when (scheme?.lowercase()) {
    "http", "https" -> host == LOOPBACK && this.port == port
    "data", "blob", "about" -> true
    else -> false
}

private const val LOOPBACK = "127.0.0.1"
