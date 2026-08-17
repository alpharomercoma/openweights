# Roadmap

OpenWeights is a local-first agent runtime that happens to look like a chat app. The
product goal has not moved: **run open-weight models from Hugging Face on your own phone,
on as many devices as possible, with nothing leaving the device.** What follows is how the
rest of it gets built, and why each piece exists.

Status legend: **done** · *in progress* · planned.

Rewritten 2026-08-11. Six of these sections still said "planned" for work that had already
shipped, which is the sort of drift that makes a roadmap worse than no roadmap: a
contributor reading it would have built a model browser that already exists. Each heading
below was checked against the code before it was changed, and the gaps that remain are
named as gaps rather than left implied.

---

## 1. Inference core: **done**

llama.cpp behind an `InferenceEngine` interface, seven Android CPU backends selected at
runtime, split thread counts for prefill and decode, KV-cache prefix reuse across turns.
Measured 76.9 tok/s prefill / 16.2 tok/s decode on a MediaTek MT6991. See
`docs/research/inference-engines.md` and `docs/CONTEXT.md`.

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
- **NPU**. No path through llama.cpp. Reaching MediaTek APU or Qualcomm Hexagon means a
  second engine and per-SoC pre-exported models, which is the curated-catalog trade-off
  this project exists to avoid.

So there is no backend picker and no one-tap benchmark. Settings lists what the device
actually reports, including the CPU feature flags, and says in a sentence why there is
nothing to choose between.

## 3. Getting models: **done**, except that a download dies with the app

Nothing else matters if you cannot get a model in. Hugging Face search filtered to GGUF,
with the app reading each file's GGUF header over HTTP range requests *before* downloading
so it can tell you: how much RAM this needs at your chosen context length, roughly how fast
it will run, and whether it will run at all. Then a resumable, checksum-verified download.

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

Output is text plus `TextToSpeech` read-aloud. Speech-generating open models exist but are
30B-class and llama.cpp does not implement their audio decoders; image generation would be a
second engine. `docs/research/multimodality.md` has the full reasoning and the numbers.

Dictation uses Android's on-device recogniser only, so the "nothing leaves this device"
promise holds for the microphone too. Audio input is proven with LFM2.5-Audio-1.5B.

## 9. Play Store production: *code done, paperwork drafted*

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

What is left genuinely needs a person in front of the Console: pasting those answers into the
questionnaire, recording the foreground service video, and filing the generative AI
declaration with its two open questions.

---

## Device support

The app must run on as many arm64 Android 12+ devices as possible, not just flagships.
Concretely that means: no SoC name ever gates a feature; the CPU backend is chosen by
runtime capability score with a plain armv8.0 fallback; context length and offload are
clamped to what the device can actually hold; and the fit estimator tells the truth about
models that will not run rather than letting the OS kill the app.
