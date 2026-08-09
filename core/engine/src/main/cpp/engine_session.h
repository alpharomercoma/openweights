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

/** One message in a conversation, as handed to the chat template. */
struct ChatMessage {
    std::string role;
    std::string content;
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

    /** Loads a model. Returns nullptr on failure and fills `error`. */
    static Session * load(
        const std::string & model_path,
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
        const SamplerConfig & sampler,
        const TokenCallback & on_token,
        GenerationStats & stats,
        std::string & error);

    /** Signals the running generation to stop. Safe to call from any thread. */
    void cancel() { cancelled_.store(true, std::memory_order_relaxed); }

    /** Drops the KV cache so the next generation starts from an empty context. */
    void reset();

    std::string model_description() const;
    uint64_t    parameter_count() const;
    uint64_t    model_size_bytes() const;
    int32_t     context_size() const;
    int32_t     training_context_size() const;
    int32_t     layer_count() const;
    /** Number of tokens currently held in the KV cache. */
    int32_t     context_used() const { return static_cast<int32_t>(cached_.size()); }

private:
    Session() = default;

    /** Applies the model's chat template, growing the buffer as needed. */
    bool render_prompt(
        const std::vector<ChatMessage> & messages,
        std::string & out,
        std::string & error) const;

    /** Decodes `tokens[from..]`, reusing whatever prefix is already cached. */
    bool ingest_prompt(const std::vector<llama_token> & tokens, size_t from, std::string & error);

    llama_model   * model_ = nullptr;
    llama_context * ctx_   = nullptr;

    /** Tokens currently represented in the KV cache, in order. */
    std::vector<llama_token> cached_;

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
