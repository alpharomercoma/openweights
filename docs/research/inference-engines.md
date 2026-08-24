# On-device inference engines: what OpenWeights runs on, and why

Research date: **August 2026**. This document records the engine evaluation behind
OpenWeights so the choice can be re-litigated later with the same facts in hand.

## The requirement

> Run *any* open-weight model a user can find on Hugging Face, on a phone, with no cloud.

That requirement, "any model the user finds", not "any model we shipped a build for", 
is what eliminates most of the field. It is precisely the limitation of Google AI Edge
that motivated this project.

## Verdict

**llama.cpp with GGUF models.** It is the only runtime where a user can paste a repo they
found five minutes ago and have it work, and it is what essentially every serious
local-LLM Android app ships today.

That verdict is for the primary arbitrary-model text engine. Image and speech *output* are a
different, experimental runtime boundary: MNN is the selected candidate for a measured proof
there, but no production generator is integrated. See
[`generation-runtimes.md`](generation-runtimes.md).

## The field

### llama.cpp: chosen

- **Format:** GGUF, a single self-describing file. ~100k+ GGUF repos exist on the Hub, and
  the community publishes quantizations of new architectures within days of release.
- **Model coverage:** our first target, `LiquidAI/LFM2.5-2.6B`, has official GGUFs from
  Liquid AI and community quants from bartowski. LFM2.5 support landed in llama.cpp
  release `b10262`, so we pin at or above that.
- **Android acceleration:** CPU is the practical path. ggml dispatches at runtime over
  NEON / dotprod / i8mm, and **KleidiAI** adds **SME2** kernels, which the Dimensity
  9500's Arm C1 cores support, worth a large prefill speedup. GPU backends (Vulkan,
  OpenCL) exist but on Mali/Immortalis are frequently *slower* than CPU; OpenCL on Adreno
  is the more promising GPU path and is worth measuring on the Snapdragon test device.
- **Multimodal:** `libmtmd` handles image, audio, and video inputs via per-model `mmproj`
  projector files (LFM2.5-VL, Qwen3-VL, Gemma 3/4, MiniCPM-V, InternVL, Ultravox…), so
  multimodality does not require a second engine.
- **Cost:** it is a C++ library, not a mobile SDK. We write and maintain the JNI layer.

### ExecuTorch 1.0 (Meta): rejected for v1, revisit for NPU

