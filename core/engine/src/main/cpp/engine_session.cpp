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

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <mutex>
#include <regex>
#include <string>
#include <utility>
#include <vector>

#include "build-info.h"
#include "chat.h"
#include "common.h"
#include "ngram-map.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#include <memory>
#include <new>
#include <sys/stat.h>

namespace {
/**
 * Largest attachment handed to the projector.
 *
 * libmtmd reads the whole file into memory before it knows what it is, so an oversized
 * one is an out-of-memory kill rather than an error message. Images and video frames are
 * downscaled long before this; the cap is really about audio and about files that are not
 * what their name claims.
 */
constexpr size_t MAX_ATTACHMENT_BYTES = 64u * 1024u * 1024u;
}  // namespace
#include "ggml-backend.h"
#include "ggml-cpu.h"

/** No legitimate tokenizer piece is remotely this large; malformed vocabularies stop here. */
constexpr size_t MAX_TOKEN_PIECE_BYTES = 1024u * 1024u;

/**
 * The n-gram the drafter looks for in the context, and the most it proposes on a match.
 *
 * Twelve is llama.cpp's own default for the simple drafter: long enough that a match is
 * the model copying rather than a coincidence. Four is the break-even from the batch
 * costs measured on the MT6991, where verifying four costs 1.68 single steps and eight
 * costs 3.09, so a longer draft pays only at an acceptance rate this drafter rarely has.
 */
constexpr uint16_t SPEC_NGRAM_SIZE = 12;
constexpr uint16_t SPEC_DRAFT_MAX  = 4;

static bool ends_with(const std::string & text, const std::string & suffix) {
    return !suffix.empty() && text.size() >= suffix.size() &&
           text.compare(text.size() - suffix.size(), suffix.size(), suffix) == 0;
}

