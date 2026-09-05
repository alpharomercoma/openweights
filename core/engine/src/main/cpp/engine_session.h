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
#include <deque>
#include <unordered_map>
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

/** True when the first byte of [text] is ill-formed rather than merely incomplete. */
bool utf8_malformed_at_front(const std::string & text);

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
/**
 * The grammar the chat template asked for, so the sampler can be constrained by it.
 *
 * Rendering tools produces both a prompt and a grammar describing what a call to one
 * looks like. Taking only the prompt, which is what this did first, leaves the model free
 * to write "I could use the search tool, shall I?" instead of a call, which is exactly
 * what it did.
 */
struct GrammarSpec {
    std::string grammar;
    /** When true the grammar only binds after a trigger appears, leaving prose free. */
    bool lazy = false;
    std::vector<std::string> trigger_patterns;
    std::vector<llama_token> trigger_tokens;
};

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
    float temperature   = 0.8f;
    int32_t top_k         = 40;
    float top_p         = 0.95f;
    float min_p         = 0.05f;
    float repeat_penalty = 1.1f;
    int32_t repeat_last_n = 64;
    uint32_t seed          = LLAMA_DEFAULT_SEED;
    int32_t max_tokens    = 0;  // 0 = until EOG or context is full
    /**
     * Thinking tokens before the block is closed for the model: -1 for no cap. At the
     * cap the template's own end-of-thinking tag is written into the reply and decoded,
     * and the model answers from what it has. Nothing happens on a model or a turn that
     * never opened a block.
     */
    int32_t reasoning_budget = -1;
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
    /** Tokens proposed by the n-gram drafter, and how many of them the model agreed with. */
    int32_t draft_tokens      = 0;
    int32_t accepted_tokens   = 0;
    int64_t prefill_ms        = 0;
    int64_t decode_ms         = 0;
    int64_t time_to_first_token_ms = 0;
    int32_t context_used      = 0;
    int32_t context_size      = 0;
    /**
     * Tokens this turn's prompt reused from the KV cache rather than re-decoding.
     *
     * Zero on a media turn: embeddings are never compared against the cache, so an
     * attachment always re-evaluates the whole conversation. `prompt_tokens` above is
     * already just the freshly-decoded remainder, so `cached_tokens + prompt_tokens` is the
     * conversation's full length as tokenized this turn.
     */
    int32_t cached_tokens      = 0;
    /**
     * Whether the template opened a thinking block that the reply continues from.
     *
     * The caller needs this to store a reply that will match the cache next turn. LFM2.5's
     * template ends an assistant opener with `<think>`, so the tag is in the prompt and
     * never in the stream, and the stored reply has to have it put back. Guessing from the
     * text works only when a closing tag arrived: a reply cut off mid-thought has neither
     * tag, is indistinguishable from a reply that never thought, and silently costs a full
     * re-prefill on every turn after it.
     */
    bool thinking_prefilled   = false;
};

/** What warming the prompt prefix did, measured the same way generation is. */
struct WarmStats {
    /** Tokens freshly decoded into the cache by this warm. */
    int32_t prompt_tokens  = 0;
    /** Tokens the cache already held, byte-for-byte, so nothing was done for them. */
    int32_t reused_tokens  = 0;
    int64_t prefill_ms     = 0;
    /** Size of the prefix snapshot kept for models that refuse rollback, or 0. */
    int64_t snapshot_bytes = 0;
};

/**
 * A loaded model plus its context and KV cache.
 *
 * One Session is one conversation: it remembers the tokens already in the KV cache so a
 * follow-up turn only has to decode the new suffix. Sessions are not thread-safe except
 * for `cancel()`, which is callable from another thread.
 */
class Session {
public:
    /** Emitted for each decoded token; return false to stop generation early. */
    using TokenCallback = std::function<bool(const char * piece)>;

    ~Session();