Production-grade (GA October 2025, used across Meta's apps), 12+ hardware backends
including a **MediaTek NPU** backend, and strong measured numbers (~50 tok/s decode for
Llama 3.2 1B on flagship CPUs via XNNPACK + KleidiAI).

It fails our requirement for one structural reason: **it cannot read GGUF**. Models must
be exported to `.pte` ahead of time, on a desktop, per backend, and NPU exports are
per-SoC and gated behind vendor SDKs (NeuroPilot for MediaTek). That reproduces exactly
the curated-catalog constraint we are trying to escape. Its artifacts are also larger
(tied embeddings aren't yet supported by the delegate, duplicating the embedding table).

Worth revisiting as an *optional second engine* for NPU acceleration on specific chips,
which is why `InferenceEngine` is an interface rather than a class.

### MLC-LLM: rejected

Compiles each model through Apache TVM to native code per target. Fast on GPU,
but the catalog is whatever has been compiled; adding a model is a build-system task, not
a download. Same catalog problem.

### MNN (Alibaba): rejected for primary text; selected for an output proof

Excellent mobile CPU/GPU performance and broad model support, with its own conversion
pipeline. Smaller English-language ecosystem and community than llama.cpp; the conversion
step again stands between a user and an arbitrary Hub repo, so it does not satisfy the
primary text-engine requirement.

That rejection does not apply to experimental image or speech output, where a curated,
converted bundle is inherent to the task. MNN is the candidate for the first Android OpenCL
image benchmark and later speech investigation. This is a research selection, not a claim
that either generator currently ships.

### MediaPipe / Google AI Edge (LiteRT): rejected

`.task` / `.litertlm` bundles from a small curated catalog. NPU and GPU acceleration are
good; the catalog is the entire problem statement of this project.

### vLLM, not applicable

Server-side inference engine for datacenter GPUs (CUDA/ROCm), built around PagedAttention
and continuous batching for *serving many concurrent users*. It does not run on Android at
all. Mentioning it here only because it comes up: it is the wrong side of the client/server
line for this project.

## What comparable apps use

| App | Engine |
|---|---|
| PocketPal AI | llama.cpp via `llama.rn` (React Native) |
| ChatterUI | llama.cpp via `llama.rn` |
| SmolChat (Apache-2.0) | llama.cpp via a hand-written Kotlin JNI binding: closest reference to our approach |
| Maid (Flutter) | llama.cpp |
| MLC Chat | MLC-LLM |
| Google AI Edge Gallery | MediaPipe / LiteRT |

## Performance, measured

On a MediaTek MT6991 (8 cores, no SME) running LFM2.5-2.6B at Q4_K_M:

| | Prefill | Decode | Time to first token |
|---|---|---|---|
| Default ggml CPU build | 15.3 tok/s | 11.5 tok/s | 1377 ms |
| Runtime-selected armv9 backend, split thread counts | **68.2 tok/s** | **16.4 tok/s** | **309 ms** |

Two things mattered far more than expected, and both are build/runtime configuration
rather than anything about the model:

1. **Build the CPU backends for the instruction sets phones actually have.** A default
   cross-compiled ggml targets plain armv8-a: no dotprod, no i8mm. Enabling
   `GGML_CPU_ALL_VARIANTS` with `GGML_BACKEND_DL` produces seven Android CPU backends;
   each reports at runtime whether this chip can run it, and the app loads the best one.
   That alone was ~3.4× on prefill. It also means one APK stays correct on older phones
   instead of crashing with SIGILL on unsupported instructions.
2. **Prompt processing and generation want different thread counts.** Decode is
   memory-bandwidth-bound and peaks near the big-core count. It got *slower* using all
   eight cores, because every step waits on the little ones. Prefill is compute-bound and
   scaled all the way to eight. llama.cpp exposes `n_threads` and `n_threads_batch`
   separately; using one number for both leaves throughput on the table either way.

Decode remains bandwidth-bound overall, roughly `memory bandwidth ÷ active model bytes`, 
so quantization choice dominates it. `docs/CONTEXT.md` keeps the current numbers, and the
in-app estimator is calibrated against them.

## Prefix reuse is a property of the model, not of the engine

Measured 2026-08-15 on a Snapdragon 8 Gen 3, same binary, minutes apart. Re-sending an
identical conversation, and then a follow-up that extends it:

| Model | Memory | Repeat prompt | Follow-up |
|---|---|---|---|
| Qwen 2.5 1.5B | transformer | **1 token of 38** | 15 of 38 |
| LFM2.5 2.6B | hybrid recurrent | **19 of 19** | all of it |

A transformer keeps a row per token, so the cache can be cut at any position and everything
before the cut is free. A recurrent or hybrid model carries a running state instead, and can
only roll back as far as it kept snapshots for, which by default is none. `llama_memory_seq_rm`
says so by returning false.

The engine used to ignore that return and rewind its own bookkeeping anyway. Nothing was
removed, `llama_batch_get_one` leaves positions unset, and the next batch landed after the
tail that was supposed to be gone: the model attended to text nobody could see, the cache
grew every turn, and thirty or so generations later `llama_decode` returned 1. That was the
LFM2 failure, and Granite-hybrid, Jamba and Nemotron-H reach it the same way.

Honouring the refusal means starting over, which is the whole prefill on those families, and
that is the trade: a slower follow-up for a session that stays correct. `SustainedUseTest`
is what holds the correct half on hardware, and the two reuse tests are pointed at a
transformer because reuse is not a claim that can be made about every model.

**What would buy it back** is recurrent state checkpoints, which llama.cpp can keep when
asked. Nothing here asks yet, and it costs memory per checkpoint on a device that has none
to spare, so it needs measuring rather than assuming.

## Sources

- llama.cpp: [build docs](https://github.com/ggml-org/llama.cpp/blob/master/docs/build.md),
  [multimodal / libmtmd](https://github.com/ggml-org/llama.cpp/blob/master/docs/multimodal.md),
  [Android GPU backend discussion #9464](https://github.com/ggml-org/llama.cpp/discussions/9464),
  [Vulkan performance #10879](https://github.com/ggml-org/llama.cpp/discussions/10879)
- [LiquidAI/LFM2.5-2.6B-GGUF](https://huggingface.co/LiquidAI/LFM2.5-2.6B-GGUF) ·
  [Liquid docs: llama.cpp deployment](https://docs.liquid.ai/deployment/on-device/llama-cpp)
- [ExecuTorch beta / 1.0 announcement](https://pytorch.org/blog/executorch-beta/) ·
  [optimum-executorch](https://github.com/huggingface/optimum-executorch)
- [Arm SME2 + KleidiAI](https://www.arm.com/technologies/sme2/accelerate-on-device-ai) ·
  [Arm learning path: measure SME2 in llama.cpp](https://learn.arm.com/learning-paths/mobile-graphics-and-gaming/performance_llama_cpp_sme2/run_llm/)
- [SmolChat-Android](https://github.com/shubham0204/SmolChat-Android)
- ["Understanding LLMs in Your Pockets" (arXiv 2410.03613)](https://arxiv.org/html/2410.03613v3)