#define LOG_TAG "OpenWeights"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace openweights {
namespace {

/**
 * The warm-state file format; bumped whenever the layout changes.
 *
 * Version 2 put the llama.cpp build stamp after the version word; a version 1 file is
 * refused on its number, before anything after it is read.
 */
constexpr uint32_t kWarmFileVersion = 2;

/** A state larger than this is not worth the disk or the write; compute instead. */
constexpr uint64_t kWarmFileMaxBytes = 512ull * 1024 * 1024;

/** A stamp is "b<number>-<commit>", a dozen characters; a longer one was not written here. */
constexpr uint32_t kWarmFileMaxStampBytes = 64;

/**
 * The llama.cpp build that wrote a warm file, or is about to read one.
 *
 * The state blob is `llama_state_seq_get_data`'s bytes exactly as it produced them, and
 * that layout is llama's to change between builds. `llama_state_seq_set_data` checks the
 * cell count, the layer count and each tensor's type and size, which catches another
 * model or context; a field it was not written to expect is read as one it was. The
 * submodule commit is baked into `llama-common` at configure time, so a file written by
 * any other build of this app is refused on its header, before llama sees a byte of it.
 *
 * A build configured without git stamps `b0-unknown`. That still refuses every file a
 * stamped build wrote; it only cannot tell two unstamped builds apart, which is the
 * position every file was in before the stamp.
 */
const std::string & warm_file_stamp() {
    static const std::string stamp = llama_build_info();
    return stamp;
}

int64_t now_ms() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

/**
 * Where the weights of the model currently loading actually went.
 *
 * llama.cpp prints one line per backend buffer it filled, naming the backend and the
 * megabytes in it, and that line is the only ground truth about offloading there is. The
 * count of layers it also prints is the number that was *asked for*: it is computed from
 * `n_gpu_layers` inside `if (llama_supports_gpu_offload())`, which is a property of the
 * build rather than of this load, so a GPU backend that failed to attach reports the same
 * number as one that worked.
 *
 * Global rather than per-session because llama.cpp's log callback is global and carries no
 * user data we set per load. Loads are serialised behind the engine's own lock, and
 * [Session::load] clears this before the model opens and reads it after, so the window is
 * exactly one load.
 */
std::mutex g_buffers_mutex;
std::vector<std::pair<std::string, double>> g_load_buffers;

/**
 * `load_tensors:   CPU_Mapped model buffer size =   1234.56 MiB`, which is what we are after.
 *
 * Unanchored, because the line opens with the function name and the buffer name is the
 * token immediately before "model buffer size". Anchoring at the start captured
 * `load_tensors:` instead and matched nothing, which read exactly like the GPU never being
 * used: the summary came back empty and the caller fell through to the registered device,
 * which is always the CPU.
 *
 * The names carry how the memory was obtained as well as which backend owns it, so
 * `CPU_Mapped` and `CPU_Repack` are both the CPU. The suffix is dropped, or a model split
 * across two CPU buffers would look like two different processors.
 */
void note_buffer_line(const char * text) {
    static const std::regex pattern(R"((\S+)\s+model buffer size\s*=\s*([0-9.]+)\s*MiB)");
    std::cmatch match;
    if (!std::regex_search(text, match, pattern)) return;
    std::string name = match[1].str();
    const size_t underscore = name.find('_');
    if (underscore != std::string::npos) name = name.substr(0, underscore);
    std::lock_guard<std::mutex> guard(g_buffers_mutex);
    g_load_buffers.emplace_back(name, std::strtod(match[2].str().c_str(), nullptr));
}

/**
 * The last thing llama.cpp said went wrong, so a failure can be reported in its words.
 *
 * Without this the app had to guess. `llama_init_from_model` returns a null pointer and
 * nothing else, so every context failure was reported as "context length may be too large
 * for this device" — a guess that is right often enough to be believed and wrong in a way
 * the user then acts on: told the context is too big, they lower it and fail again. The
 * engine had already printed the real reason a line earlier.
 */
std::mutex g_error_mutex;
std::string g_last_error;

void remember_error(const char * text) {
    if (text == nullptr) return;
    std::string line(text);
    // llama.cpp ends its log lines with a newline; a message shown in a dialog should not.
    while (!line.empty() && (line.back() == '\n' || line.back() == '\r')) line.pop_back();
    if (line.empty()) return;
    std::lock_guard<std::mutex> guard(g_error_mutex);
    g_last_error = line;
}

std::string take_last_error() {
    std::lock_guard<std::mutex> guard(g_error_mutex);
    return g_last_error;
}

void clear_last_error() {
    std::lock_guard<std::mutex> guard(g_error_mutex);
    g_last_error.clear();
}

void log_callback(ggml_log_level level, const char * text, void * /*user_data*/) {
    // Read before the severity switch, because these arrive at INFO and INFO is dropped.
    // Dropping them is right: llama.cpp is chatty enough to matter on a phone. Reading one
    // line out of the stream first is what lets the app say where a model is without
    // turning the whole log back on.
    if (text != nullptr) note_buffer_line(text);

    // GGML_LOG_LEVEL_CONT (5) marks a continuation of the previous line, not a severity 
    // matching on >= ERROR would log llama.cpp's progress dots as errors.
    switch (level) {
        case GGML_LOG_LEVEL_ERROR:
            remember_error(text);
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

/**
 * The grammar sampler for a rendered tool call, or nullptr when the template asked for
 * no grammar.
 *
 * Mirrors what llama.cpp's own sampling code does with the same fields, because the
 * triggers come back in four shapes and only one of them is a literal word.
 */
llama_sampler * build_grammar(
    const GrammarSpec & spec,
    const llama_vocab * vocab) {
    // Lazy only. A lazy grammar costs nothing until the model starts a call and then
    // guarantees the rest of it parses, which is the whole benefit. A non-lazy one has to
    // be checked against every token in the vocabulary from the first position, which on
    // this hardware costs more than the tool call is worth, and forces a shape out of a
    // model that was not going to choose it. Measured on a phone: seconds per token, and
    // a reply that ran on well past the call.
    if (spec.grammar.empty() || !spec.lazy) {
        return nullptr;
    }
    std::vector<const char *> patterns;
    patterns.reserve(spec.trigger_patterns.size());
    for (const auto & pattern : spec.trigger_patterns) {
        patterns.push_back(pattern.c_str());
    }
    return llama_sampler_init_grammar_lazy_patterns(
        vocab,
        spec.grammar.c_str(),
        "root",
        patterns.data(),
        patterns.size(),
        spec.trigger_tokens.data(),
        spec.trigger_tokens.size());
}

/**
 * Builds the sampler chain. Order matters: penalties and truncation before temperature.
 *
 * The grammar goes first when there is one. It masks every token that cannot continue a
 * valid tool call, and it has to do that against the full distribution: behind top_k it
 * would be choosing from a shortlist that the truncation samplers had already picked
 * without knowing which tokens were legal.
 */
llama_sampler * build_sampler(
    const SamplerConfig & cfg,
    const llama_vocab * vocab,
    llama_sampler * grammar) {
    auto params = llama_sampler_chain_default_params();
    params.no_perf = true;
    llama_sampler * chain = llama_sampler_chain_init(params);

    if (grammar != nullptr) {
        llama_sampler_chain_add(chain, grammar);
    }

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
 * The length of the longest prefix of [text] that is valid UTF-8.
 *
 * A token is a sequence of bytes, not a character. "Belem" with an accent and every emoji
 * span several tokens, so a single piece routinely ends halfway through a character.
 * Handing that half to JNI's NewStringUTF does not throw, it aborts the process.
 *
 * Scans from the start rather than inspecting the tail. An earlier version only checked
 * the last few bytes, which accepted a string whose damage was in the middle. Overlong
 * encodings, surrogate halves and anything above U+10FFFF are rejected too: all three are
 * ill-formed UTF-8 that a decoder is required to refuse.
 */
size_t complete_utf8_prefix(const std::string & text) {
    size_t index = 0;
    while (index < text.size()) {
        const auto lead = static_cast<unsigned char>(text[index]);

        size_t length = 0;
        uint32_t code = 0;
        if ((lead & 0x80) == 0x00) {
            length = 1;
            code = lead;
        } else if ((lead & 0xE0) == 0xC0) {
            length = 2;
            code = lead & 0x1Fu;
        } else if ((lead & 0xF0) == 0xE0) {
            length = 3;
            code = lead & 0x0Fu;
        } else if ((lead & 0xF8) == 0xF0) {
            length = 4;
            code = lead & 0x07u;
        } else {
            return index;  // continuation byte or invalid lead
        }

        if (index + length > text.size()) {
            return index;  // a character still arriving
        }

        for (size_t offset = 1; offset < length; ++offset) {
            const auto byte = static_cast<unsigned char>(text[index + offset]);
            if ((byte & 0xC0) != 0x80) return index;
            code = (code << 6) | (byte & 0x3Fu);
        }

        const bool overlong = (length == 2 && code < 0x80) ||
                              (length == 3 && code < 0x800) ||
                              (length == 4 && code < 0x10000);
        const bool surrogate = code >= 0xD800 && code <= 0xDFFF;
        if (overlong || surrogate || code > 0x10FFFF) {
            return index;
        }
        index += length;
    }
    return index;
}

/**
 * Whether the byte at the front of [text] can never begin a valid character, as opposed to
 * beginning one that has not finished arriving.
 *
 * [complete_utf8_prefix] answers zero for both, and the streaming loop could only wait.
 * For a character still arriving that is right; for a stray continuation byte or an
 * overlong lead it is a stall: nothing behind it is ever emitted, the stored reply ends
 * there, and the model goes on decoding into a cache the record no longer describes. A
 * byte-fallback vocabulary produces exactly such bytes when sampling wanders, so the
 * front byte is replaced with U+FFFD and the stream moves on, which is what every decoder
 * that displays text does with ill-formed input.
 */
bool utf8_malformed_at_front(const std::string & text) {
    if (text.empty()) return false;
    const auto lead = static_cast<unsigned char>(text[0]);

    size_t length = 0;
    uint32_t code = 0;
    if ((lead & 0x80) == 0x00) {
        return false;
    } else if ((lead & 0xE0) == 0xC0) {
        length = 2;
        code = lead & 0x1Fu;
    } else if ((lead & 0xF0) == 0xE0) {
        length = 3;
        code = lead & 0x0Fu;
    } else if ((lead & 0xF8) == 0xF0) {
        length = 4;
        code = lead & 0x07u;
    } else {
        return true;  // a continuation byte, or a lead no encoding uses
    }

    for (size_t offset = 1; offset < length && offset < text.size(); ++offset) {
        const auto byte = static_cast<unsigned char>(text[offset]);
        if ((byte & 0xC0) != 0x80) return true;  // the sequence broke before it finished
        code = (code << 6) | (byte & 0x3Fu);
    }
    if (text.size() < length) return false;  // still arriving

    const bool overlong = (length == 2 && code < 0x80) ||
                          (length == 3 && code < 0x800) ||
                          (length == 4 && code < 0x10000);
    const bool surrogate = code >= 0xD800 && code <= 0xDFFF;
    return overlong || surrogate || code > 0x10FFFF;
}

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

/**
 * Registers the GPU backend, on the devices that have one.
 *
 * Same reason as the CPU variants: nothing is extracted from the APK, so ggml cannot scan
 * a directory and the library has to be named. Failing is normal and quiet. The backend
 * only loads where the vendor ships an OpenCL driver, which is Qualcomm and not much
 * else, and a device without one is not broken, it just has no GPU option.
 *
 * Registering it does not move any work onto the GPU. Weights stay on the CPU until a
 * load asks for layers to be offloaded, which is a choice made per model.
 */
void load_gpu_backend() {
    if (!ggml_backend_load("libggml-opencl.so")) {
        LOGI("no OpenCL backend on this device, running on the CPU");
        return;
    }
    LOGI("OpenCL backend registered");
}

/** Size of a file in bytes, or 0 when it cannot be read. */
size_t file_size(const std::string & path) {
    struct stat info {};
    if (stat(path.c_str(), &info) != 0) return 0;
    return static_cast<size_t>(info.st_size);
}

/** The backend holding most of the weights, and the breakdown behind it. */
std::string offload_summary_locked() {
    if (g_load_buffers.empty()) return std::string();
    // Folded by name first: one backend can own several buffers, and three CPU entries
    // ranked apart would each lose to a single larger GPU one.
    std::vector<std::pair<std::string, double>> sorted;
    for (const auto & [name, mib] : g_load_buffers) {
        auto found = std::find_if(sorted.begin(), sorted.end(),
                                  [&](const auto & entry) { return entry.first == name; });
        if (found == sorted.end()) sorted.emplace_back(name, mib); else found->second += mib;
    }
    std::sort(sorted.begin(), sorted.end(),
              [](const auto & a, const auto & b) { return a.second > b.second; });
    std::string summary = sorted.front().first;
    for (const auto & [name, mib] : sorted) {
        summary += "|" + name + ":" + std::to_string(static_cast<long>(mib));
    }
    return summary;
}

void init_backend() {
    std::call_once(g_backend_once, [] {
        llama_log_set(log_callback, nullptr);
        // The projector logs through its own channel; without this its failures go to
        // stderr, which on Android means nowhere.
        mtmd_helper_log_set(log_callback, nullptr);
        load_best_cpu_backend();
        load_gpu_backend();
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
    if (mtmd_ != nullptr) {
        mtmd_free(static_cast<mtmd_context *>(mtmd_));
    }
    if (chat_templates_ != nullptr) {
        common_chat_templates_free(static_cast<common_chat_templates *>(chat_templates_));
    }
    if (ctx_ != nullptr) {
        llama_free(ctx_);
    }
    if (model_ != nullptr) {
        llama_model_free(model_);
    }
}

/**
 * Why a context could not be made, in words the person reading them can act on.
 *
 * The one case worth naming outright is a speculative-decoding draft head. `dflash` and
 * `eagle3` files are published beside the models they draft for and look like ordinary
 * models from the outside: this one is 168 MB, loads without complaint, and fits any phone
 * comfortably. It has no vocabulary and no output layer — it borrows both from the model it
 * drafts for — so llama.cpp refuses the context with "requires ctx_other to be set", naming
 * the target context it was not given. That is precise and means nothing to anybody who has
 * not read llama.cpp, so it is translated once, here.
 *
 * Everything else keeps the engine's own sentence. A guess that reads like a diagnosis is
 * worse than an unfamiliar sentence that is true.
 */
std::string describe_context_failure(const std::string & reported) {
    if (reported.find("ctx_other") != std::string::npos) {
        return "this file is a draft model for speculative decoding, not one that can "
               "answer on its own. It has no vocabulary or output layer of its own and "
               "only runs alongside the full model it was published to speed up.";
    }
    if (!reported.empty()) {
        return "this model's context could not be created: " + reported;
    }
    return "failed to create llama context (context length may be too large for this device)";
}

/** The same for a model that would not load at all. See [describe_context_failure]. */
std::string describe_load_failure(const std::string & reported) {
    if (reported.find("unknown model architecture") != std::string::npos ||
        reported.find("unknown architecture") != std::string::npos) {
        return "this build does not know this model's architecture, so it cannot be run: " +
               reported;
    }
    if (!reported.empty()) {
        return "this model could not be loaded: " + reported;
    }
    return "failed to load this model";
}

Session * Session::load(
    const std::string & model_path,
    const std::string & mmproj_path,
    int32_t n_ctx,
    int32_t n_threads,
    int32_t n_threads_batch,
    int32_t n_gpu_layers,
    bool use_mmap,
    bool op_offload,
    bool kv_quantized,
    bool speculate,
    std::string & error) {
    init_backend();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = n_gpu_layers;
    model_params.load_mode    = use_mmap ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;

    {
        std::lock_guard<std::mutex> guard(g_buffers_mutex);
        g_load_buffers.clear();
    }
    clear_last_error();
    llama_model * model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (model == nullptr) {
        error = describe_load_failure(take_last_error());
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
    // Whether large-batch operations may be handed to a GPU while the model itself stays
    // where n_gpu_layers put it. This is the one knob that separates the two halves of a
    // turn: the scheduler only offloads an operation when the batch is big enough to be
    // worth the transfer, so prompt reading can run on the GPU while token-by-token
    // generation, which is always batch one, stays on the CPU. Turning it off keeps
    // everything local.
    ctx_params.op_offload      = op_offload;
    // The KV cache at eight bits, K and V both, which halves the bytes attention reads
    // back on every generated token. Decode is affine in context, so at a wide window
    // this is on the order of a tenth of the per-token time; whether it is worth its
    // cost in accuracy is a measurement per model, not an assumption, which is why it is
    // a knob and off by default. Q8_0 only: a four-bit K collapses (llama.cpp #23470).
    // Flash attention is asked for outright rather than left to the backend's judgement,
    // because a quantized V cache needs it and "auto" is allowed to say no.
    if (kv_quantized) {
        ctx_params.type_k          = GGML_TYPE_Q8_0;
        ctx_params.type_v          = GGML_TYPE_Q8_0;
        ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    }

    // Allocated before the context so that every native handle has an owner from the
    // moment it exists: a throw between them would otherwise leak the model outright.
    std::unique_ptr<Session> session(new (std::nothrow) Session());
    if (session == nullptr) {
        llama_model_free(model);
        error = "out of memory";
        return nullptr;
    }
    session->model_ = model;
    session->speculate_ = speculate;

    clear_last_error();
    llama_context * ctx = llama_init_from_model(model, ctx_params);
    if (ctx == nullptr) {
        error = describe_context_failure(take_last_error());
        return nullptr;
    }
    session->ctx_ = ctx;
    // Stop must mean now. The cooperative checks between batches and between tokens give
    // Stop a worst-case latency of one whole prefill batch - several seconds of a long
    // prompt on a phone - and the user reads that as the button not working. ggml polls
    // this callback between graph nodes, so a Stop pressed mid-batch lands mid-batch.
    // The session owns the flag and outlives the context, so the pointer stays valid.
    llama_set_abort_callback(
        ctx,
        [](void * data) -> bool {
            return static_cast<std::atomic<bool> *>(data)->load(std::memory_order_relaxed);
        },
        &session->cancelled_);

    if (!mmproj_path.empty()) {
        mtmd_context_params mtmd_params = mtmd_context_params_default();
        mtmd_params.use_gpu = n_gpu_layers > 0;
        mtmd_params.n_threads = n_threads;
        mtmd_params.print_timings = false;

        session->mtmd_ = mtmd_init_from_file(mmproj_path.c_str(), model, mtmd_params);
        if (session->mtmd_ == nullptr) {
            error = "could not load the multimodal projector at " + mmproj_path;
            return nullptr;
        }
    }

    try {
        session->chat_templates_ = common_chat_templates_init(model, "").release();
        session->probe_thinking_history();
    } catch (const std::exception & failure) {
        // A model's chat template is untrusted data from someone else's repository. A
        // parse failure must surface as an error, not unwind through JNI and take the
        // process with it.
        error = std::string("this model's chat template could not be read: ") + failure.what();
        return nullptr;
    }
    return session.release();
}

void Session::set_threads(int32_t n_threads, int32_t n_threads_batch) {
    llama_set_n_threads(ctx_, n_threads, n_threads_batch);
}

void Session::reset() {
    llama_memory_clear(llama_get_memory(ctx_), true);
    cached_.clear();
    n_past_ = 0;
    cached_covers_context_ = true;
}

Session::MediaSupport Session::media_support() const {
    if (mtmd_ == nullptr) return {};
    auto * ctx = static_cast<mtmd_context *>(mtmd_);
    return { mtmd_support_vision(ctx), mtmd_support_audio(ctx) };
}

bool Session::generates_speech() const {
    if (mtmd_ == nullptr) return false;
    auto * ctx = static_cast<mtmd_context *>(mtmd_);
    return mtmd_gen_audio_get_info(ctx).type != MTMD_GEN_AUDIO_TYPE_NONE;
}

std::string Session::media_marker() const {
    if (mtmd_ == nullptr) return mtmd_default_marker();
    return mtmd_get_marker(static_cast<mtmd_context *>(mtmd_));
}

int32_t Session::ingest_media_prompt(
    const std::string & prompt,
    const std::vector<std::string> & media_paths,
    std::string & error) {
    auto * ctx = static_cast<mtmd_context *>(mtmd_);

    std::vector<mtmd_bitmap *> bitmaps;
    for (const auto & path : media_paths) {
        // Before each file rather than only before the batch: libmtmd reads and decodes the
        // whole thing, and several large attachments are seconds of work before a single
        // chunk exists to be evaluated.
        if (cancelled_.load(std::memory_order_relaxed)) {
            for (auto * loaded : bitmaps) mtmd_bitmap_free(loaded);
            error = "cancelled";
            return -1;
        }
        if (file_size(path) > MAX_ATTACHMENT_BYTES) {
            for (auto * loaded : bitmaps) mtmd_bitmap_free(loaded);
            error = "that attachment is too large to process on this device";
            return -1;
        }
        // libmtmd decodes the file itself, so images and audio arrive the same way and the
        // projector decides what it can accept. It reads the whole file into memory and
        // allocates from its dimensions, both of which are attacker-controlled for a file
        // the user was handed: hence the size cap above and the catch below.
        mtmd_bitmap * bitmap = nullptr;
        try {
            auto wrapper = mtmd_helper_bitmap_init_from_file(ctx, path.c_str(), false);
            if (wrapper.video_ctx != nullptr) {
                mtmd_helper_video_free(wrapper.video_ctx);
            }
            bitmap = wrapper.bitmap;
        } catch (const std::exception &) {
            bitmap = nullptr;
        }
        if (bitmap == nullptr) {
            for (auto * loaded : bitmaps) mtmd_bitmap_free(loaded);
            error = "could not read the attached file: " + path;
            return -1;
        }
        bitmaps.push_back(bitmap);
    }

    mtmd_input_text input_text;
    input_text.text          = prompt.c_str();
    input_text.text_len      = prompt.size();
    input_text.add_special   = true;
    input_text.parse_special = true;

    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    const int32_t tokenized = mtmd_tokenize(
        ctx, chunks, &input_text,
        const_cast<const mtmd_bitmap **>(bitmaps.data()), bitmaps.size());

    for (auto * loaded : bitmaps) mtmd_bitmap_free(loaded);

    if (tokenized != 0) {
        mtmd_input_chunks_free(chunks);
        error = tokenized == 1
            ? "the number of attachments does not match the prompt"
            : "an attachment could not be prepared for this model";
        return -1;
    }

    // Media becomes embeddings rather than tokens, so there is nothing to compare a
    // prefix against. The context is rebuilt from scratch for these turns.
    llama_memory_clear(llama_get_memory(ctx_), true);
    cached_.clear();
    cached_covers_context_ = false;

    // Driven a chunk at a time rather than handed to mtmd_helper_eval_chunks, so that Stop
    // can be answered part way through. Text prefill has checked cancellation between
    // batches since it was written; this path was a single call that ran to completion
    // whatever the user did, so Stop during "reading the prompt" did nothing at all for as
    // long as it took. Measured at roughly thirteen seconds for one image and over a minute
    // for four video frames, with the phone hot and the button apparently dead.
    //
    // A chunk is the granularity the helper offers and it is the honest one: one image is
    // still one uninterruptible encode, so a single attachment answers Stop no faster than
    // before. What changes is the case that took the longest, where every frame after the
    // one in flight is now skipped.
    llama_pos new_n_past = 0;
    const size_t chunk_count = mtmd_input_chunks_size(chunks);
    int32_t evaluated = 0;
    for (size_t index = 0; index < chunk_count && evaluated == 0; ++index) {
        if (cancelled_.load(std::memory_order_relaxed)) {
            mtmd_input_chunks_free(chunks);
            error = "cancelled";
            return -1;
        }
        evaluated = mtmd_helper_eval_chunk_single(
            ctx, ctx_, mtmd_input_chunks_get(chunks, index), new_n_past, /*seq_id=*/0,
            static_cast<int32_t>(llama_n_batch(ctx_)),
            // Only the last one, which is what the all-in-one helper does: logits are read
            // once, from the end of the prompt.
            /*logits_last=*/index + 1 == chunk_count,
            &new_n_past);
    }

    mtmd_input_chunks_free(chunks);

    if (evaluated != 0) {
        error = "the model could not process the attachment";
        return -1;
    }
    return static_cast<int32_t>(new_n_past);
}

/**
 * True when telling this template not to think actually changes the prompt.
 *
 * Rendered twice and compared, the same way reasoning effort is decided, and for the same
 * reason: a control that changes nothing is worse than no control.
 *
 * llama.cpp's own `common_chat_templates_support_enable_thinking` was asked first and is
 * the wrong question. For a template handled by the generic parser it reports
 * `reasoning.mode != NONE`, which says this model reasons, not that it can be told not to.
 * LFM2.5 answers yes to that and then reasons anyway: measured across four questions with
 * the flag on and off, a thinking block came back four times out of four either way, on
 * the desktop and on the phone. The switch was offered on a promise nothing could keep.
 */
bool Session::supports_thinking() const {
    auto * templates = static_cast<common_chat_templates *>(chat_templates_);
    if (templates == nullptr) {
        return false;
    }

    auto render = [&](bool thinking) -> std::string {
        common_chat_templates_inputs inputs;
        inputs.add_generation_prompt = true;
        inputs.use_jinja = true;
        inputs.enable_thinking = thinking;

        common_chat_msg msg;
        msg.role = "user";
        msg.content = "probe";
        inputs.messages.push_back(msg);

        try {
            return common_chat_templates_apply(templates, inputs).prompt;
        } catch (const std::exception &) {
            return std::string();
        }
    };

    const std::string on = render(true);
    const std::string off = render(false);
    return !on.empty() && on != off;
}

bool Session::supports_tools() const {
    auto * templates = static_cast<common_chat_templates *>(chat_templates_);
    if (templates == nullptr) {
        return false;
    }

    // Ask the template rather than guess from the architecture, by rendering a tool with
    // an unmistakable name and looking for it. Handing a tool to a model whose template
    // ignores it is not an error anywhere in llama.cpp: the definition is silently dropped
    // and the model answers in prose, which is indistinguishable from a model that chose
    // not to call anything. This tells cannot apart from did not.
    static constexpr const char * kProbeName = "openweights_probe_tool";

    common_chat_templates_inputs inputs;
    inputs.add_generation_prompt = true;
    inputs.use_jinja = true;

    common_chat_msg msg;
    msg.role = "user";
    msg.content = "probe";
    inputs.messages.push_back(msg);
    inputs.tools.push_back({kProbeName, "probe", "{\"type\":\"object\",\"properties\":{}}"});

    try {
        const common_chat_params params = common_chat_templates_apply(templates, inputs);
        return params.prompt.find(kProbeName) != std::string::npos;
    } catch (const std::exception &) {
        // A template that throws on tools cannot use them either.
        return false;
    }
}

bool Session::supports_tool_results() const {
    auto * templates = static_cast<common_chat_templates *>(chat_templates_);
    if (templates == nullptr) {
        return false;
    }

    // The whole round rather than the tool message alone, because what these templates
    // refuse is the sequence and not the role: Gemma 3 raises "Conversation roles must
    // alternate user/assistant" the moment anything sits between a question and its
    // answer, which llama.cpp reports as being unable to build a parser. Asked with a
    // lone tool message the check would pass and the app would still fail on the shape a
    // turn actually has.
    common_chat_templates_inputs inputs;
    inputs.add_generation_prompt = true;
    inputs.use_jinja = true;

    const auto add = [&](const char * role, const char * content, const char * call_id) {
        common_chat_msg msg;
        msg.role = role;
        msg.content = content;
        if (call_id != nullptr) {
            msg.tool_call_id = call_id;
        }
        inputs.messages.push_back(msg);
    };
    // Two results rather than one, and each with a name only it could have put there.
    //
    // Both halves of that were wrong in the first version, which asked only whether the
    // render came back non-empty. That is the same mistake supports_tools was written to
    // avoid: a template with no branch for the tool role does not raise, it drops the
    // message and renders the two turns around it, so the prompt is non-empty and the probe
    // says yes. The app would then hand the result to a model that never receives it and
    // the turn answers as though nothing had run, which is the failure this whole function
    // exists to prevent, now silent.
    //
    // Two, because one call is not the shape a turn takes when a model asks for a search
    // and a fetch in the same breath. AgentRunner answers every call in a pass, so the real
    // conversation can carry two tool messages back to back, and a template that renders
    // one and refuses the pair would pass a one-message probe.
    static constexpr const char * kFirst = "openweights_probe_result_one";
    static constexpr const char * kSecond = "openweights_probe_result_two";
    add("user", "probe", nullptr);
    add("assistant", "probe", nullptr);
    add("tool", kFirst, "openweights_probe_call_one");
    add("tool", kSecond, "openweights_probe_call_two");

    try {
        const std::string prompt = common_chat_templates_apply(templates, inputs).prompt;
        // Both, so that a template which keeps only the last result is treated as unable to
        // carry them. Folding into a user turn works for any template, so the strict answer
        // is the safe one to be wrong about.
        return prompt.find(kFirst) != std::string::npos &&
            prompt.find(kSecond) != std::string::npos;
    } catch (const std::exception &) {
        return false;
    }
}

bool Session::supports_reasoning_effort() const {
    auto * templates = static_cast<common_chat_templates *>(chat_templates_);
    if (templates == nullptr) {
        return false;
    }

    // Rendered twice and compared, because there is no flag to ask. A template that reads
    // reasoning_effort puts something different in the prompt for "low" than for "high";
    // one that ignores the argument produces the same bytes both times, and offering the
    // user a control that changes nothing is worse than not offering it.
    auto render = [&](const char * effort) -> std::string {
        common_chat_templates_inputs inputs;
        inputs.add_generation_prompt = true;
        inputs.use_jinja = true;
        inputs.enable_thinking = true;
        inputs.chat_template_kwargs["reasoning_effort"] = std::string("\"") + effort + "\"";

        common_chat_msg msg;
        msg.role = "user";
        msg.content = "probe";
        inputs.messages.push_back(msg);

        try {
            return common_chat_templates_apply(templates, inputs).prompt;
        } catch (const std::exception &) {
            return std::string();
        }
    };

    const std::string low = render("low");
    const std::string high = render("high");
    return !low.empty() && low != high;
}

/**
 * Moves a leading `<think>...</think>` out of an assistant message and into its own field.
 *
 * This is about the KV cache rather than about presentation, and it is worth twelve seconds
 * a turn on the models this app recommends.
 *
 * LFM2.5's template pre-opens the thinking block in the generation prompt, so the tokens the
 * engine actually decoded for turn one end `<|im_start|>assistant\n<think>` followed by the
 * reply. When turn two re-renders the same conversation, the reply is now a history message,
 * and `common_chat_params_init_lfm2` only emits that `<think>` if the message carries
 * `reasoning_content`: it copies that field into the `thinking` variable the template reads.
 * Handing the template a message whose content still has the tags inside it is not the same
 * thing, because `common_chat_templates_apply` parses them straight back out again.
 *
 * So one token went missing from the re-render, at the position where the assistant turn
 * begins. Measured on an SM8650 with LFM2.5 2.6B, that one token cost the entire cache:
 * the prefix matched 1,158 of 1,204 tokens, the rollback to 1,158 was refused because a
 * hybrid model cannot erase part of its recurrent state, and the engine correctly started
 * over. A second turn re-read 1,220 tokens and took 9.6 seconds to do it. With this in
 * place the same turn re-reads 17 and takes 189 ms.
 *
 * The app writes this shape itself, in `assistantHistoryText`, so this is reading back
 * something the codebase produced rather than guessing at a model's. Anything that is not
 * that shape is left alone, which is what keeps it inert for a model whose template opens
 * no thinking block of its own.
 */
static void split_thinking(std::string & content, std::string & reasoning) {
    static const std::string OPEN  = "<think>";
    static const std::string CLOSE = "</think>";
    if (content.rfind(OPEN, 0) != 0) return;
    const size_t end = content.find(CLOSE, OPEN.size());
    if (end == std::string::npos) return;
    reasoning = content.substr(OPEN.size(), end - OPEN.size());
    content = content.substr(end + CLOSE.size());
}

bool Session::render_prompt(
    const std::vector<ChatMessage> & messages,
    const std::vector<ToolDefinition> & tools,
    const ReasoningConfig & reasoning,
    std::string & out,
    std::string & error,
    bool add_generation_prompt) {
    auto * templates = static_cast<common_chat_templates *>(chat_templates_);
    if (templates == nullptr) {
        error = "model has no chat template; it cannot be used for chat";
        return false;
    }

    common_chat_templates_inputs inputs;
    // False only for warming, which renders the head a future conversation will start
    // with: the generation prompt opens an assistant turn, and a warmed prefix ending in
    // one would stop matching the moment the real first turn renders a user turn there.
    inputs.add_generation_prompt = add_generation_prompt;
    inputs.use_jinja = true;
    // Ask the template to keep thinking separable; the parser then hands it back as
    // reasoning_content instead of leaving tags in the answer.
    inputs.reasoning_format = COMMON_REASONING_FORMAT_AUTO;
    inputs.enable_thinking = reasoning.enabled;
    // Send a reply's thinking back with it. LFM2.5's template drops thinking from every
    // assistant turn before the last user turn unless this is set, and dropping it is what
    // breaks the KV cache: see split_thinking above for the measurement. Different
    // template generations named the switch differently — LFM2.5-Thinking's reads
    // `keep_past_thinking` — and a template ignores the keys it does not know, so both
    // are always sent.
    inputs.chat_template_kwargs["preserve_thinking"] = "true";
    inputs.chat_template_kwargs["keep_past_thinking"] = "true";
    if (!reasoning.effort.empty()) {
        // A template argument rather than a field, because only some models read it. The
        // rest ignore the extra key.
        inputs.chat_template_kwargs["reasoning_effort"] = "\"" + reasoning.effort + "\"";
    }

    for (const auto & message : messages) {
        common_chat_msg msg;
        msg.role = message.role;
        msg.content = message.content;
        // A reply that was thought about has to go back the way it came, or the prefix
        // stops matching and the whole conversation is read again. See split_thinking —
        // and probe_thinking_history for the templates whose replay needs the opposite:
        // the block left in the content, verbatim.
        if (message.role == "assistant" && split_history_thinking_) {
            split_thinking(msg.content, msg.reasoning_content);
        }
        if (!message.tool_call_id.empty()) {
            msg.tool_call_id = message.tool_call_id;
        }
        inputs.messages.push_back(msg);
    }

    for (const auto & tool : tools) {
        inputs.tools.push_back({tool.name, tool.description, tool.parameters_json});
    }

    try {
        const common_chat_params params = common_chat_templates_apply(templates, inputs);
        // params.prompt already ends with whatever opens the assistant turn, for LFM2.5
        // that is "<|im_start|>assistant\n<think>", the template pre-filling the thinking
        // block. params.generation_prompt is the same text, kept separately so the parser
        // can account for it; appending it here would duplicate the turn header.
        out = params.prompt;
        last_generation_prompt_ = params.generation_prompt;

        // Being told not to think, on a template that opens the block anyway.
        //
        // LFM2.5's template ends the generation prompt with "<think>" whatever
        // `enable_thinking` says, so asking for no thinking did nothing at all: the model
        // began inside the block and reasoned for as long as it liked. Closing the block in
        // the prompt is the one thing that does work, and llama.cpp hands over the tags to
        // do it with, so this is not a guess about any particular model's syntax.
        //
        // Measured on the compaction call, which is where it matters most: LFM2.5 2.6B spent
        // 541 tokens producing a summary with the block open and 132 with it closed, for a
        // summary of comparable content that finished its last sentence instead of running
        // into the token cap. That is four times less generation on the app's longest pause.
        // Recorded whether or not thinking is on, because the caller needs it to store a
        // reply that will still match the cache next turn. See GenerationStats.
        thinking_prefilled_ = !params.thinking_start_tag.empty() &&
                              out.size() >= params.thinking_start_tag.size() &&
                              out.compare(out.size() - params.thinking_start_tag.size(),
                                          params.thinking_start_tag.size(),
                                          params.thinking_start_tag) == 0;
        thinking_start_tag_ = params.thinking_start_tag;
        thinking_end_tag_ = params.thinking_end_tags.empty() ? std::string()
                                                             : params.thinking_end_tags.front();

        if (!reasoning.enabled && !params.thinking_start_tag.empty() &&
            !params.thinking_end_tags.empty()) {
            const std::string & open = params.thinking_start_tag;
            const std::string & close = params.thinking_end_tags.front();
            if (out.size() >= open.size() &&
                out.compare(out.size() - open.size(), open.size(), open) == 0) {
                out += close;
                // The parser is given the generation prompt to line its rules up against,
                // so it has to see the same thing the model did.
                last_generation_prompt_ += close;
            }
        }

        // The reply has to be parsed with the same format it was rendered in, so both are
        // remembered here rather than recomputed later from a guess.
        last_format_ = static_cast<int>(params.format);
        last_grammar_ = GrammarSpec{};
        last_grammar_.grammar = params.grammar;
        last_grammar_.lazy = params.grammar_lazy;
        for (const auto & trigger : params.grammar_triggers) {
            switch (trigger.type) {
                case COMMON_GRAMMAR_TRIGGER_TYPE_WORD:
                    last_grammar_.trigger_patterns.push_back(regex_escape(trigger.value));
                    break;
                case COMMON_GRAMMAR_TRIGGER_TYPE_PATTERN:
                    last_grammar_.trigger_patterns.push_back(trigger.value);
                    break;
                case COMMON_GRAMMAR_TRIGGER_TYPE_PATTERN_FULL: {
                    // Anchored, because a full-match trigger means the whole output so far
                    // has to match, not merely contain, the pattern.
                    const std::string & pattern = trigger.value;
                    std::string anchored = "^$";
                    if (!pattern.empty()) {
                        anchored = (pattern.front() != '^' ? "^" : "") + pattern +
                            (pattern.back() != '$' ? "$" : "");
                    }
                    last_grammar_.trigger_patterns.push_back(anchored);
                    break;
                }
                case COMMON_GRAMMAR_TRIGGER_TYPE_TOKEN:
                    last_grammar_.trigger_tokens.push_back(trigger.token);
                    break;
                default:
                    break;
            }
        }
        // The prompt contains the user's conversation, so it is never logged.
        return true;
    } catch (const std::exception & failure) {
        error = std::string("failed to apply the model's chat template: ") + failure.what();
        return false;
    }
}

bool Session::ingest_prompt(
    const std::vector<llama_token> & tokens,
    size_t from,
    std::string & error) {
    const int32_t n_batch = static_cast<int32_t>(llama_n_batch(ctx_));

    for (size_t offset = from; offset < tokens.size(); offset += n_batch) {
        // Between batches, because prefill is the one part of a turn that can run for
        // seconds without producing a token. Cancellation used to be read only once the
        // sampling loop began, so Stop on a long prompt did nothing at all: the phone kept
        // decoding the whole conversation before noticing it had been asked not to.
        if (cancelled_.load(std::memory_order_relaxed)) {
            error = "cancelled";
            return false;
        }
        const int32_t chunk =
            std::min<int32_t>(n_batch, static_cast<int32_t>(tokens.size() - offset));
        llama_batch batch =
            llama_batch_get_one(const_cast<llama_token *>(tokens.data() + offset), chunk);
        const int ret = llama_decode(ctx_, batch);
        if (ret != 0) {
            // The abort callback ends a decode from inside the graph, and llama reports
            // that as a non-zero return. That is Stop working, not the model failing.
            if (cancelled_.load(std::memory_order_relaxed)) {
                error = "cancelled";
                return false;
            }
            error = "failed to decode prompt (llama_decode returned " + std::to_string(ret) + ")";
            return false;
        }
    }
    return true;
}

size_t Session::align_cache(const std::vector<llama_token> & tokens, bool need_logits) {
    size_t reusable = 0;
    while (cached_covers_context_ && reusable < cached_.size() && reusable < tokens.size() &&
           cached_[reusable] == tokens[reusable]) {
        ++reusable;
    }
    // Never reuse the entire prompt when logits are wanted: at least one token must be
    // decoded to have something to sample from. A warm samples nothing, so it may.
    if (need_logits && reusable == tokens.size() && reusable > 0) {
        --reusable;
    }

    // Where the cache stopped matching, in tokens.
    //
    // Worth a line of log because the alternative is inferring it. A turn that re-reads
    // the whole conversation can be a history that does not match what was generated, a
    // template that renders a rebuilt turn a token differently, or anything volatile
    // near the head of the prompt, and those want different fixes. The number says
    // which: a divergence a few hundred tokens in is the tail of the conversation, one
    // at twenty is the system message or the tool block.
    if (reusable < cached_.size()) {
        LOGI("kv: diverged at %zu of %zu cached, prompt %zu, re-reading %zu",
             reusable, cached_.size(), tokens.size(), tokens.size() - reusable);
    }

    // The snapshot beats whatever the cache offers whenever the prompt carries the whole
    // warmed prefix and the cache matches less of it. The empty cache is the common case:
    // a fresh context after load, reset or an error holds nothing, no rollback is ever
    // attempted, and without this check the snapshot sat unused while two thousand tokens
    // were read again.
    if (!prefix_state_.empty() && prefix_tokens_.size() > reusable) {
        size_t on_prefix = 0;
        while (on_prefix < prefix_tokens_.size() && on_prefix < tokens.size() &&
               prefix_tokens_[on_prefix] == tokens[on_prefix]) {
            ++on_prefix;
        }
        if (on_prefix == prefix_tokens_.size() && (on_prefix < tokens.size() || !need_logits)) {
            reset();
            const size_t applied = llama_state_seq_set_data(
                ctx_, prefix_state_.data(), prefix_state_.size(), 0);
            if (applied != 0) {
                cached_ = prefix_tokens_;
                n_past_ = static_cast<int32_t>(prefix_tokens_.size());
                LOGI("kv: restored the warm prefix of %zu, reading %zu instead of %zu",
                     prefix_tokens_.size(), tokens.size() - prefix_tokens_.size(),
                     tokens.size());
                return prefix_tokens_.size();
            }
            LOGI("kv: warm prefix restore failed, starting cold");
            reset();
            return 0;
        }
    }

    // Compared against the cache position rather than the token count, because a
    // previous turn with an attachment leaves positions filled that no token describes.
    //
    // The answer matters and used to be thrown away. A transformer's cache can be cut at
    // any position, but a recurrent or hybrid one carries a running state rather than a
    // row per token, so it can only roll back as far as it kept snapshots for, which by
    // default is none. It says so by returning false, and the old code rewound n_past_
    // anyway. Nothing was removed, and llama_batch_get_one leaves the positions unset, so
    // the next batch was placed after the tail that was supposed to be gone: the model
    // attended to text nobody could see, the cache grew on every turn, and thirty or so
    // generations later llama_decode returned 1 with no slot left. That is the LFM2
    // failure, and Granite-hybrid, Jamba and Nemotron-H reach it by the same route.
    if (static_cast<int32_t>(reusable) < n_past_) {
        const bool rolled_back = llama_memory_seq_rm(
            llama_get_memory(ctx_), 0, static_cast<llama_pos>(reusable), -1);
        // A sliding-window model answers yes to the removal and still cannot serve the
        // prefix. Its local-attention layers keep only the last n_swa positions, and
        // those cells are recycled as the conversation grows past them; roll back to a
        // point older than the window and the cells the next token needs are gone, with
        // no error to say so. llama.h says to ask the memory what it still holds, and
        // llama-server does exactly this check before trusting a reuse: the smallest
        // position left must reach back a window's width from the cut.
        const bool window_intact = rolled_back && swa_window_reaches(reusable);
        if (!window_intact) {
            // The families that refuse are the ones the warm snapshot exists for. A new
            // conversation shares its first two thousand tokens with the snapshot, so if
            // this prompt extends the snapshotted prefix, restoring it turns "re-read
            // everything" into "re-read the question".
            size_t on_prefix = 0;
            while (on_prefix < prefix_tokens_.size() && on_prefix < tokens.size() &&
                   prefix_tokens_[on_prefix] == tokens[on_prefix]) {
                ++on_prefix;
            }
            const bool extends_prefix = !prefix_state_.empty() &&
                on_prefix == prefix_tokens_.size() && on_prefix > 0 &&
                (on_prefix < tokens.size() || !need_logits);
            if (extends_prefix) {
                reset();
                const size_t applied = llama_state_seq_set_data(
                    ctx_, prefix_state_.data(), prefix_state_.size(), 0);
                if (applied != 0) {
                    cached_ = prefix_tokens_;
                    n_past_ = static_cast<int32_t>(prefix_tokens_.size());
                    LOGI("kv: rollback refused; restored the warm prefix of %zu instead of "
                         "re-reading all %zu",
                         prefix_tokens_.size(), tokens.size());
                    return prefix_tokens_.size();
                }
                LOGI("kv: warm prefix restore failed, re-reading all %zu", tokens.size());
                reset();
                return 0;
            }
            // Said out loud, because the divergence line above only names the
            // candidate. Reading that line as what was reused sent a whole
            // investigation the wrong way: on a hybrid model this branch turns
            // "re-reading 674" into re-reading everything.
            LOGI("kv: rollback to %zu refused (%s), re-reading all %zu",
                 reusable, rolled_back ? "sliding window passed it" : "recurrent state",
                 tokens.size());
            reset();
            return 0;
        }
    }
    cached_.resize(reusable);
    n_past_ = static_cast<int32_t>(reusable);
    return reusable;
}

bool Session::keeps_no_full_history() const {
    return llama_model_is_hybrid(model_) || llama_model_is_recurrent(model_) ||
           llama_model_n_swa(model_) > 0;
}

bool Session::swa_window_reaches(size_t position) const {
    const int32_t n_swa = llama_model_n_swa(model_);
    if (n_swa <= 0 || position == 0) return true;
    // The iSWA memory reports the sliding cache's own minimum, which is the question:
    // the full cache behind it holds everything and is not where the gap would be.
    const llama_pos pos_min = llama_memory_seq_pos_min(llama_get_memory(ctx_), 0);
    if (pos_min < 0) return false;  // nothing left in the window at all
    const llama_pos needed_from =
        std::max<llama_pos>(0, static_cast<llama_pos>(position) - n_swa);
    return pos_min <= needed_from;
}

bool Session::ingest_warm(
    const std::vector<llama_token> & tokens,
    size_t from,
    std::string & error) {
    const int32_t n_batch = static_cast<int32_t>(llama_n_batch(ctx_));

    // A recurrent or hybrid memory cannot cut a half-written batch away, so without help
    // an interrupted warm forfeits everything it read: measured live, sixteen seconds of
    // prefill kept nothing, and the turn that interrupted it paid the whole read again in
    // the foreground. So on those families each committed batch is checkpointed — a state
    // copy, ~26 MB and milliseconds against a batch that costs seconds — and a failed or
    // aborted batch restores the last checkpoint instead of conceding the cache.
    const bool checkpoint =
        llama_model_is_hybrid(model_) || llama_model_is_recurrent(model_);
    std::vector<uint8_t> committed_state;

    for (size_t offset = from; offset < tokens.size(); offset += n_batch) {
        if (cancelled_.load(std::memory_order_relaxed)) {
            error = "cancelled";
            return false;
        }
        const int32_t chunk =
            std::min<int32_t>(n_batch, static_cast<int32_t>(tokens.size() - offset));
        llama_batch batch =
            llama_batch_get_one(const_cast<llama_token *>(tokens.data() + offset), chunk);
        const int ret = llama_decode(ctx_, batch);
        if (ret != 0) {
            // An aborted or failed batch may have written some of its cells. Committed
            // progress stays if the half-written tail can be removed, or — where the
            // memory refuses — if there is a checkpoint to fall back to; only with
            // neither does the whole cache go, rather than answering from positions
            // nobody can account for.
            if (!llama_memory_seq_rm(
                    llama_get_memory(ctx_), 0, static_cast<llama_pos>(n_past_), -1)) {
                if (!committed_state.empty()) {
                    const std::vector<llama_token> kept_tokens = cached_;
                    const int32_t kept_past = n_past_;
                    reset();
                    if (llama_state_seq_set_data(
                            ctx_, committed_state.data(), committed_state.size(), 0) != 0) {
                        cached_ = kept_tokens;
                        n_past_ = kept_past;
                        LOGI("kv: interrupted warm kept %d committed tokens", n_past_);
                    }
                } else {
                    reset();
                }
            }
            error = cancelled_.load(std::memory_order_relaxed)
                ? "cancelled"
                : "failed to decode prompt (llama_decode returned " + std::to_string(ret) + ")";
            return false;
        }
        // Committed per batch, so a cancelled warm keeps what it finished: the next turn
        // reuses the partial prefix instead of starting over.
        cached_.insert(cached_.end(), tokens.begin() + offset, tokens.begin() + offset + chunk);
        n_past_ += chunk;
        if (checkpoint && offset + chunk < tokens.size()) {
            const size_t size = llama_state_seq_get_size(ctx_, 0);
            if (size > 0) {
                committed_state.resize(size);
                if (llama_state_seq_get_data(
                        ctx_, committed_state.data(), size, 0) != size) {
                    committed_state.clear();
                }
            }
        }
    }
    return true;
}

void Session::probe_thinking_history() {
    auto * templates = static_cast<common_chat_templates *>(chat_templates_);
    if (templates == nullptr) return;

    static const std::string REASONING = "R7R";
    static const std::string ANSWER    = "C7C";

    const auto renders = [&](bool split) -> bool {
        common_chat_templates_inputs inputs;
        inputs.add_generation_prompt = true;
        inputs.use_jinja = true;
        inputs.reasoning_format = COMMON_REASONING_FORMAT_AUTO;
        inputs.enable_thinking = true;
        inputs.chat_template_kwargs["preserve_thinking"] = "true";
        inputs.chat_template_kwargs["keep_past_thinking"] = "true";

        common_chat_msg user;
        user.role = "user";
        user.content = "u";
        common_chat_msg reply;
        reply.role = "assistant";
        if (split) {
            reply.content = ANSWER;
            reply.reasoning_content = REASONING;
        } else {
            reply.content = "<think>" + REASONING + "</think>" + ANSWER;
        }
        common_chat_msg again = user;
        again.content = "v";
        inputs.messages = {user, reply, again};

        try {
            const common_chat_params params = common_chat_templates_apply(templates, inputs);
            // Survival and order, not exact bytes: families decorate the block with
            // their own newlines, and the sentinels are unique enough that presence
            // means the history kept the thought. Byte-exactness is what the kv log's
            // divergence line checks against real traffic.
            const size_t thought = params.prompt.find(REASONING);
            const size_t answer  = params.prompt.find(ANSWER);
            return thought != std::string::npos && answer != std::string::npos &&
                thought < answer;
        } catch (const std::exception &) {
            return false;
        }
    };

    if (renders(/*split=*/true)) {
        split_history_thinking_ = true;
        return;
    }
    if (renders(/*split=*/false)) {
        split_history_thinking_ = false;
        LOGI("kv: this template keeps past thinking inline; replaying it verbatim");
        return;
    }
    split_history_thinking_ = true;
    LOGI("kv: this template drops past thinking; every turn after a thought reply "
         "re-reads the conversation");
}

void Session::maybe_snapshot() {
    // A full-attention transformer can roll any conversation back to the shared prefix,
    // so for it the snapshot would be memory spent on a problem it does not have. The
    // families that cannot are the recurrent and hybrid ones, whose state has no rows to
    // cut, and the sliding-window ones, whose local layers have forgotten the head by
    // the time a conversation is long enough to want a new chat.
    if (!keeps_no_full_history()) return;
    if (cached_.empty() || prefix_tokens_ == cached_) return;

    const size_t size = llama_state_seq_get_size(ctx_, 0);
    if (size == 0) {
        LOGI("kv: warm snapshot unavailable for this model");
        return;
    }
    std::vector<uint8_t> data(size);
    const size_t written = llama_state_seq_get_data(ctx_, data.data(), data.size(), 0);
    if (written == 0) {
        LOGI("kv: warm snapshot failed");
        return;
    }
    data.resize(written);
    prefix_state_  = std::move(data);
    prefix_tokens_ = cached_;
    LOGI("kv: warm prefix snapshot of %zu tokens, %zu KB",
         prefix_tokens_.size(), prefix_state_.size() / 1024);
}

bool Session::warm(
    const std::vector<ChatMessage> & messages,
    const std::vector<ToolDefinition> & tools,
    const ReasoningConfig & reasoning,
    bool snapshot,
    const char * store,
    WarmStats & stats,
    std::string & error) {
    cancelled_.store(false, std::memory_order_relaxed);

    std::string prompt;
    if (!render_prompt(messages, tools, reasoning, prompt, error,
                       /*add_generation_prompt=*/false)) {
        return false;
    }

    const llama_vocab * vocab = llama_model_get_vocab(model_);
    const bool add_special = true;
    const int32_t needed = -llama_tokenize(
        vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
        nullptr, 0, add_special, true);
    if (needed <= 0) {
        error = "the chat template produced an empty prefix";
        return false;
    }
    std::vector<llama_token> tokens(needed);
    if (llama_tokenize(
            vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
            tokens.data(), static_cast<int32_t>(tokens.size()), add_special, true) < 0) {
        error = "failed to tokenize the prefix";
        return false;
    }
    if (static_cast<int32_t>(tokens.size()) >= static_cast<int32_t>(llama_n_ctx(ctx_))) {
        error = "the prefix is longer than the context window";
        return false;
    }

    const int64_t start = now_ms();

    // Already warm: the cache starts with exactly these tokens. The snapshot is still
    // captured when it is missing and the cache holds the prefix alone, which is the
    // load-then-warm-twice case rather than the mid-conversation one.
    if (cached_covers_context_ && tokens.size() <= cached_.size() &&
        std::equal(tokens.begin(), tokens.end(), cached_.begin())) {
        stats.reused_tokens = static_cast<int32_t>(tokens.size());
        if (snapshot && cached_.size() == tokens.size()) {
            maybe_snapshot();
        }
        const bool stateful = keeps_no_full_history();
        const bool snapshot_missing = snapshot && stateful &&
            (prefix_state_.empty() || prefix_tokens_ != tokens);
        if (!snapshot_missing) {
            stats.snapshot_bytes = static_cast<int64_t>(prefix_state_.size());
            return true;
        }
        // The head is cached but the snapshot of it is gone or describes another day's
        // head — a turn interrupted the first warm of this session, and from then on
        // the cache always holds a conversation the capture cannot take a head from.
        // Left alone, every new chat re-reads the whole head, because these families
        // refuse rollback. The disk file is the cheap way back; failing that, the head
        // is re-read here, while nobody is waiting, so it is paid once instead of on
        // every plus-button chat.
        if (store != nullptr && arm_warm_file(store, tokens)) {
            stats.snapshot_bytes = static_cast<int64_t>(prefix_state_.size());
            LOGI("kv: warm snapshot armed from disk: %zu tokens, %zu KB",
                 prefix_tokens_.size(), prefix_state_.size() / 1024);
            return true;
        }
        LOGI("kv: warm snapshot missing; re-reading %zu tokens to rebuild it",
             tokens.size());
        reset();
    }

    // A state file beats any partial the cache holds: restoring is one read of the
    // whole prefix, computed on some earlier launch, against re-reading whatever the
    // cache cannot offer at seconds per hundred tokens.
    if (store != nullptr && restore_warm_file(store, tokens)) {
        stats.reused_tokens = static_cast<int32_t>(tokens.size());
        stats.prefill_ms    = now_ms() - start;
        stats.snapshot_bytes = static_cast<int64_t>(prefix_state_.size());
        LOGI("kv: warm restored from disk: %zu tokens in %lld ms",
             tokens.size(), static_cast<long long>(stats.prefill_ms));
        return true;
    }

    const size_t from = align_cache(tokens, /*need_logits=*/false);
    stats.reused_tokens = static_cast<int32_t>(from);

    if (!ingest_warm(tokens, from, error)) {
        // What the batches that finished added; zero when a refused rollback gave up the
        // whole cache on the way out.
        const int32_t kept = static_cast<int32_t>(cached_.size()) - stats.reused_tokens;
        stats.prompt_tokens = kept > 0 ? kept : 0;
        stats.prefill_ms    = now_ms() - start;
        return false;
    }

    stats.prompt_tokens = static_cast<int32_t>(tokens.size() - from);
    stats.prefill_ms    = now_ms() - start;
    cached_ = tokens;
    n_past_ = static_cast<int32_t>(tokens.size());
    cached_covers_context_ = true;
    if (snapshot) {
        maybe_snapshot();
    }
    stats.snapshot_bytes = static_cast<int64_t>(prefix_state_.size());
    LOGI("kv: warmed %d tokens in %lld ms (%d reused)",
         stats.prompt_tokens, static_cast<long long>(stats.prefill_ms), stats.reused_tokens);
    // Written only after a warm that completed, and never while a turn is waiting: the
    // write costs a few hundred milliseconds this holds the engine for, and a cancel
    // that has landed means somebody is.
    if (store != nullptr && !cancelled_.load(std::memory_order_relaxed)) {
        save_warm_file(store, tokens);
    }
    return true;
}

bool Session::read_warm_file(
    const char * path,
    const std::vector<llama_token> & tokens,
    std::vector<uint8_t> & state) {
    FILE * file = fopen(path, "rb");
    if (file == nullptr) return false;

    char magic[4];
    uint32_t version = 0;
    uint32_t stamp_size = 0;
    bool header_ok =
        fread(magic, 1, 4, file) == 4 && memcmp(magic, "OWWM", 4) == 0 &&
        fread(&version, sizeof(version), 1, file) == 1 && version == kWarmFileVersion &&
        fread(&stamp_size, sizeof(stamp_size), 1, file) == 1 &&
        stamp_size > 0 && stamp_size <= kWarmFileMaxStampBytes;

    if (header_ok) {
        std::string stamp(stamp_size, '\0');
        header_ok = fread(&stamp[0], 1, stamp_size, file) == stamp_size;
        if (header_ok && stamp != warm_file_stamp()) {
            // Another build's bytes: skipped whole, as if there were no file. Deleted
            // below with every other mismatch, since that build is not coming back.
            LOGI("kv: warm file is from llama build %s, this is %s; starting cold",
                 stamp.c_str(), warm_file_stamp().c_str());
            header_ok = false;
        }
    }

    uint32_t count = 0;
    uint64_t state_size = 0;
    header_ok = header_ok &&
        fread(&count, sizeof(count), 1, file) == 1 &&
        count == tokens.size() && count > 0 &&
        fread(&state_size, sizeof(state_size), 1, file) == 1 &&
        state_size > 0 && state_size <= kWarmFileMaxBytes;

    std::vector<llama_token> stored;
    if (header_ok) {
        stored.resize(count);
        header_ok = fread(stored.data(), sizeof(llama_token), count, file) == count &&
            stored == tokens;
    }

    if (header_ok) {
        state.resize(state_size);
        header_ok = fread(state.data(), 1, state_size, file) == state_size;
    }
    fclose(file);

    if (!header_ok) {
        // A stale file — yesterday's date line, changed settings, replaced weights, an
        // older format — never matches twice; holding it only costs the next launch this
        // same read.
        state.clear();
        remove(path);
        return false;
    }
    return true;
}

bool Session::arm_warm_file(const char * path, const std::vector<llama_token> & tokens) {
    std::vector<uint8_t> state;
    if (!read_warm_file(path, tokens, state)) return false;
    prefix_tokens_ = tokens;
    prefix_state_  = std::move(state);
    return true;
}

bool Session::restore_warm_file(const char * path, const std::vector<llama_token> & tokens) {
    std::vector<uint8_t> state;
    if (!read_warm_file(path, tokens, state)) return false;

    reset();
    if (llama_state_seq_set_data(ctx_, state.data(), state.size(), 0) == 0) {
        // llama's own validation refused it: wrong architecture, context, or version.
        reset();
        remove(path);
        return false;
    }
    cached_ = tokens;
    n_past_ = static_cast<int32_t>(tokens.size());
    cached_covers_context_ = true;
    if (keeps_no_full_history()) {
        // The RAM snapshot armed from the same bytes, with no compute: everything the
        // restore machinery does for a new chat works from the first second.
        prefix_tokens_ = tokens;
        prefix_state_  = std::move(state);
    }
    return true;
}

void Session::save_warm_file(const char * path, const std::vector<llama_token> & tokens) {
    std::vector<uint8_t> state;
    if (!prefix_state_.empty() && prefix_tokens_ == tokens) {
        state = prefix_state_;
    } else {
        const size_t size = llama_state_seq_get_size(ctx_, 0);
        if (size == 0 || size > kWarmFileMaxBytes) return;
        state.resize(size);
        if (llama_state_seq_get_data(ctx_, state.data(), size, 0) != size) return;
    }

    // A stamp the reader would refuse is a file not worth writing.
    const std::string & stamp = warm_file_stamp();
    if (stamp.empty() || stamp.size() > kWarmFileMaxStampBytes) return;

    const std::string tmp = std::string(path) + ".tmp";
    FILE * file = fopen(tmp.c_str(), "wb");
    if (file == nullptr) return;
    const uint32_t version = kWarmFileVersion;
    const uint32_t stamp_size = static_cast<uint32_t>(stamp.size());
    const uint32_t count = static_cast<uint32_t>(tokens.size());
    const uint64_t state_size = static_cast<uint64_t>(state.size());
    const bool ok =
        fwrite("OWWM", 1, 4, file) == 4 &&
        fwrite(&version, sizeof(version), 1, file) == 1 &&
        fwrite(&stamp_size, sizeof(stamp_size), 1, file) == 1 &&
        fwrite(stamp.data(), 1, stamp.size(), file) == stamp.size() &&
        fwrite(&count, sizeof(count), 1, file) == 1 &&
        fwrite(&state_size, sizeof(state_size), 1, file) == 1 &&
        fwrite(tokens.data(), sizeof(llama_token), tokens.size(), file) == tokens.size() &&
        fwrite(state.data(), 1, state.size(), file) == state.size();
    fclose(file);
    if (!ok || rename(tmp.c_str(), path) != 0) {
        remove(tmp.c_str());
        return;
    }
    LOGI("kv: warm state saved: %zu tokens, %zu KB", tokens.size(), state.size() / 1024);
}

StopReason Session::generate(
    const std::vector<ChatMessage> & messages,
    const std::vector<ToolDefinition> & tools,
    const SamplerConfig & sampler_config,
    const ReasoningConfig & reasoning,
    const TokenCallback & on_token,
    GenerationStats & stats,
    ParsedReply & reply,
    std::string & error) {
    cancelled_.store(false, std::memory_order_relaxed);

    std::string prompt;
    if (!render_prompt(messages, tools, reasoning, prompt, error)) {
        return StopReason::ERROR;
    }

    const llama_vocab * vocab = llama_model_get_vocab(model_);
    const int32_t n_ctx = static_cast<int32_t>(llama_n_ctx(ctx_));

    // Attachments, in the order their markers appear across the whole conversation. The
    // projector matches them to markers positionally, so this order is what makes the
    // right image line up with the right turn.
    std::vector<std::string> media_paths;
    for (const auto & message : messages) {
        media_paths.insert(
            media_paths.end(), message.media_paths.begin(), message.media_paths.end());
    }
    if (!media_paths.empty() && mtmd_ == nullptr) {
        error = "this model cannot read attachments: it was loaded without a projector";
        return StopReason::ERROR;
    }

    const int64_t prefill_start = now_ms();

    if (!media_paths.empty()) {
        // Media becomes embeddings, which cannot be compared against cached tokens, so a
        // turn with an attachment re-evaluates the conversation from the start.
        const int32_t evaluated = ingest_media_prompt(prompt, media_paths, error);
        if (evaluated < 0) {
            // The same reckoning the text path does, and for the same reason. Media prefill
            // empties the cache before it evaluates anything, so a failure part way through
            // leaves n_past_ describing a prefix that is no longer there: the next turn
            // finds a cache it thinks is warm, skips a prefix that was cleared, and decodes
            // from a position the model never saw. reset() puts the three back in
            // agreement.
            reset();
            // Stop is not a failure, and reporting it as one puts an error on screen for
            // something the user asked for.
            return cancelled_.load(std::memory_order_relaxed)
                ? StopReason::CANCELLED
                : StopReason::ERROR;
        }
        // Assigned before the length check: the cache is already full of these positions,
        // and reporting zero would show an empty context meter over a full context.
        n_past_ = evaluated;
        stats.prompt_tokens = evaluated;
        stats.context_used  = evaluated;
        stats.context_size  = n_ctx;
        if (evaluated >= n_ctx) {
            error = "the conversation and its attachments are longer than the context window";
            return StopReason::CONTEXT_FULL;
        }
    } else {
        // Tokenize the whole conversation, then reuse whatever prefix is already cached.
        //
        // add_special is unconditionally true: we re-render the entire conversation every
        // turn, so the token sequence must be built identically each time or the prefix
        // comparison below finds no match and every turn re-decodes from scratch. Whether a
        // BOS is actually inserted is the vocab's decision (models whose template already
        // emits one set add_bos_token = false), so this cannot double up.
        const bool add_special = true;
        const int32_t n_tokens_needed = -llama_tokenize(
            vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
            nullptr, 0, add_special, true);
        if (n_tokens_needed <= 0) {
            error = "the model's chat template produced an empty prompt";
            return StopReason::ERROR;
        }
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
                    std::to_string(prompt_tokens.size()) + " > " +
                    std::to_string(n_ctx) + " tokens)";
            return StopReason::CONTEXT_FULL;
        }

        const size_t reusable = align_cache(prompt_tokens, /*need_logits=*/true);

        if (!ingest_prompt(prompt_tokens, reusable, error)) {
            // Whatever decoded before the stop is in the cache, and the bookkeeping above says
            // it is not: cached_ and n_past_ still describe the prefix, not the batches that
            // went in after it. Left that way the next turn finds nothing to remove, appends
            // after the orphaned tokens, and answers from a conversation with a ghost in it.
            // Pressing Stop during a long prefill is the ordinary way to reach this.
            reset();
            // Stop during prefill is not a failure, and reporting it as one would put an
            // error on screen for something the user asked for.
            return cancelled_.load(std::memory_order_relaxed)
                ? StopReason::CANCELLED
                : StopReason::ERROR;
        }

        stats.prompt_tokens = static_cast<int32_t>(prompt_tokens.size() - reusable);
        stats.cached_tokens = static_cast<int32_t>(reusable);
        cached_ = prompt_tokens;
        n_past_ = static_cast<int32_t>(prompt_tokens.size());
        cached_covers_context_ = true;
    }

    const int64_t prefill_end = now_ms();
    stats.prefill_ms   = prefill_end - prefill_start;
    stats.context_size = n_ctx;

    llama_sampler * grammar = build_grammar(last_grammar_, vocab);
    // Null means two different things here and only one of them is a failure. A grammar
    // that is not lazy is declined on purpose, for the reasons in build_grammar: it would
    // have to be checked against the whole vocabulary from the first token, which on this
    // hardware costs more than the tool call is worth. Treating that as a parse error ended
    // the turn with "failed to parse the tool-call grammar" after the entire prompt had
    // already been prefilled, for a template that had done nothing wrong. A lazy grammar
    // that still comes back null is the real failure, because that one was attempted.
    if (grammar == nullptr && !last_grammar_.grammar.empty() && last_grammar_.lazy) {
        error = "failed to parse the tool-call grammar the chat template produced";
        return StopReason::ERROR;
    }
    // Owned by the chain from here: llama_sampler_free on the chain frees its children.
    llama_sampler * sampler = build_sampler(sampler_config, vocab, grammar);
    StopReason reason = StopReason::END_OF_TURN;
    int64_t first_token_ms = 0;

    std::vector<char> piece_buffer(512);
    // Everything the model produced, and the subset of it that is well formed. The parser
    // reads the second: a trailing half character withheld from the UI must not reappear
    // in the finished reply.
    std::string raw_reply;
    std::string safe_reply;
    // Bytes seen but not yet emitted, because they are the start of a character whose
    // remaining bytes are still to come.
    std::string pending;
    bool return_parsed_content = false;

    // Draft-free speculation, see speculate_ in the header. The drafter reads the cache
    // record as the history, so it needs the record to be whole, and a rejected tail is
    // dropped with a partial rollback, which a hybrid or recurrent memory refuses.
    const bool speculating = speculate_ && cached_covers_context_ && !keeps_no_full_history();
    const common_ngram_simple_config spec_config{SPEC_NGRAM_SIZE, SPEC_DRAFT_MAX};
    llama_batch spec_batch = speculating ? llama_batch_init(SPEC_DRAFT_MAX + 1, 0, 1) : llama_batch{};
    std::vector<llama_token> draft;
    // Tokens the model has already chosen and the cache already holds, waiting to be
    // emitted in order: a verified draft yields several per step.
    std::deque<llama_token> chosen;
    // The one token chosen but not yet in the cache: the correction after a rejected
    // draft, or the token after a fully accepted one. It is committed by the next step.
    bool has_pending_token = false;
    llama_token pending_token = 0;

    // The reasoning budget, see SamplerConfig. The block is open from the start when the
    // template pre-filled the opening tag and nothing closed it in the prompt; otherwise
    // it opens when the model writes the tag itself.
    const bool budgeted = sampler_config.reasoning_budget >= 0 && !thinking_end_tag_.empty();
    bool in_thinking = budgeted && thinking_prefilled_ &&
        !ends_with(last_generation_prompt_, thinking_end_tag_);
    int32_t thinking_tokens = 0;
    while (true) {
        if (cancelled_.load(std::memory_order_relaxed)) {
            reason = StopReason::CANCELLED;
            break;
        }
        if (sampler_config.max_tokens > 0 && stats.generated_tokens >= sampler_config.max_tokens) {
            reason = StopReason::MAX_TOKENS;
            break;
        }
        if (n_past_ >= n_ctx) {
            reason = StopReason::CONTEXT_FULL;
            break;
        }

        if (in_thinking && thinking_tokens >= sampler_config.reasoning_budget) {
            // Thought enough. Whatever was chosen past this point was more thinking:
            // the queued accepted tokens leave the cache, the pending one was never in it.
            if (!chosen.empty()) {
                const int32_t unread = static_cast<int32_t>(chosen.size());
                n_past_ -= unread;
                cached_.resize(cached_.size() - static_cast<size_t>(unread));
                if (!llama_memory_seq_rm(
                        llama_get_memory(ctx_), 0, static_cast<llama_pos>(n_past_), -1)) {
                    reset();
                }
                chosen.clear();
            }
            has_pending_token = false;
            const std::string close = thinking_end_tag_ + "\n";
            std::vector<llama_token> close_tokens = common_tokenize(vocab, close, false, true);
            if (close_tokens.empty() || n_past_ + static_cast<int32_t>(close_tokens.size()) >= n_ctx) {
                reason = StopReason::CONTEXT_FULL;
                break;
            }
            llama_batch close_batch =
                llama_batch_get_one(close_tokens.data(), static_cast<int32_t>(close_tokens.size()));
            if (llama_decode(ctx_, close_batch) != 0) {
                error = "failed to close the thinking block";
                reason = StopReason::ERROR;
                break;
            }
            for (const llama_token closing : close_tokens) {
                llama_sampler_accept(sampler, closing);
            }
            n_past_ += static_cast<int32_t>(close_tokens.size());
            if (cached_covers_context_) {
                cached_.insert(cached_.end(), close_tokens.begin(), close_tokens.end());
            }
            // Into the reply as text the parser and the screen both see, so the block
            // reads as closed everywhere and the next turn's history matches the cache.
            raw_reply += close;
            safe_reply += close;
            in_thinking = false;
            LOGI("reasoning: closed the block at the budget of %d", sampler_config.reasoning_budget);
            if (!on_token(close.c_str())) {
                reason = StopReason::CANCELLED;
                break;
            }
        }

        llama_token token;
        // Whether `token` is already in the cache (an accepted draft token) or still has
        // to be decoded into it (everything else).
        bool already_committed = false;
        if (!chosen.empty()) {
            token = chosen.front();
            chosen.pop_front();
            already_committed = true;
        } else if (has_pending_token) {
            token = pending_token;
            has_pending_token = false;
        } else {
            token = llama_sampler_sample(sampler, ctx_, -1);
        }
        if (llama_vocab_is_eog(vocab, token)) {
            reason = StopReason::END_OF_TURN;
            break;
        }

        int32_t piece_len = llama_token_to_piece(
            vocab, token, piece_buffer.data(), piece_buffer.size(), 0, true);
        if (piece_len < 0) {
            const size_t needed = static_cast<size_t>(-static_cast<int64_t>(piece_len));
            if (needed > MAX_TOKEN_PIECE_BYTES) {
                error = "the model vocabulary contains an implausibly large token";
                reason = StopReason::ERROR;
                break;
            }
            piece_buffer.resize(needed);
            piece_len = llama_token_to_piece(
                vocab, token, piece_buffer.data(), piece_buffer.size(), 0, true);
        }
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

        const std::string piece(piece_buffer.data(), piece_len);
        raw_reply += piece;
        if (budgeted) {
            if (in_thinking) {
                ++thinking_tokens;
                if (ends_with(raw_reply, thinking_end_tag_)) {
                    in_thinking = false;
                }
            } else if (!thinking_start_tag_.empty() && ends_with(raw_reply, thinking_start_tag_)) {
                in_thinking = true;
                thinking_tokens = 0;
            }
        }

        pending += piece;
        size_t complete = complete_utf8_prefix(pending);
        // A byte that can never begin a character would otherwise sit at the front of
        // `pending` for the rest of the reply, with everything behind it unemitted.
        while (complete == 0 && utf8_malformed_at_front(pending)) {
            pending.replace(0, 1, "\xEF\xBF\xBD");
            complete = complete_utf8_prefix(pending);
        }
        if (complete > 0) {
            const std::string emit = pending.substr(0, complete);
            pending.erase(0, complete);
            safe_reply += emit;
            if (!on_token(emit.c_str())) {
                reason = StopReason::CANCELLED;
                break;
            }
        }

        if (already_committed) {
            continue;
        }

        // The draft: what the context says usually follows `token`, if it says anything.
        // Bounded by the window, because every drafted token takes a cell to verify.
        draft.clear();
        if (speculating) {
            draft = common_ngram_simple_draft(spec_config, cached_, token);
            const size_t room = static_cast<size_t>(std::max<int32_t>(0, n_ctx - n_past_ - 1));
            if (draft.size() > std::min<size_t>(room, SPEC_DRAFT_MAX)) {
                draft.resize(std::min<size_t>(room, SPEC_DRAFT_MAX));
            }
        }

        llama_token committed = token;
        llama_batch batch;
        if (draft.empty()) {
            batch = llama_batch_get_one(&committed, 1);
        } else {
            spec_batch.n_tokens = static_cast<int32_t>(1 + draft.size());
            for (int32_t i = 0; i < spec_batch.n_tokens; ++i) {
                spec_batch.token[i]     = i == 0 ? committed : draft[i - 1];
                spec_batch.pos[i]       = static_cast<llama_pos>(n_past_ + i);
                spec_batch.n_seq_id[i]  = 1;
                spec_batch.seq_id[i][0] = 0;
                // Every position's logits, because every drafted token is judged by the
                // distribution the model computed after the one before it.
                spec_batch.logits[i]    = 1;
            }
            batch = spec_batch;
        }
        const int ret = llama_decode(ctx_, batch);
        if (ret != 0) {
            // See ingest_prompt: an aborted graph is Stop landing mid-batch, and the
            // one-token decode this loop just lost is exactly the reply.flush() case -
            // nothing the user was shown is missing. The abort may have left this
            // position's cells half-written, so they are dropped before the bookkeeping
            // above them is trusted again; a memory that cannot drop them (recurrent
            // state) forfeits the cache instead, which reset() records honestly.
            if (cancelled_.load(std::memory_order_relaxed)) {
                if (!llama_memory_seq_rm(
                        llama_get_memory(ctx_), 0, static_cast<llama_pos>(n_past_), -1)) {
                    reset();
                }
                reason = StopReason::CANCELLED;
                break;
            }
            error = "failed to decode (llama_decode returned " + std::to_string(ret) + ")";
            reason = StopReason::ERROR;
            break;
        }
        if (draft.empty()) {
            ++n_past_;
            // Recorded only once the decode succeeded, so the cache stays an accurate
            // record of what the KV cache actually holds.
            if (cached_covers_context_) {
                cached_.push_back(token);
            }
            continue;
        }

        // Verification: sample after every batch position in turn and accept a drafted
        // token while the model would have chosen it too. The first disagreement is the
        // model's own choice for that position, and everything drafted past it is dropped
        // from the cache, which the transformer memory does exactly.
        int32_t accepted = 0;
        while (accepted < static_cast<int32_t>(draft.size())) {
            const llama_token verdict = llama_sampler_sample(sampler, ctx_, accepted);
            if (verdict != draft[accepted]) {
                has_pending_token = true;
                pending_token = verdict;
                break;
            }
            chosen.push_back(verdict);
            ++accepted;
        }
        if (accepted == static_cast<int32_t>(draft.size())) {
            has_pending_token = true;
            pending_token = llama_sampler_sample(sampler, ctx_, accepted);
        }
        if (accepted < static_cast<int32_t>(draft.size())) {
            llama_memory_seq_rm(
                llama_get_memory(ctx_), 0, static_cast<llama_pos>(n_past_ + 1 + accepted), -1);
        }
        n_past_ += 1 + accepted;
        cached_.push_back(token);
        cached_.insert(cached_.end(), draft.begin(), draft.begin() + accepted);
        stats.draft_tokens    += static_cast<int32_t>(draft.size());
        stats.accepted_tokens += accepted;
    }

    // Accepted tokens the loop never emitted are in the cache and not in the reply, and
    // the record has to say what the cache holds: they go.
    if (!chosen.empty()) {
        const int32_t unread = static_cast<int32_t>(chosen.size());
        n_past_ -= unread;
        cached_.resize(cached_.size() - static_cast<size_t>(unread));
        if (!llama_memory_seq_rm(llama_get_memory(ctx_), 0, static_cast<llama_pos>(n_past_), -1)) {
            reset();
        }
    }
    if (speculating) {
        llama_batch_free(spec_batch);
        if (stats.draft_tokens > 0) {
            LOGI("spec: drafted=%d accepted=%d generated=%d",
                 stats.draft_tokens, stats.accepted_tokens, stats.generated_tokens);
        }
    }

    llama_sampler_free(sampler);

    // Parse with the format the prompt was rendered in. This is where tool calls come
    // from, and where reasoning is separated for models whose thinking is not <think>.
    try {
        common_chat_parser_params parser_params;
        parser_params.format = static_cast<common_chat_format>(last_format_);
        parser_params.generation_prompt = last_generation_prompt_;
        parser_params.parse_tool_calls = true;
        parser_params.reasoning_format = COMMON_REASONING_FORMAT_AUTO;

        // Anything other than a clean end of turn was cut off mid-sentence, and a cut-off
        // tool call read as a complete one produces a call the model never finished asking
        // for. The parser handles that itself when told the input is partial.
        const bool truncated = reason != StopReason::END_OF_TURN;
        const common_chat_msg parsed =
            common_chat_parse(safe_reply, truncated, parser_params);
        for (const auto & call : parsed.tool_calls) {
            reply.tool_calls.push_back({call.id, call.name, call.arguments});
        }
        // Only trust the cleaned text when the parser actually recognised something.
        // Otherwise it returns the input unchanged plus the generation prompt, which is
        // worse than the raw text the Kotlin parser can still handle.
        // Tool calls are only acted on from a reply that finished. A truncated one may
        // have been about to add arguments.
        if (!reply.tool_calls.empty() && reason == StopReason::END_OF_TURN) {
            reply.content = parsed.content;
            reply.reasoning = parsed.reasoning_content;
            return_parsed_content = true;
        }
    } catch (const std::exception &) {
        // A parser failure must not lose tool calls silently; the Kotlin fallback covers
        // formats llama.cpp does not know, so there is nothing to do here.
    }

    if (!return_parsed_content) {
        // The text crosses the boundary and Kotlin splits it, where the logic is
        // unit-tested against output captured from real models. safe_reply, not raw_reply:
        // the caller was never shown the withheld tail and the stored reply must match.
        reply.content = safe_reply;
    }

    const int64_t decode_end = now_ms();
    stats.decode_ms = first_token_ms > 0 ? decode_end - first_token_ms : 0;
    stats.context_used = n_past_;
    stats.thinking_prefilled = thinking_prefilled_;
    return reason;
}

std::string Session::offload_summary() const {
    std::lock_guard<std::mutex> guard(g_buffers_mutex);
    return offload_summary_locked();
}

std::string Session::model_description() const {
    char buffer[256];
    const int32_t len = llama_model_desc(model_, buffer, sizeof(buffer));
    return len > 0 ? std::string(buffer, len) : std::string();
}

uint64_t Session::parameter_count() const { return llama_model_n_params(model_); }
uint64_t Session::model_size_bytes() const { return llama_model_size(model_); }
int32_t Session::context_size() const { return static_cast<int32_t>(llama_n_ctx(ctx_)); }
int32_t Session::training_context_size() const { return llama_model_n_ctx_train(model_); }
int32_t Session::layer_count() const { return llama_model_n_layer(model_); }

}  // namespace openweights
