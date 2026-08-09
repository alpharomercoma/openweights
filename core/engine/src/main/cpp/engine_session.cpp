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

#include "engine_session.h"

#include <android/log.h>
#include <dlfcn.h>

#include <chrono>
#include <cstring>
#include <mutex>

#include "ggml-backend.h"
#include "ggml-cpu.h"

#define LOG_TAG "OpenWeights"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace openweights {
namespace {

int64_t now_ms() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

void log_callback(ggml_log_level level, const char * text, void * /*user_data*/) {
    // GGML_LOG_LEVEL_CONT (5) marks a continuation of the previous line, not a severity —
    // matching on >= ERROR would log llama.cpp's progress dots as errors.
    switch (level) {
        case GGML_LOG_LEVEL_ERROR:
            __android_log_write(ANDROID_LOG_ERROR, LOG_TAG, text);
            break;
        case GGML_LOG_LEVEL_WARN:
            __android_log_write(ANDROID_LOG_WARN, LOG_TAG, text);
            break;
        default:
            break;
    }
}

std::once_flag g_backend_once;

/** Builds the sampler chain. Order matters: penalties and truncation before temperature. */
llama_sampler * build_sampler(const SamplerConfig & cfg, const llama_vocab * vocab) {
    auto params = llama_sampler_chain_default_params();
    params.no_perf = true;
    llama_sampler * chain = llama_sampler_chain_init(params);

    if (cfg.repeat_penalty != 1.0f && cfg.repeat_last_n != 0) {
        llama_sampler_chain_add(
            chain,
            llama_sampler_init_penalties(
                llama_vocab_n_tokens(vocab), cfg.repeat_last_n, cfg.repeat_penalty, 0.0f, 0.0f));
    }

    // temperature <= 0 means greedy: skip the truncation samplers entirely.
    if (cfg.temperature <= 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
        return chain;
    }

    if (cfg.top_k > 0) {
        llama_sampler_chain_add(chain, llama_sampler_init_top_k(cfg.top_k));
    }
    if (cfg.top_p < 1.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(cfg.top_p, 1));
    }
    if (cfg.min_p > 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_min_p(cfg.min_p, 1));
    }
    llama_sampler_chain_add(chain, llama_sampler_init_temp(cfg.temperature));
    llama_sampler_chain_add(chain, llama_sampler_init_dist(cfg.seed));
    return chain;
}

}  // namespace

/**
 * The CPU backend variants built by GGML_CPU_ALL_VARIANTS, best instruction set first.
 * Each exports `ggml_backend_score()`, which inspects the running CPU and returns 0 when
 * the variant's instructions are unavailable.
 */
constexpr const char * kCpuVariants[] = {
    "libggml-cpu-android_armv9.2_2.so",  // + SVE2 + SME
    "libggml-cpu-android_armv9.2_1.so",  // + SVE  + SME
    "libggml-cpu-android_armv9.0_1.so",  // + SVE2
    "libggml-cpu-android_armv8.6_1.so",  // + i8mm
    "libggml-cpu-android_armv8.2_2.so",  // + fp16 arithmetic
    "libggml-cpu-android_armv8.2_1.so",  // + dotprod
    "libggml-cpu-android_armv8.0_1.so",  // baseline
};

/**
 * Loads the fastest CPU backend this device can actually run.
 *
 * ggml's own loader scans a directory for backend libraries, which does not work on
 * Android: with modern packaging the `.so` files are never extracted from the APK, so
 * there is no directory to scan. They *are* reachable by soname through the app's linker
 * namespace, so we score the candidates ourselves and hand the winner to ggml.
 */
void load_best_cpu_backend() {
    const char * best_name = nullptr;
    int best_score = 0;

    for (const char * name : kCpuVariants) {
        void * handle = dlopen(name, RTLD_NOW | RTLD_LOCAL);
        if (handle == nullptr) {
            continue;
        }
        auto score_fn = reinterpret_cast<int (*)()>(dlsym(handle, "ggml_backend_score"));
        const int score = score_fn != nullptr ? score_fn() : 1;
        dlclose(handle);

        if (score > best_score) {
            best_score = score;
            best_name = name;
        }
    }

    if (best_name == nullptr) {
        LOGE("no compatible ggml CPU backend found for this device");
        return;
    }
    LOGI("using CPU backend %s (score %d)", best_name, best_score);
    ggml_backend_load(best_name);
}

void init_backend() {
    std::call_once(g_backend_once, [] {
        llama_log_set(log_callback, nullptr);
        load_best_cpu_backend();
        llama_backend_init();
    });
}

std::string system_info() {
    std::string info = llama_print_system_info();
    info += " | backends:";
    for (size_t i = 0; i < ggml_backend_reg_count(); ++i) {
        info += " ";
        info += ggml_backend_reg_name(ggml_backend_reg_get(i));
    }
    return info;
}