    /**
     * What kinds of media the loaded projector can accept.
     *
     * Video is absent: libmtmd's video path extracts frames by shelling out
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
        bool op_offload,
        bool kv_quantized,
        bool speculate,
        /**
         * Tokens per image, or 0 to leave the projector's metadata in charge.
         *
         * Set as both the minimum and the maximum, so a picture costs what was asked for
         * rather than somewhere between the model's own two numbers. A maximum on its own
         * below the model's floor makes clip throw while reading the projector, which
         * would turn a slider into a model that will not load.
         */
        int32_t image_tokens,
        /** Logical and physical prompt batch. See ModelLoadParams.batchTokens. */
        int32_t n_batch,
        int32_t n_ubatch,
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

    /**
     * Reads the conversation's stable prefix into the KV cache before anybody asks anything.
     *
     * The first turn of every conversation begins with the same two thousand tokens of
     * system message and tool definitions, and at phone prefill speeds that is most of the
     * first answer's wait. Warming decodes that prefix while the app is idle — right after
     * the model loads — so the first question pays for itself and nothing else.
     *
     * `messages` is the fresh conversation's head (normally just the system message) and is
     * rendered *without* the generation prompt, so the rendered text is a byte prefix of
     * what the first real turn will render. Cancellable like generation; a cancelled warm
     * keeps every full batch it managed on models whose memory can be cut, and concedes the
     * cache on those whose memory cannot.
     *
     * On models that refuse partial rollback (recurrent and hybrid families), the warmed
     * prefix is also snapshotted, because those models otherwise pay a full re-read on
     * every new conversation: the old conversation's cache cannot be rewound to the shared
     * prefix, but it can be *replaced* by the snapshot. Transformers need no snapshot; for
     * them rollback already keeps the prefix across conversations.
     *
     * `snapshot` gates that capture. True for the fresh-chat head; false when warming a
     * whole conversation — a fold, a branch, a reopened chat — which must ride the cache
     * itself rather than displace the head snapshot every future new chat restores from.
     */
    bool warm(
        const std::vector<ChatMessage> & messages,
        const std::vector<ToolDefinition> & tools,
        const ReasoningConfig & reasoning,
        bool snapshot,
        const char * store,
        WarmStats & stats,
        std::string & error);

    /** True when the loaded chat template understands being told whether to think. */
    bool supports_thinking() const;

    /** True when this model's chat template renders tool definitions. */
    bool supports_tools() const;

    /**
     * True when this model's chat template will also render the *answer* a tool gave.
     *
     * A separate question from [supports_tools], which is what made this worth asking.
     * Gemma's templates describe tools perfectly well and then require the roles to
     * alternate strictly user/assistant, so the tool message that carries the result raises
     * instead of rendering. FunctionGemma is tuned for calling and answers yes to
     * [supports_tools]; it still cannot be told what came back.
     */
    bool supports_tool_results() const;

    /** True when this model's chat template does something with `reasoning_effort`. */
    bool supports_reasoning_effort() const;

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

    MediaSupport media_support() const;

    /**
     * True when the projector carries a generative audio decoder, so the model speaks.
     *
     * Separate from `MediaSupport::audio`, which is the opposite direction: that one says a
     * wav can be sent in. A speech model needs both and they are not the same encoder, and
     * conflating them would have the app offer a microphone on a model that only talks.
     */
    bool generates_speech() const;

    /** The marker the projector expects where a file belongs in the prompt. */
    std::string media_marker() const;

    std::string model_description() const;

