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

package io.github.alpharomercoma.openweights.core.common.model

/**
 * What a loaded model produces, and therefore which settings reach it.
 *
 * The question this answers is whether the sampler settings mean anything for a model that
 * does not emit words, and the answer is that most of them do not. It is not a guess. The
 * audio path in the vendored engine takes one struct, `mtmd_helper_gen_audio_inp` in
 * `tools/mtmd/mtmd-helper.h`, and that struct has three sampling fields:
 *
 * ```c
 * int32_t  top_k;
 * float    top_p;
 * uint32_t seed; // UINT32_MAX for random
 * ```
 *
 * There is nowhere to put the other six. Temperature, min-p, the repeat penalty and its
 * window, the token limit, and the thinking switch are not parameters of that call, so a
 * sheet that offers them on a speech model is offering controls whose wire has been cut.
 * `tools/tts/tts.cpp` confirms it from the other side: it fills exactly `top_k`, `top_p`
 * and `seed` from `params.sampling` and reads nothing else out of it.
 *
 * ### What is deliberately missing
 *
 * There is no `IMAGE` and no `VIDEO`. Not an oversight and not a placeholder left for
 * later: llama.cpp does not generate either, at any quantization, on any backend. Pictures
 * come from a diffusion runtime, `stable-diffusion.cpp` is the usual one, which is a
 * separate project this app does not vendor. Naming the cases here would put two entries in
 * an enum that no model can ever return and every `when` would have to answer for.
 *
 * ### Speech is detected, not generated, in this build
 *
 * [SPEECH] can be reported by a loaded projector today, and nothing yet plays what such a
 * model produces. That is on purpose and it is the better of the two failures: a text
 * pipeline pointed at a speech model does not fail, it succeeds at the wrong thing, turning
 * audio codes into tokens and rendering them as text. Knowing the modality is what lets the
 * app say so instead.
 */
enum class OutputModality {
    /** Words. Every model this app currently generates with. */
    TEXT,

    /**
     * Speech, from a projector carrying a generative audio decoder.
     *
     * Reported by `mtmd_gen_audio_get_info`, whose `type` is `MTMD_GEN_AUDIO_TYPE_NONE`
     * unless the projector is a Qwen3-TTS or Pocket-TTS pipeline.
     */
    SPEECH,
    ;

    /** Whether [tunable] reaches the engine for this modality, or is decoration. */
    fun accepts(tunable: Tunable): Boolean = when (this) {
        TEXT -> true
        SPEECH -> tunable in SPEECH_TUNABLES
    }

    private companion object {
        /** The three sampling fields of `mtmd_helper_gen_audio_inp`. Not policy, an ABI. */
        val SPEECH_TUNABLES = setOf(
            Tunable.TOP_K,
            Tunable.TOP_P,
            Tunable.SEED,
        )
    }
}

/**
 * One thing the settings sheet lets a person change, named so a modality can disown it.
 *
 * Only the settings whose applicability varies are here. Context length and the processor
 * choice are absent on purpose: both are properties of loading a model rather than of
 * sampling from it, and they apply whatever it goes on to emit.
 *
 * The two prompts are here despite not being samplers, because they stop applying for the
 * same reason and at the same moment. A speech pipeline is handed the text to say, through
 * `mtmd_helper_gen_audio_inp::prompt`, with no chat template rendered around it, so there
 * is no system turn to carry standing instructions and no tool definitions to describe.
 */
enum class Tunable {
    THINKING,
    ANSWER_LENGTH,
    TEMPERATURE,
    TOP_K,
    TOP_P,
    REPEAT_PENALTY,
    SEED,
    MAX_TOKENS,
    SYSTEM_PROMPT,
    TOOL_PROMPT,
}