std::vector<ComputeDevice> compute_devices() {
    init_backend();
    std::vector<ComputeDevice> devices;
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t device = ggml_backend_dev_get(i);
        size_t free_memory = 0;
        size_t total_memory = 0;
        ggml_backend_dev_memory(device, &free_memory, &total_memory);
        devices.push_back({
            ggml_backend_dev_name(device),
            ggml_backend_dev_description(device),
            static_cast<int32_t>(ggml_backend_dev_type(device)),
            static_cast<uint64_t>(total_memory),
        });
    }
    return devices;
}

Session::~Session() {
    if (ctx_ != nullptr) {
        llama_free(ctx_);
    }
    if (model_ != nullptr) {
        llama_model_free(model_);
    }
}

Session * Session::load(
    const std::string & model_path,
    int32_t n_ctx,
    int32_t n_threads,
    int32_t n_threads_batch,
    int32_t n_gpu_layers,
    bool use_mmap,
    std::string & error) {
    init_backend();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = n_gpu_layers;
    model_params.load_mode    = use_mmap ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;

    llama_model * model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (model == nullptr) {
        error = "failed to load model from " + model_path;
        return nullptr;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx           = static_cast<uint32_t>(n_ctx);
    // Prompt ingestion happens in chunks of this size; 512 keeps peak compute-buffer
    // memory modest on phones while still batching enough work to be fast.
    ctx_params.n_batch         = 512;
    ctx_params.n_ubatch        = 512;
    ctx_params.n_threads       = n_threads;
    ctx_params.n_threads_batch = n_threads_batch;
    ctx_params.no_perf         = true;

    llama_context * ctx = llama_init_from_model(model, ctx_params);
    if (ctx == nullptr) {
        llama_model_free(model);
        error = "failed to create llama context (context length may be too large for this device)";
        return nullptr;
    }

    auto * session = new Session();
    session->model_ = model;
    session->ctx_   = ctx;
    return session;
}

void Session::reset() {
    llama_memory_clear(llama_get_memory(ctx_), true);
    cached_.clear();
}

bool Session::render_prompt(
    const std::vector<ChatMessage> & messages,
    std::string & out,
    std::string & error) const {
    const char * tmpl = llama_model_chat_template(model_, nullptr);
    if (tmpl == nullptr) {
        error = "model has no chat template; it cannot be used for chat";
        return false;
    }

    std::vector<llama_chat_message> chat;
    chat.reserve(messages.size());
    for (const auto & message : messages) {
        chat.push_back({message.role.c_str(), message.content.c_str()});
    }

    std::vector<char> buffer(4096);
    int32_t needed = llama_chat_apply_template(
        tmpl, chat.data(), chat.size(), /*add_ass=*/true, buffer.data(), buffer.size());
    if (needed > static_cast<int32_t>(buffer.size())) {
        buffer.resize(needed);
        needed = llama_chat_apply_template(
            tmpl, chat.data(), chat.size(), /*add_ass=*/true, buffer.data(), buffer.size());
    }
    if (needed < 0) {
        error = "failed to apply the model's chat template";
        return false;
    }

    out.assign(buffer.data(), needed);
    return true;
}

bool Session::ingest_prompt(
    const std::vector<llama_token> & tokens,
    size_t from,
    std::string & error) {
    const int32_t n_batch = static_cast<int32_t>(llama_n_batch(ctx_));

    for (size_t offset = from; offset < tokens.size(); offset += n_batch) {
        const int32_t chunk =
            std::min<int32_t>(n_batch, static_cast<int32_t>(tokens.size() - offset));
        llama_batch batch =
            llama_batch_get_one(const_cast<llama_token *>(tokens.data() + offset), chunk);
        const int ret = llama_decode(ctx_, batch);
        if (ret != 0) {
            error = "failed to decode prompt (llama_decode returned " + std::to_string(ret) + ")";
            return false;
        }
    }
    return true;
}

StopReason Session::generate(
    const std::vector<ChatMessage> & messages,
    const SamplerConfig & sampler_config,
    const TokenCallback & on_token,
    GenerationStats & stats,
    std::string & error) {
    cancelled_.store(false, std::memory_order_relaxed);

    std::string prompt;
    if (!render_prompt(messages, prompt, error)) {
        return StopReason::ERROR;
    }

    const llama_vocab * vocab = llama_model_get_vocab(model_);
    const int32_t n_ctx = static_cast<int32_t>(llama_n_ctx(ctx_));

    // Tokenize the whole conversation, then reuse whatever prefix is already cached.
    //
    // add_special is unconditionally true: we re-render the entire conversation every turn,
    // so the token sequence must be built identically each time or the prefix comparison
    // below finds no match and every turn re-decodes from scratch. Whether a BOS is
    // actually inserted is the vocab's decision (models whose template already emits one
    // set add_bos_token = false), so this cannot double up.
    const bool add_special = true;
    const int32_t n_tokens_needed = -llama_tokenize(
        vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), nullptr, 0, add_special, true);
    std::vector<llama_token> prompt_tokens(n_tokens_needed);
    if (llama_tokenize(
            vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
            prompt_tokens.data(), static_cast<int32_t>(prompt_tokens.size()),
            add_special, true) < 0) {
        error = "failed to tokenize the prompt";
        return StopReason::ERROR;
    }

    if (static_cast<int32_t>(prompt_tokens.size()) >= n_ctx) {
        error = "prompt is longer than the context window (" +
                std::to_string(prompt_tokens.size()) + " > " + std::to_string(n_ctx) + " tokens)";
        return StopReason::CONTEXT_FULL;
    }

    size_t reusable = 0;
    while (reusable < cached_.size() && reusable < prompt_tokens.size() &&
           cached_[reusable] == prompt_tokens[reusable]) {
        ++reusable;
    }
    // Never reuse the entire prompt: at least one token must be decoded to produce logits.
    if (reusable == prompt_tokens.size() && reusable > 0) {
        --reusable;
    }
    if (reusable < cached_.size()) {
        llama_memory_seq_rm(llama_get_memory(ctx_), 0, static_cast<llama_pos>(reusable), -1);
        cached_.resize(reusable);
    }

    const int64_t prefill_start = now_ms();
    if (!ingest_prompt(prompt_tokens, reusable, error)) {
        return StopReason::ERROR;
    }
    const int64_t prefill_end = now_ms();

    cached_ = prompt_tokens;

    stats.prompt_tokens = static_cast<int32_t>(prompt_tokens.size() - reusable);
    stats.prefill_ms    = prefill_end - prefill_start;
    stats.context_size  = n_ctx;

    llama_sampler * sampler = build_sampler(sampler_config, vocab);
    StopReason reason = StopReason::END_OF_TURN;
    int64_t first_token_ms = 0;

    char piece_buffer[512];
    while (true) {
        if (cancelled_.load(std::memory_order_relaxed)) {
            reason = StopReason::CANCELLED;
            break;
        }
        if (sampler_config.max_tokens > 0 && stats.generated_tokens >= sampler_config.max_tokens) {
            reason = StopReason::MAX_TOKENS;
            break;
        }
        if (static_cast<int32_t>(cached_.size()) >= n_ctx) {
            reason = StopReason::CONTEXT_FULL;
            break;
        }

        const llama_token token = llama_sampler_sample(sampler, ctx_, -1);
        if (llama_vocab_is_eog(vocab, token)) {
            reason = StopReason::END_OF_TURN;
            break;
        }

        const int32_t piece_len =
            llama_token_to_piece(vocab, token, piece_buffer, sizeof(piece_buffer), 0, true);
        if (piece_len < 0) {
            error = "failed to convert a token to text";
            reason = StopReason::ERROR;
            break;
        }

        if (stats.generated_tokens == 0) {
            first_token_ms = now_ms();
            stats.time_to_first_token_ms = first_token_ms - prefill_start;
        }
        ++stats.generated_tokens;

        const std::string piece(piece_buffer, piece_len);
        if (!on_token(piece.c_str())) {
            reason = StopReason::CANCELLED;
            break;
        }

        cached_.push_back(token);
        llama_batch batch = llama_batch_get_one(&cached_.back(), 1);
        const int ret = llama_decode(ctx_, batch);
        if (ret != 0) {
            // The token was sampled but could not be committed; drop it so the cache stays
            // an accurate record of what the KV cache actually holds.
            cached_.pop_back();
            error = "failed to decode (llama_decode returned " + std::to_string(ret) + ")";
            reason = StopReason::ERROR;
            break;
        }
    }

    llama_sampler_free(sampler);

    const int64_t decode_end = now_ms();
    stats.decode_ms = first_token_ms > 0 ? decode_end - first_token_ms : 0;
    stats.context_used = static_cast<int32_t>(cached_.size());
    return reason;
}

std::string Session::model_description() const {
    char buffer[256];
    const int32_t len = llama_model_desc(model_, buffer, sizeof(buffer));
    return len > 0 ? std::string(buffer, len) : std::string();
}

uint64_t Session::parameter_count() const { return llama_model_n_params(model_); }
uint64_t Session::model_size_bytes() const { return llama_model_size(model_); }
int32_t  Session::context_size() const { return static_cast<int32_t>(llama_n_ctx(ctx_)); }
int32_t  Session::training_context_size() const { return llama_model_n_ctx_train(model_); }
int32_t  Session::layer_count() const { return llama_model_n_layer(model_); }

}  // namespace openweights