    /**
     * Which backend actually holds the weights, and the breakdown behind it.
     *
     * `"OpenCL|OpenCL:680|CPU:96"`: the dominant backend first, then every buffer with its
     * megabytes. Empty when nothing was captured, which means the model was not loaded
     * through this process.
     *
     * This is llama.cpp's own accounting of where the tensors went, taken from the line it
     * prints per buffer. It is not the number of layers requested: that figure is computed
     * from `n_gpu_layers` whatever happened, so a GPU that failed to attach reports the
     * same as one that worked, and the app spent a long time believing it.
     */
    std::string offload_summary() const;
    uint64_t parameter_count() const;
    uint64_t model_size_bytes() const;
    int32_t context_size() const;
    int32_t training_context_size() const;
    int32_t layer_count() const;
    /** Number of tokens currently held in the KV cache. */
    /**
     * KV-cache positions in use.
     *
     * [n_past_] rather than the token record: a turn with an attachment fills positions
     * with embeddings that no token describes, so the token count would read as empty
     * while the cache is nearly full.
     */
    int32_t context_used() const { return n_past_; }

private:
    Session() = default;

    /** Renders the conversation and any tools into this model's own prompt format. */
    bool render_prompt(
        const std::vector<ChatMessage> & messages,
        const std::vector<ToolDefinition> & tools,
        const ReasoningConfig & reasoning,
        std::string & out,
        std::string & error,
        bool add_generation_prompt = true);

    /** Decodes `tokens[from..]`, reusing whatever prefix is already cached. */
    bool ingest_prompt(const std::vector<llama_token> & tokens, size_t from, std::string & error);

    /**
     * One attachment's place in [cached_], and what was there.
     *
     * A media chunk occupies `n_tokens` positions that hold embeddings rather than tokens.
     * In the record those positions are LLAMA_TOKEN_NULL, and this says which picture (or
     * clip) filled them, by libmtmd's own hash of its pixels. Two prompts agree at a span
     * when they have the same id and the same length at the same place: then the
     * embeddings already in the cache are the ones this prompt would have produced, and
     * the encode that made them, which is most of an image turn, is not paid again.
     */
    struct MediaSpan {
        size_t start = 0;
        size_t n_tokens = 0;
        std::string id;
    };

    /**
     * Brings the cache into agreement with the head of `tokens`, as cheaply as it can.
     *
     * Reuses the longest matching prefix; rolls the rest back where the memory allows it;
     * where it refuses, restores the warm snapshot if `tokens` extends the snapshotted
     * prefix; and starts cold otherwise. Returns the position ingestion should continue
     * from. `need_logits` keeps one token back for generation, which a warm does not need.
     */
    size_t align_cache(
        const std::vector<llama_token> & tokens,
        bool need_logits,
        const std::vector<MediaSpan> * spans = nullptr);

    /**
     * How far `tokens` agrees with [cached_], counting a media span as one unit.
     *
     * A span matches only whole, id and length alike; anything else stops the prefix at
     * the span's start, never inside it, so a reuse boundary always lands on text.
     */
    size_t common_prefix(
        const std::vector<llama_token> & tokens,
        const std::vector<MediaSpan> * spans) const;

    /**
     * Decodes `tokens[from..]` a batch at a time, committing progress after each batch.
     *
     * The warm path's ingest: a cancelled warm is not a failure, so every batch that
     * finished stays counted in [cached_] and the next turn reuses it. On a model whose
     * memory cannot drop the half-written batch, cancellation concedes the whole cache.
     */
    bool ingest_warm(const std::vector<llama_token> & tokens, size_t from, std::string & error);

    /**
     * Restores a warmed state from [path], if its tokens are exactly [tokens].
     *
     * The file outlives the process, which the state in RAM cannot be made to do on a
     * phone: the fresh-chat read costs tens of seconds once, and every later startup of
     * the same model, same settings and same day arms itself from disk in the time a
     * 26 MB read takes. A stale or foreign file — new day, changed settings, replaced
     * weights, another llama.cpp build — fails the build stamp in its header, the token
     * compare or llama's own state validation, is deleted, and the caller computes as if
     * it never existed.
     */
    bool restore_warm_file(const char * path, const std::vector<llama_token> & tokens);

    /** Writes the current cache — exactly [tokens] — and its state to [path], atomically. */
    void save_warm_file(const char * path, const std::vector<llama_token> & tokens);

