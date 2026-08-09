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

#pragma once

#include <atomic>
#include <functional>
#include <string>
#include <vector>

#include "llama.h"

namespace openweights {

/**
 * The length of the longest prefix of [text] that is complete UTF-8.
 *
 * Exposed because the JNI layer needs the same answer: anything crossing into Java has to
 * be valid modified UTF-8 or the runtime aborts the process rather than throwing.
 */
size_t complete_utf8_prefix(const std::string & text);

/** One message in a conversation, as handed to the chat template. */
struct ChatMessage {
    std::string role;
    std::string content;
    /** Set on a `tool` message: which call this is the result of. */
    std::string tool_call_id;
    /**
     * Files attached to this message, in the order their markers appear in [content].
     *
     * Images, audio and video are all just files here; libmtmd decides what each one is
     * from its contents, and the loaded projector decides what the model can accept.
     */
    std::vector<std::string> media_paths;
};

/** A tool the model may call, described the way the OpenAI-style schema does. */
struct ToolDefinition {
    std::string name;
    std::string description;
    /** JSON Schema for the arguments object. */
    std::string parameters_json;
};

/** A call the model asked for. */
struct ToolCall {
    std::string id;
    std::string name;
    std::string arguments_json;
};

/**
 * How the model should think, when its template offers the choice.
 *
 * llama.cpp exposes two separate things. `enable_thinking` is a flag the chat template
 * itself understands, and `common_chat_templates_support_enable_thinking` says whether the
 * loaded template does. `effort` is passed through as a template argument for the models
 * that read one, and ignored by the ones that do not.
 */
struct ReasoningConfig {
    bool enabled = true;
    /** `low`, `medium` or `high`. Empty leaves the template's own default alone. */
    std::string effort;
};

/** Sampler configuration for a single generation. Mirrors SamplerParams on the Kotlin side. */
struct SamplerConfig {
    float    temperature   = 0.8f;
    int32_t  top_k         = 40;
    float    top_p         = 0.95f;
    float    min_p         = 0.05f;
    float    repeat_penalty = 1.1f;
    int32_t  repeat_last_n = 64;
    uint32_t seed          = LLAMA_DEFAULT_SEED;
    int32_t  max_tokens    = 0;  // 0 = until EOG or context is full
};

/** How a generation ended. */
enum class StopReason {
    END_OF_TURN,      // model emitted an end-of-generation token
    MAX_TOKENS,       // hit the caller's token budget
    CONTEXT_FULL,     // no room left in the KV cache
    CANCELLED,        // cancel() was called from another thread
    ERROR,            // decode failed; see `error`
};

/** A model reply, already split by llama.cpp's per-model parser. */
struct ParsedReply {
    std::string content;
    std::string reasoning;
    std::vector<ToolCall> tool_calls;
};

/** Throughput numbers for one generation, measured by us rather than inferred. */
struct GenerationStats {
    int32_t prompt_tokens     = 0;
    int32_t generated_tokens  = 0;
    int64_t prefill_ms        = 0;
    int64_t decode_ms         = 0;
    int64_t time_to_first_token_ms = 0;
    int32_t context_used      = 0;
    int32_t context_size      = 0;
};

/**
 * A loaded model plus its context and KV cache.
 *
 * One Session is one conversation: it remembers the tokens already in the KV cache so a
 * follow-up turn only has to decode the new suffix. Sessions are not thread-safe except
 * for `cancel()`, which is deliberately callable from another thread.
 */
class Session {
public:
    /** Emitted for each decoded token; return false to stop generation early. */
    using TokenCallback = std::function<bool(const char * piece)>;

    ~Session();

    /**
     * What kinds of media the loaded projector can accept.
     *
     * Video is absent deliberately: libmtmd's video path extracts frames by shelling out
     * to an `ffmpeg` binary in PATH, which an Android app has no way to provide. Frames
     * are sampled on the Kotlin side and sent as images instead.
     */
    struct MediaSupport {
        bool vision = false;
        bool audio = false;
    };

    /** Loads a model. Returns nullptr on failure and fills `error`. */
    static Session * load(
        const std::string & model_path,
        const std::string & mmproj_path,
        int32_t n_ctx,
        int32_t n_threads,
        int32_t n_threads_batch,
        int32_t n_gpu_layers,
        bool use_mmap,
        std::string & error);

