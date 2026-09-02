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

    /** Every prompt fed since construction, so a turn's *delta* can be asserted on. */
    val prompts: MutableList<String> = mutableListOf()

    /** Every piece [prefill] fed, in order, so a warm's chunking can be asserted on. */
    val prefills: MutableList<String> = mutableListOf()

    /** Set to make [prefill] throw, as a runtime that cannot prefill would. */
    var failsDuringPrefill: String? = null

    /** Called after each [prefill] piece, so a test can cancel mid-warm. */
    var onPrefill: ((String) -> Unit)? = null

    /** New tokens asked for, so the zero-means-no-limit case can be checked. */
    var lastMaxNewTokens: Int = -1
        private set

    /** The window handed over at load, which ExecuTorch needs as a sequence length. */
    var loadedContextLength: Int = -1
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

    /** Set to make [generate] throw partway, as a runtime error would. */
    var failsDuringGeneration: String? = null

    /** Called before each fragment is delivered, so a test can act mid-reply. */
    var beforeFragment: ((String) -> Unit)? = null

    override fun load(
        modelPath: String,
        tokenizerPath: String,
        temperature: Float,
        contextLength: Int,
    ): Boolean {
        loadedContextLength = contextLength
        loadedModelPath = modelPath
        loadedTokenizerPath = tokenizerPath
        closed = false
        return opens
    }

    override fun generate(
        prompt: String,
        maxNewTokens: Int,
        onToken: (String) -> Unit,
    ): ExecuTorchOutcome {
        lastPrompt = prompt
        prompts += prompt
        lastMaxNewTokens = maxNewTokens
        // Fragment by fragment, because the engine must not assume one callback per reply.
        // The real runtime keeps calling back until its own loop ends; a stop is only a
        // flag it reads on the way round, which is what stopping after the fragment
        // that asked reproduces.
        stopped = false
        for (fragment in reply.chunked(FRAGMENT)) {
            beforeFragment?.invoke(fragment)
            onToken(fragment)
            if (stopped) break
        }
        failsDuringGeneration?.let { throw LlamaException(it) }
        return outcome
    }

    override fun prefill(prompt: String) {
        failsDuringPrefill?.let { throw LlamaException(it) }
        prefills += prompt
        onPrefill?.invoke(prompt)
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