    /**
     * Reads and verifies the warm file at [path] against [tokens], filling [state]. A
     * malformed or mismatched file is deleted so it never fails the same way twice.
     */
    bool read_warm_file(
        const char * path,
        const std::vector<llama_token> & tokens,
        std::vector<uint8_t> & state);

    /**
     * Arms the in-RAM snapshot from the warm file without touching the live cache. This
     * is the repair for a hybrid whose head warm was interrupted by a turn: the cache
     * holds a conversation from then on, the exact-head moment the capture needs never
     * returns, and this puts the restore machinery back without costing the open chat
     * its cache. The blob is validated by llama at restore time, where a refusal
     * already falls back to a cold read.
     */
    bool arm_warm_file(const char * path, const std::vector<llama_token> & tokens);

    /**
     * Decides, once per load, how a reply's thinking survives being replayed as history.
     *
     * Two shapes exist in the wild and they are mutually exclusive. Templates the lfm2
     * handler serves, and Qwen3's family, re-emit thinking from `reasoning_content` — for
     * them the block is split out of the content. LFM2.5-Thinking's template reads the
     * block *from the content itself* and only keeps it for past turns when its
     * `keep_past_thinking` kwarg is set — for it the content must go back verbatim.
     * Guessing wrong either way costs the whole conversation's cache every turn on a
     * hybrid, so it is probed: a known assistant turn is rendered both ways through the
     * production path, and whichever reproduces it byte-for-byte wins.
     */
    void probe_thinking_history();

    /** Snapshots the cache as the warm prefix, on the families that cannot roll back. */
    void maybe_snapshot();

    /**
     * Whether this model's memory can be rolled back to any earlier position.
     *
     * False for recurrent and hybrid families, whose state has no rows to cut, and for
     * sliding-window ones, whose local-attention cells are recycled past the window. Both
     * want the warm-prefix snapshot, and both re-read a prefix a transformer would rewind to.
     */
    bool keeps_no_full_history() const;

    /**
     * Whether the sliding-window cache still holds the n_swa positions before [position],
     * which the next token there will attend to. Always true without a sliding window.
     */
    bool swa_window_reaches(size_t position) const;

    /**
     * Encodes a prompt containing media and evaluates it into the KV cache.
     *
     * Returns the new position, or -1 on failure.
     */
    int32_t ingest_media_prompt(
        const std::string & prompt,
        const std::vector<std::string> & media_paths,
        size_t & reused,
        std::string & error);


    llama_model   * model_ = nullptr;
    llama_context * ctx_   = nullptr;

    /**
     * The worker threads, kept alive between graphs instead of made for each one.
     *
     * ggml creates a *disposable* threadpool inside every `ggml_graph_compute` when the
     * context has none attached, and frees it on the way out. Prefill runs one graph per
     * 512-token batch, so it pays that once for hundreds of tokens. Decode runs one graph
     * per token, so it pays it for every single one: measured on the phone, the process
     * thread count sat at a flat 61 while idle and oscillated between 62 and 70
     * throughout a reply.
     *
     * The cost is not the `pthread_create` itself. It is that a thread born a moment ago
     * has no scheduler history — its utilization estimate starts at zero, so EAS places
     * it on a little core and neither migrates it nor raises the frequency until it has
     * run long enough to be noticed, which a thread that lives for one token never does.
     * That is why decode collapses further than prefill under load, and why the big cores
     * were measured at 1.9-2.1 GHz against the 3.3 and 3.73 GHz they are capable of.
     *
     * One pool, sized once to the wider of the two thread counts and never rebuilt: the
     * CPU backend keeps its own pointer to whatever it was last handed, so freeing a pool
     * while the context is alive is a use-after-free. See `attach_threadpools`.
     */
    ggml_threadpool_t threadpool_ = nullptr;

    /** How wide it was built, which is the widest either half will ever ask for. */
    int32_t pool_threads_ = 0;

    /** Builds it once, wide enough for both halves, and hands it to the context. */
    void attach_threadpools(int32_t n_threads, int32_t n_threads_batch);

