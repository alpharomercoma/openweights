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

package io.github.alpharomercoma.openweights.core.engine

/**
 * ExecuTorch, standing still.
 *
 * Records what the engine asked it to run and replies with whatever the test set, which is
 * what makes the templating, the reasoning split and the tool parsing testable off a phone.
 */
class FakeExecuTorchBridge : ExecuTorchBridge {

    /** The prompt the engine built, exactly as the runtime would have received it. */
    var lastPrompt: String? = null
        private set

    /** The sequence length asked for, so the zero-means-no-limit case can be checked. */
    var lastMaxTokens: Int = -1
        private set

    var loadedModelPath: String? = null
        private set

    var loadedTokenizerPath: String? = null
        private set

    var closed: Boolean = false
        private set

    var stopped: Boolean = false
        private set

    /** How many times the engine dropped the runtime's carried-over state. */
    var contextResets: Int = 0
        private set

    /** What [generate] hands back, one fragment at a time. */
    var reply: String = ""

    /** What [load] answers. False stands for a runtime that could not open the file. */
    var opens: Boolean = true

    var outcome: ExecuTorchOutcome = ExecuTorchOutcome(StopReason.END_OF_TURN)

    override fun load(modelPath: String, tokenizerPath: String, temperature: Float): Boolean {
        loadedModelPath = modelPath
        loadedTokenizerPath = tokenizerPath
        closed = false
        return opens
    }

    override fun generate(
        prompt: String,
        maxTokens: Int,
        onToken: (String) -> Unit,
    ): ExecuTorchOutcome {
        lastPrompt = prompt
        lastMaxTokens = maxTokens
        // Fragment by fragment, because the engine must not assume one callback per reply.
        reply.chunked(FRAGMENT).forEach(onToken)
        return outcome
    }

    override fun resetContext() {
        contextResets++
    }

    override fun stop() {
        stopped = true
    }

    override fun close() {
        closed = true
    }

    private companion object {
        const val FRAGMENT = 3
    }
}