    /**
     * Renders `messages` with the model's own chat template and generates a reply,
     * invoking `on_token` for each piece as it is produced.
     */
    StopReason generate(
        const std::vector<ChatMessage> & messages,
        const std::vector<ToolDefinition> & tools,
        const SamplerConfig & sampler,
        const ReasoningConfig & reasoning,
        const TokenCallback & on_token,
        GenerationStats & stats,
        ParsedReply & reply,
        std::string & error);

    /** True when the loaded chat template understands being told whether to think. */
    bool supports_thinking() const;

    /** Signals the running generation to stop. Safe to call from any thread. */
    void cancel() { cancelled_.store(true, std::memory_order_relaxed); }

    /** Drops the KV cache so the next generation starts from an empty context. */
    void reset();

    /**
     * Changes how many threads generation and prompt processing use.
     *
     * Runtime rather than load-time because the right answer moves: a throttled phone is
     * measurably slower with more threads than with fewer.
     */
    void set_threads(int32_t n_threads, int32_t n_threads_batch);

    /** True when a multimodal projector was loaded alongside the model. */
    bool is_multimodal() const { return mtmd_ != nullptr; }

    MediaSupport media_support() const;

    /** The marker the projector expects where a file belongs in the prompt. */
    std::string media_marker() const;

    std::string model_description() const;
    uint64_t    parameter_count() const;
    uint64_t    model_size_bytes() const;
    int32_t     context_size() const;
    int32_t     training_context_size() const;
    int32_t     layer_count() const;
    /** Number of tokens currently held in the KV cache. */
    /**
     * KV-cache positions in use.
     *
     * [n_past_] rather than the token record: a turn with an attachment fills positions
     * with embeddings that no token describes, so the token count would read as empty
     * while the cache is nearly full.
     */
    int32_t     context_used() const { return n_past_; }

private:
    Session() = default;

    /** Renders the conversation and any tools into this model's own prompt format. */
    bool render_prompt(
        const std::vector<ChatMessage> & messages,
        const std::vector<ToolDefinition> & tools,
        const ReasoningConfig & reasoning,
        std::string & out,
        std::string & error);

    /** Decodes `tokens[from..]`, reusing whatever prefix is already cached. */
    bool ingest_prompt(const std::vector<llama_token> & tokens, size_t from, std::string & error);

    /**
     * Encodes a prompt containing media and evaluates it into the KV cache.
     *
     * Returns the new position, or -1 on failure.
     */
    int32_t ingest_media_prompt(
        const std::string & prompt,
        const std::vector<std::string> & media_paths,
        std::string & error);

    llama_model   * model_ = nullptr;
    llama_context * ctx_   = nullptr;

    /**
     * The model's chat templates, parsed once at load.
     *
     * Held as an opaque pointer so this header does not drag llama.cpp's common layer
     * into every translation unit that talks to the engine.
     */
    void * chat_templates_ = nullptr;

    /** How the last prompt was rendered, which is what the reply must be parsed against. */
    int last_format_ = 0;
    std::string last_generation_prompt_;

    /** The multimodal projector, or null for a text-only model. */
    void * mtmd_ = nullptr;

    /** Tokens currently represented in the KV cache, in order. */
    std::vector<llama_token> cached_;

    /**
     * Position of the next token in the KV cache.
     *
     * Tracked separately from [cached_] because a media chunk occupies positions without
     * corresponding to tokens we can compare, so prefix reuse does not apply to it.
     */
    int32_t n_past_ = 0;

    /**
     * True while [cached_] describes every position in the KV cache.
     *
     * Goes false for a turn with an attachment, whose positions hold image or audio
     * embeddings. Comparing a prompt against those would match tokens that are not there,
     * so the next turn re-evaluates from the start instead.
     */
    bool cached_covers_context_ = true;

    std::atomic<bool> cancelled_{false};
};

/** Initialises the ggml backends and routes llama.cpp logs to logcat. Idempotent. */
void init_backend();

/** Human-readable description of the active ggml backends and CPU features. */
std::string system_info();

/** One compute device ggml can run on, as reported by the loaded backends. */
struct ComputeDevice {
    std::string id;           // ggml device name, stable enough to persist as a setting
    std::string description;  // what to show the user
    int32_t     type;         // mirrors ggml_backend_dev_type
    uint64_t    total_memory;
};

/**
 * Every compute device available on this phone.
 *
 * Today that is the CPU backend chosen by [init_backend]; when a GPU backend is compiled
 * in it appears here too, which is how Settings learns what it may offer.
 */
std::vector<ComputeDevice> compute_devices();

}  // namespace openweights
