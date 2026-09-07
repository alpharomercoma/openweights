# Roadmap

OpenWeights is a local-first agent runtime that happens to look like a chat app. The
product goal has not moved: **run open-weight models from Hugging Face on your own phone,
on as many devices as possible, with nothing leaving the device.** What follows is how the
rest of it gets built, and why each piece exists.

Status legend: **done** · *in progress* · planned.

> **Read with the date in mind.** The last full rewrite was 2026-08-11 and the notes below
> describe a one-engine, GGUF-only product. Since then a second runtime shipped and grew:
> ExecuTorch runs compiled `.pte` files for eight families, reads pictures for two of them,
> and the app now ships its own 32k exports and opens a compiled model at the window it was
> exported with. That work is recorded in [ARCHITECTURE.md](ARCHITECTURE.md) and the
> research notes it links, starting with [research/executorch.md](research/executorch.md)
> and [research/executorch-window-matrix.md](research/executorch-window-matrix.md). The
> app is live on Google Play (2026-09). Sections 1, 2 and 9 below are corrected in place;
> the rest still holds.

Rewritten 2026-08-11. Six of these sections still said "planned" for work that had already
shipped, which is the sort of drift that makes a roadmap worse than no roadmap: a
contributor reading it would have built a model browser that already exists. Each heading
below was checked against the code before it was changed, and the gaps that remain are
named as gaps rather than left implied.

---

## 1. Inference core: **done**

llama.cpp behind an `InferenceEngine` interface, seven Android CPU backends selected at
runtime, split thread counts for prefill and decode, KV-cache prefix reuse across turns.
Measured 76.9 tok/s prefill / 16.2 tok/s decode on a MediaTek MT6991 in August 2026; by
September the same phone read about 100 tok/s prefill on llama.cpp and 360 tok/s on the
ExecuTorch export of the same model (`docs/research/first-turn-latency.md`,
`docs/research/executorch-own-exports.md`). See `docs/research/inference-engines.md` and
`docs/CONTEXT.md`.

## 2. Compute backend choice: **done**, and the answer was the CPU

This was going to be a picker with a benchmark behind it. What shipped is the CPU, because
that is what the measurements kept saying, and Settings explains rather than offers.

- **CPU**, always available, and the fastest path on every phone tested. KleidiAI matters
  more than the backend does: the same model at Q4_0 runs at 13.8 tokens a second where
  Q4_K_M runs at 7.7, because Q4_K_M carries q6_K tensors KleidiAI has no kernel for.
- **GPU (OpenCL)**. Built and registered. On the Immortalis G925 in the dev phone it logs
  `unsupported GPU` and drops the device, so the backend is present and unusable, which is
  a more honest outcome than not shipping it.
- **GPU (Vulkan)**. Not built. Frequently slower than a tuned CPU path on Mali, and adding
  a second GPU backend to be slower twice was not worth the binary.
- **NPU**. Not built, on measurement rather than principle: against a KleidiAI-repacked
  CPU the MediaTek MDLA was level at decode and worth 1.3x to 2.1x at prefill, and real
  multi-turn conversations prefill a median of 50 tokens after cache reuse
  (`research/mediatek-npu.md`, `research/npu-prefill-multiturn.md`).
- **A second engine did ship**, and it is the CPU one: ExecuTorch with XNNPACK, for
  compiled `.pte` files. It is not a curated catalogue; Discover searches the Hub for
  compiled repositories the same way it does for GGUFs (`research/executorch.md`).

So there is no backend picker and no one-tap benchmark. Settings lists what the device
actually reports, including the CPU feature flags, and says in a sentence why there is
nothing to choose between.

## 3. Getting models: **done**

Nothing else matters if you cannot get a model in. Hugging Face search filtered to GGUF,
with the app reading each file's GGUF header over HTTP range requests *before* downloading
so it can tell you: how much RAM this needs at your chosen context length, roughly how fast
it will run, and whether it will run at all. Then a resumable, checksum-verified download
that runs in a `dataSync` foreground service, so leaving the app no longer ends it (the
gap this heading used to name).

The token is stored encrypted with a hardware-backed Android Keystore key, sent only to
`huggingface.co`, and never logged.

## 4. Agent runtime: **done**

This is the part that turns a chat app into something that does work. Four layers, and
they are different concerns:

**Prompt engineering**: how a single message is worded. Per-model system prompts and
saved presets, because a 2 B local model needs much more explicit instruction than a
frontier model does.

**Context engineering**: what the model sees on a given call. On-device this is the
binding constraint: a phone-sized context window fills fast, and `n_ctx` costs RAM. Three
mechanisms:

- **Compaction.** When the context approaches full, summarize the older turns with the
  model itself and continue, instead of the conversation dying. This is what Claude Code
  and Codex do, and the research is consistent about the design: compact *before* the wall,
  keep the most recent turns verbatim, and keep pinned artifacts (the system prompt, the
  current task, file references) out of the summary entirely. The user sees a marker in
  the transcript, and the full pre-compaction history stays on disk. Nothing is lost, only
  moved out of the window.
- **Deletion over rewriting where possible.** Summarization is lossy and, on a phone, slow.
  Tool output and duplicated file contents are dropped rather than summarized.