    /** Frees it. Only safe once the context that was given it is gone. */
    void free_threadpools();

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
    /** Set when the rendered prompt ends with the template's own thinking open tag. */
    bool thinking_prefilled_ = false;
    /** The template's thinking tags, kept from the last render for the reasoning budget. */
    std::string thinking_start_tag_;
    std::string thinking_end_tag_;

    GrammarSpec last_grammar_;

    /** The multimodal projector, or null for a text-only model. */
    void * mtmd_ = nullptr;

    /**
     * Tokens currently represented in the KV cache, in order.
     *
     * A position filled by an attachment holds LLAMA_TOKEN_NULL here, and [media_spans_]
     * says which attachment; see [MediaSpan]. A real token never equals the placeholder,
     * so every text-only comparison of this record still means what it did.
     */
    std::vector<llama_token> cached_;

    /** The attachments in [cached_], in position order. Empty for a text-only cache. */
    std::vector<MediaSpan> media_spans_;

    /**
     * What the projector produced for each media chunk this session has encoded, by key.
     *
     * The encode is most of an image turn on a phone: one 512-pixel view is fifteen or so
     * seconds of vision transformer and a tiled picture is ten of them. The record above
     * saves that when the cache can be extended. It cannot always be: a hybrid model
     * refuses to roll back, and the model's own reply sometimes re-tokenizes a token
     * differently from how it was decoded, which forces the whole conversation to be read
     * again. Reading text again is seconds; encoding the picture again is minutes. So the
     * embeddings are kept, keyed by the picture's hash and the chunk's place in it, and a
     * re-read decodes them straight into the cache.
     *
     * Bounded in bytes, oldest first out. A 2560-wide model at 256 tokens a chunk is 2.6 MB
     * per chunk, so the budget holds a few pictures' worth of tiles, which is a conversation.
     */
    std::unordered_map<std::string, std::vector<float>> media_embd_;
    std::deque<std::string> media_embd_order_;
    size_t media_embd_bytes_ = 0;

    /** Encodes (or recalls) one media chunk and decodes it into the cache at `n_past`. */
    int32_t decode_media_chunk(
        void * mtmd_ctx,
        const void * chunk,
        const std::string & key,
        llama_pos * n_past);

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

    /**
     * Draft-free speculation: propose the next few tokens from n-grams already in the
     * context and let the model verify them in one batch.
     *
     * Costs nothing where the context has no match, because then there is no draft and
     * the step is the single decode it always was. Where the model is copying (a page
     * being rebuilt, a quote, a file being edited back) a draft of four is verified for
     * the price of about 1.7 single steps, so it pays above 0.68 accepted and yields up
     * to 3x at full acceptance, per the batch costs measured on the MT6991. Only on
     * models whose cache can drop a rejected tail: a hybrid or recurrent model cannot,
     * and keeps the single path. Off unless the load asks for it.
     */
    bool speculate_ = false;

    /**
     * The tokens the warm snapshot covers, and the memory state that held exactly them.
     *
     * Only populated on models whose memory refuses partial rollback — recurrent and
     * hybrid families — because they are the ones with no other way to keep the shared
     * prefix across conversations. For everyone else the ordinary rollback path is free.
     * Both live for the session and go with it; a re-warm with a different prefix
     * replaces them.
     */
    /** See [probe_thinking_history]; true is the reasoning_content shape. */
    bool split_history_thinking_ = true;

    std::vector<llama_token> prefix_tokens_;
    std::vector<uint8_t> prefix_state_;

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
    int32_t type;         // mirrors ggml_backend_dev_type
    uint64_t total_memory;
};

/**
 * Every compute device available on this phone.
 *
 * Today that is the CPU backend chosen by [init_backend]; when a GPU backend is compiled
 * in it appears here too, which is how Settings learns what it may offer.
 */
std::vector<ComputeDevice> compute_devices();

}  // namespace openweights
