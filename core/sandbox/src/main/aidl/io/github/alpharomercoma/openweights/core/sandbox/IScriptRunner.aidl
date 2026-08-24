// Copyright 2026 The OpenWeights Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package io.github.alpharomercoma.openweights.core.sandbox;

import io.github.alpharomercoma.openweights.core.sandbox.IScriptResultCallback;

// The whole surface between the app and the process that runs scripts.
//
// One call, all inputs, one answer. Deliberately narrow: everything crossing this boundary
// is a string or a number the app chose, and nothing on the other side can ask for more.
// The reply comes back as a JSON envelope rather than through an out parameter, so there is
// one thing to marshal and one thing to get wrong.
oneway interface IScriptRunner {
    void run(
        in String source,
        in String inputsJson,
        in long memoryBytes,
        in long millis,
        in int outputLimit,
        in IScriptResultCallback callback
    );
}
