# Research notes

Every measured report and decision record in this directory, with what it asks and what
it found. Dates are the last substantive change. Three reports also have published pages:
[latency](https://alpharomercoma.github.io/openweights/latency.html),
[the exported-window study](https://alpharomercoma.github.io/openweights/window.html) and
[its reruns](https://alpharomercoma.github.io/openweights/reruns.html).

## Measured reports

| Note | Date | What it asks | What it found |
|---|---|---|---|
| [who-is-questions.md](who-is-questions.md) | 2026-09-10 | Why "who is X" came back as "Let me look that up" with no search, and what generalises | The compiled LFM2.5 1.2B never calls web_search under the app's instructions (0 of 96 rows); the app now searches the name before the model speaks: 4 of 9 to 8 of 9 correct on the compiled model, 5 to 9 of 9 on the GGUF, Qwen3 unchanged and five seconds faster, zero searches on the six questions that must not be searched; a fourth routing wording measured and refuted, and the compiled export found calling 0 of 10 current-facts questions where the GGUF calls 5 |
| [executorch-window-matrix.md](executorch-window-matrix.md), tables in [window-matrix.md](window-matrix.md) | 2026-09-07 | Does the context window an ExecuTorch model is exported with change answers, tool calls, speed or memory? Four chips, five windows, 90 prompts | It changes memory at load and nothing else distinguishable from run-to-run variation; a full-attention model at 32k is killed by a 6 GB rule on the 12 GB phones tested |
| [executorch-own-exports.md](executorch-own-exports.md) | 2026-09-07 | Can we export LFM2.5 ourselves at 16k and 32k, and what does it cost? | A 1.2B export takes two minutes on a laptop; the window costs memory, not speed |
| [executorch-vision.md](executorch-vision.md) | 2026-09-07 | What it takes to feed a compiled vision export a picture | LFM2.5-VL encodes a 512 square in 1.5 s and decodes at 80 to 85 tok/s on a Dimensity 9400; two families are fed, two are refused with a reason |
| [vision-encoder-cost.md](vision-encoder-cost.md) | 2026-09-06 | Where a 44-second image turn goes | The vision tower is 35 to 43 s of it; the language model is a few seconds |
| [image-tokens.md](image-tokens.md) | 2026-09-05 | Is an image-token slider the fix for slow image turns? | No: fewer image tokens made the turn four times slower, because a lower budget turns tiling on |
| [public-benchmarks.md](public-benchmarks.md), tables in [benchmark-matrix.md](benchmark-matrix.md) | 2026-09-04 | GSM8K, IFEval and BFCL on both runtimes, six phones, same prompts and graders | llama.cpp ahead on grades everywhere; the scores are the leaderboard's rules applied to what the app's parser returned |
| [first-turn-latency.md](first-turn-latency.md) | 2026-09-04 | Where a 25.6-second cold first turn went | 18.5 s of it was before the first token; warming the prefix took a fresh chat under a second |
| [tool-calling.md](tool-calling.md) | 2026-09-04 | What moves tool-choice accuracy on a 1B model | Which model it is matters more than the prompt or the route |
| [parity-five-socs.md](parity-five-socs.md), tables in [backend-parity.md](backend-parity.md) | 2026-09-03 | Do the two runtimes grade the same agentic prompts the same way across silicon? | llama.cpp graded identically on all five phones; ExecuTorch showed three cross-silicon divergences |
| [production-readiness.md](production-readiness.md) | 2026-09-03 | Closing every open question from the second-runtime work | Every llama.cpp grade identical across SoCs, ten rows case for case |
| [executorch-families.md](executorch-families.md) | 2026-08-31 | What supporting a `.pte` family costs | Four artifacts plus fixtures, about an afternoon, nothing in the engine changes |
| [kv-cache-regression.md](kv-cache-regression.md) | 2026-08-31 | A reported throughput regression | The prompt was being rewritten mid-conversation; the engines were unchanged |
| [gpu-backends.md](gpu-backends.md) | 2026-08-31 | Is the GPU worth using on the development phone? | The OpenCL driver works but ggml drops the Mali GPU as unsupported; the app runs on the CPU |
| [mediatek-npu.md](mediatek-npu.md) | 2026-08-30 | Should the app target the MediaTek NPU? | No: level at decode, 1.3x to 2.1x at prefill against a KleidiAI CPU |
| [npu-prefill-multiturn.md](npu-prefill-multiturn.md) | 2026-08-30 | Would NPU prefill pay on real conversations? | After cache reuse a turn prefills a median of 50 tokens; there is almost nothing to offload |
| [speculative-decoding.md](speculative-decoding.md) | 2026-08-30 | Does a draft model help decode on a phone? | 1.5x to 3x slower than plain decode |

## Decision records and design notes

| Note | Date | What it decides |
|---|---|---|
| [inference-engines.md](inference-engines.md) | 2026-08-24 | llama.cpp with GGUF as the engine for arbitrary Hub models |
| [executorch.md](executorch.md) | 2026-09-01 | ExecuTorch as the second runtime, XNNPACK first |
| [multimodality.md](multimodality.md) | 2026-08-24 | Which modalities go in and out: text, image, audio, sampled video in; text and Android TTS out |
| [web-search.md](web-search.md) | 2026-09-01 | Where web search comes from; DuckDuckGo, Brave and Yahoo, no self-hosted SearXNG |
| [memory-recall.md](memory-recall.md) | 2026-09-05 | A linear scan over a small memory rather than a vector index |
| [engine-settings.md](engine-settings.md) | 2026-09-05 | Which of a competitor's nineteen settings belong on the sheet: none |
| [capabilities-ia.md](capabilities-ia.md) | 2026-08-10 | Tools, skills and MCP are three different things on this platform |
| [loops-and-kv-cache.md](loops-and-kv-cache.md) | 2026-09-10 | Where the four agent loops live in the app; the canvas census (sixteen pages, no load-time error, three cut-off or stub pages) and the grader it shaped; and the KV formula corrected: Qwen3's stated key length is not the embedding over the heads, which had halved the 0.6B's cache estimate |
| [plan-mode-and-recall.md](plan-mode-and-recall.md) | 2026-09-05 | Plan mode must plan, not answer; recall and search credulity fixes |
| [date-in-the-prompt.md](date-in-the-prompt.md) | 2026-09-05 | Why a greeting got answered about the date |
| [generation-runtimes.md](generation-runtimes.md) | 2026-09-01 | No runtime publishes a reproducible on-phone image-generation measurement; not offered |
| [ios-strategy.md](ios-strategy.md) | 2026-09-01 | Native Swift over the shared C++ core for a second platform |
| [competitive-analysis.md](competitive-analysis.md) | 2026-08-31 | OpenWeights against seven cloud assistants and seven on-device apps |

## Review sweeps

| Note | Date | What it covers |
|---|---|---|
| [qa-sweep-2026-09-02.md](qa-sweep-2026-09-02.md) | 2026-09-03 | Six independent reviews over the whole app, every finding verified against the code |
| [gemini-review-2026-09-03.md](gemini-review-2026-09-03.md) | 2026-09-03 | An adversarial whole-codebase review: 65 claims, 43 fixed, 16 wrong, 6 left by choice |
| [qa-sweep-2026-09-05.md](qa-sweep-2026-09-05.md) | 2026-09-05 | Commands, compiled models, canvas and page reading on a Snapdragon 8 Gen 3 |
