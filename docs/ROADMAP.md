# Roadmap

OpenWeights is a local-first agent runtime that happens to look like a chat app. The
product goal has not moved: **run open-weight models from Hugging Face on your own phone,
on as many devices as possible, with nothing leaving the device.** What follows is how the
rest of it gets built, and why each piece exists.

Status legend: **done** · *in progress* · planned.

---

## 1. Inference core — **done**

llama.cpp behind an `InferenceEngine` interface, seven Android CPU backends selected at
runtime, split thread counts for prefill and decode, KV-cache prefix reuse across turns.
Measured 76.9 tok/s prefill / 16.2 tok/s decode on a MediaTek MT6991. See
`docs/research/inference-engines.md` and `docs/CONTEXT.md`.

## 2. Compute backend choice — *in progress*

Google AI Edge lets you pick CPU or GPU; people expect that control, and on mobile the
right answer genuinely varies by chip. The engine already loads backends dynamically, so
this is a matter of offering the choice honestly rather than guessing.

- **CPU** — always available, and today the fastest path on most phones.
- **GPU (Vulkan)** — one API present on essentially every modern Android device. Frequently
  *slower* than a well-tuned CPU path on Mali/Immortalis, so it is offered as a real option
  with measured numbers, never as a default that quietly makes things worse.
- **GPU (OpenCL)** — the better GPU path on Qualcomm Adreno, verified by Qualcomm on
  Snapdragon 8 Gen 3 and 8 Elite. Worth adding when the Snapdragon test device arrives.
- **NPU** — no path through llama.cpp. Reaching MediaTek APU or Qualcomm Hexagon means a
  second engine (ExecuTorch has vendor delegates) and per-SoC pre-exported models, which
  is the curated-catalog trade-off this project exists to avoid. The setting will say that
  plainly rather than showing a disabled toggle with no explanation.

Settings shows the backends this device actually reports, lets the user pick one and set
how many layers to offload, and — because guessing is the whole problem — offers a
one-tap benchmark that measures each option on the model they are running.

## 3. Getting models — planned (the biggest functional gap)

Nothing else matters if you cannot get a model in. Hugging Face search filtered to GGUF,
with the app reading each file's GGUF header over HTTP range requests *before* downloading
so it can tell you: how much RAM this needs at your chosen context length, roughly how fast
it will run, and whether it will run at all. Then a resumable, checksum-verified download.

The token is stored encrypted with a hardware-backed Android Keystore key, sent only to
`huggingface.co`, and never logged.

## 4. Agent runtime — planned

This is the part that turns a chat app into something that does work. Four layers, and
they are genuinely different concerns:

**Prompt engineering** — how a single message is worded. Per-model system prompts and
saved presets, because a 2 B local model needs much more explicit instruction than a
frontier model does.

**Context engineering** — what the model sees on a given call. On-device this is the
binding constraint: a phone-sized context window fills fast, and `n_ctx` costs RAM. Three
mechanisms:

- **Compaction.** When the context approaches full, summarize the older turns with the
  model itself and continue, instead of the conversation dying. This is what Claude Code
  and Codex do, and the research is consistent about the design: compact *before* the wall,
  keep the most recent turns verbatim, and keep pinned artifacts (the system prompt, the
  current task, file references) out of the summary entirely. The user sees a marker in
  the transcript, and the full pre-compaction history stays on disk — nothing is lost, only
  moved out of the window.
- **Deletion over rewriting where possible.** Summarization is lossy and, on a phone, slow.
  Tool output and duplicated file contents are dropped rather than summarized.
- **Just-in-time retrieval.** `@`-referenced files are re-read when needed rather than
  pasted once and carried forever.

**Loop engineering** — the autonomous cycle. A ReAct loop (reason → act via tool → observe
→ repeat) with explicit stopping conditions, step budgets, and a visible plan the user can
interrupt. On-device this needs harder limits than a server agent: battery and thermal
budget are real, so loops are bounded and pause when the device throttles.

**Harness engineering** — the code that runs all of it reliably: tool dispatch, permission
prompts before anything irreversible, structured errors fed back to the model, and
observability the user can actually see (which tool ran, with what, and what came back).

**Sub-agents** — a fan-out for work that does not fit one context: each sub-agent gets its
own window and returns only its conclusion. On a phone this is sequential rather than
parallel, since there is one model resident in memory at a time — the win is context
isolation, not concurrency.

## 5. Tool calling — planned

The foundation for everything in section 4. llama.cpp's chat templates already accept tool
definitions, and models trained for tool use emit calls in their own format. Work:
plumb tool schemas into template rendering, parse calls out of the stream, model
`ToolCall`/`ToolResult` as message parts, render them in the transcript as inspectable
steps, and gate execution behind permission. Built-in tools come first (device clock, math,
file read within app storage); user-defined and MCP-style tools later.

## 6. Composer affordances — planned

Two input conventions people already know from developer tools:

- **`/`** opens a command palette — new chat, switch model, compact now, set parameters,
  run benchmark. Commands are discoverable by typing rather than buried in menus.
- **`@`** references a file or folder the app can read, inserting a reference rather than
  the contents, so context engineering can fetch it just in time.

Both are pure UI over existing capabilities, which is why they come after tool calling
rather than before it.

## 7. History, usage, and the dashboard — planned

Conversations in Room, with per-message stats. Lifetime usage lives in a separate
append-only day-bucketed ledger so deleting a chat never falsifies your totals: tokens
generated, tokens per day, per-model share, average throughput trend, total inference time.

## 8. Multimodal — done, except dictation

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

## 9. Play Store production — planned

Target API 36 audit, 16 KB alignment verification (already satisfied by NDK r29), foreground
service declarations for downloads, R8, signed AAB, data-safety form (every answer is "no
data collected", which is true by construction), and a security review pass.

---

## Device support

The app must run on as many arm64 Android 12+ devices as possible, not just flagships.
Concretely that means: no SoC name ever gates a feature; the CPU backend is chosen by
runtime capability score with a plain armv8.0 fallback; context length and offload are
clamped to what the device can actually hold; and the fit estimator tells the truth about
models that will not run rather than letting the OS kill the app.