- **Just-in-time retrieval.** `@`-referenced files are re-read when needed rather than
  pasted once and carried forever.

**Loop engineering**. The autonomous cycle. A ReAct loop (reason → act via tool → observe
→ repeat) with explicit stopping conditions, step budgets, and a visible plan the user can
interrupt. On-device this needs harder limits than a server agent: battery and thermal
budget are real, so loops are bounded and pause when the device throttles.

**Harness engineering**. The code that runs all of it reliably: tool dispatch, permission
prompts before anything irreversible, structured errors fed back to the model, and
observability the user can actually see (which tool ran, with what, and what came back).

**Sub-agents**. A fan-out for work that does not fit one context: each sub-agent gets its
own window and returns only its conclusion. On a phone this is sequential rather than
parallel, since there is one model resident in memory at a time. The win is context
isolation, not concurrency.

## 5. Tool calling: **done**

The foundation for everything in section 4. llama.cpp's chat templates already accept tool
definitions, and models trained for tool use emit calls in their own format. Work:
plumb tool schemas into template rendering, parse calls out of the stream, model
`ToolCall` as a message part, render calls and their results in the transcript as
inspectable steps, and gate execution behind permission. What a tool gave back is carried
as a `ChatMessage` under `ChatRole.TOOL`, which is what the templates render; the separate
`ToolResult` shape this section originally called for was written, never constructed, and
has been removed. Built-in tools come first (device clock, math,
file read within app storage); user-defined and MCP-style tools later.

## 6. Composer affordances: **done**

Two input conventions people already know from developer tools:

- **`/`** opens a command palette: new chat, switch model, compact now, set parameters,
  run benchmark. Commands are discoverable by typing rather than buried in menus.
- **`@`** references a file or folder the app can read, inserting a reference rather than
  the contents, so context engineering can fetch it just in time.

Both are pure UI over existing capabilities, which is why they come after tool calling
rather than before it.

## 7. History, usage, and the dashboard: **done**

Conversations in Room, with per-message stats. Lifetime usage lives in a separate
append-only day-bucketed ledger so deleting a chat never falsifies your totals: tokens
generated, tokens per day, per-model share, average throughput trend, total inference time.

## 8. Multimodal: **done**, dictation included

llama.cpp's `libmtmd` keeps every input modality inside the one engine: a model paired with
its `mmproj` projector reads images and audio, and video is sampled into frames on the
Android side because libmtmd's own video path needs an `ffmpeg` binary no app can ship.
Attachments reach the composer through a button beside the message field, and the button
only appears when the loaded model can actually read something.

Output is text plus `TextToSpeech` read-aloud. The line that used to sit here, that llama.cpp
does not implement the audio decoders, has stopped being true: the vendored tree carries
`tools/tts` and generative pipelines for Qwen3-TTS and Pocket-TTS in `libmtmd`, so speech out
is now an app-side gap rather than an engine one. What is missing is the playback path and
the JNI for `mtmd_helper_gen_audio`, not the decoder.

What that path will not inherit is the settings sheet. Its input struct has three sampling
fields, `top_k`, `top_p` and `seed`, and no temperature, no penalties, no token cap and no
chat template, so `OutputModality` already reports what a loaded projector emits and the
sheet already draws only what applies; CONTEXT.md has the table. Image generation is still a
second engine and still out of scope: llama.cpp generates no pictures at any quantization.
`docs/research/multimodality.md` has the rest of the reasoning and the numbers.

Dictation uses Android's on-device recogniser only, so the "nothing leaves this device"
promise holds for the microphone too. Audio input is proven with LFM2.5-Audio-1.5B.

## 9. Play Store production: **done, and live**

Target API 36, 16 KB alignment (satisfied by NDK r29), the foreground service declaration for
downloads, R8 with a build step that fails if it renamed a name JNI resolves, and a signed
AAB. All verified against the artifact rather than the intent; see
[play-store.md](play-store.md).

The data safety form is **not** "no data collected", which this document said for a long time
and which would have been a false declaration. Play counts data as collected the moment it
leaves the device, and this app searches Hugging Face and lets the assistant search the web
and fetch pages on the user's behalf. Every row, with the reasoning behind it, is in
[store-listing.md](store-listing.md), alongside the listing copy and the generative AI
declaration. The policy those link to is [privacy-policy.md](privacy-policy.md).

The upload key exists, the graphics are made and checked against the spec in `play/graphics`,
and the policy is published at <https://alpharomercoma.github.io/openweights/privacy.html>.
The content rating answers are written out question by question in
[store-listing.md](store-listing.md#content-rating-questionnaire).

The app is published at
<https://play.google.com/store/apps/details?id=io.github.alpharomercoma.openweights>. The
questionnaire, the foreground service video and the generative AI declaration were filed;
what remains per release is the checklist in [play-store.md](play-store.md), and the bundle
is built and uploaded by hand.

---

## Device support

The app must run on as many arm64 Android 12+ devices as possible, not just flagships.
Concretely that means: no SoC name ever gates a feature; the CPU backend is chosen by
runtime capability score with a plain armv8.0 fallback; context length and offload are
clamped to what the device can actually hold; and the fit estimator tells the truth about
models that will not run rather than letting the OS kill the app.
