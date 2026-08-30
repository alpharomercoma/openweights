# ExecuTorch as a second runtime

Research date: **August 2026**. Companion to
[`inference-engines.md`](inference-engines.md), which chose llama.cpp and reserved this
slot, and [`mediatek-npu.md`](mediatek-npu.md), which measured what the accelerator behind
it is actually worth.

## Decision

**Add ExecuTorch as an optional second engine, bring it up on XNNPACK first, and make
Qwen3-1.7B the first model on it.**

Not as a replacement. llama.cpp stays the primary engine and the only one that satisfies
the project's founding requirement — run any open-weight model a user finds on the Hub.
ExecuTorch cannot do that and never will, for structural reasons set out below.

## Why ExecuTorch rather than LiteRT

Both reach a MediaTek NPU, and both gate it behind the same NeuroPilot Express SDK. The
difference is which device they name.

| | ExecuTorch | LiteRT |
| --- | --- | --- |
| Lineage | PyTorch / Meta | Google, successor to TensorFlow Lite |
| PyTorch input | native `torch.export` | `litert-torch` converts to `.tflite` |
| Qualcomm | QNN backend | **QNN delegate on Maven, ungated** |
| MediaTek | NeuroPilot SDK, portal-gated | same SDK, same gate |
| MediaTek SoCs named | **D9300 and D9400** | `mt6989` (D9300), `MT8189` |
| LLM path | `mtk_llama_executor_runner` | LiteRT-LM |

The test device is a **MT6991, which is a Dimensity 9400**. ExecuTorch names it as a
target; nothing found in LiteRT's documentation does. That decided it.

Worth recording because it cuts the other way for anyone else: **Qualcomm is far better
served than MediaTek on both**, and dramatically so on LiteRT, where the QNN delegate is an
ordinary Gradle dependency with no portal and no NDA. If NPU access were the goal rather
than this phone, the answer would be LiteRT on a Qualcomm device.

## Why Qwen3-1.7B first

ExecuTorch's MediaTek backend supports a **whitelist**, not a format. The models under
`examples/mediatek/models/llm_models/weights/` are 13 across 5 families, and Qwen is the
most represented of them:

> `Qwen3-4B, Qwen3-1.7B, Qwen2-7B-Instruct, Qwen2.5-3B, Qwen2.5-0.5B-Instruct,
> Qwen2-1.5B-Instruct`

with Llama 3.2 1B/3B, Gemma 2/3, Phi 3.5/4 and Whisper alongside. The supported SoCs are
named `DX4` for Dimensity 9400 and `DX3` for Dimensity 9300.

**LFM2.5 is not on that list and will not be.** Its ten short-convolution blocks and
recurrent state are outside every family there, and the work to add them is the same
missing-operator problem `mediatek-npu.md` describes, relocated into `mtk_converter`.

Qwen3-1.7B is also the right *shape* for the question the NPU work left open. The measured
margin on LFM2.5 was squeezed by its hybrid architecture — short-conv blocks are cheap and
dispatch-heavy, the accelerator's worst case. Qwen3-1.7B is dense and pure-attention, which
is where the NPU measured its best. Running the same multi-turn corpus against it would
replace a projection with an end-to-end measurement.

## Why XNNPACK before the NPU

The XNNPACK export needs **no vendor SDK**, and the Android runtime for it is a published
artifact:

```kotlin
implementation("org.pytorch:executorch-android:1.0.0")
```

AARs exist for XNNPACK, QNN and Vulkan. **MediaTek is not among them** — it requires
building from source with the NeuroPilot SDK in place. So the CPU path is a Gradle
dependency and the NPU path is a from-source build behind a portal.

Bringing up CPU first exercises the entire Android side — the router, `.pte` loading, the
template, streaming, tool parsing, the model store — while the gated part stays off the
critical path. Switching to the NPU afterwards is an export flag and a delegate library,
not a rewrite. It also yields an honest ExecuTorch-CPU against llama.cpp-CPU number, which
nothing here has.

## What this engine gives up

Three of these are permanent, and the first is the one that matters for this app.

1. **No prefix reuse.** llama.cpp keeps a KV cache across turns, so a follow-up pays only
   for what changed. ExecuTorch's runner takes a prompt and returns a reply and holds
   nothing between calls, so **every turn re-prefills the whole conversation**. On the
   multi-turn traffic measured in [`npu-prefill-multiturn.md`](npu-prefill-multiturn.md)
   that is the dominant cost, and it will partly or wholly eat whatever the accelerator
   wins. `GenerationStats.cachedTokens` is always zero on this engine, and the zero is true.
2. **A curated catalogue.** A `.pte` is compiled ahead of time on a desktop, per backend,
   and for an NPU per SoC. Models arrive because somebody ran a build. That is precisely the
   constraint this project exists to escape, which is why this is the second engine.
3. **Every prompt format written by hand.** A GGUF carries its chat template and llama.cpp
   renders it, so it can run a model nobody here has heard of. A `.pte` carries a compiled
   graph and a tokenizer. ExecuTorch's own documentation says the C++ runner needs the chat
   template "applied manually when running". `Qwen3Prompt` is that, transcribed from
   upstream's Jinja and held to it byte for byte by `Qwen3PromptTest`.
4. **No projector.** Attachments stay on llama.cpp, via libmtmd.

## How it is wired

`ModelFormat` answers which runtime reads a file — `.gguf` for llama.cpp, `.pte` for
ExecuTorch — and `RoutingInferenceEngine` acts on it, so the five `InferenceEngine` call
sites never learn there are two. Weights are hundreds of megabytes, so the router unloads
one backend before loading into the other, and builds a backend only when a model needs it.

A `.pte` is installed with its tokenizer as a sibling of the same name
(`Qwen3-1.7B.pte`, `Qwen3-1.7B.tokenizer.json`), because a `.pte` says nothing about which
tokenizer produced it and the wrong one gives fluent nonsense rather than an error.

`tools/executorch/export_qwen3.sh` builds both.

## Open

- No measurement yet. Every number in this document is upstream's or inferred; nothing has
  run on the phone.
- Whether re-prefilling every turn leaves any win at all on real multi-turn traffic. This
  is the first thing to measure once a `.pte` loads, and it could sink the whole idea.
- Quantization is not comparable to the GGUF path. The recipe is `q8da4w` — 8-bit dynamic
  activations, 4-bit weights — against llama.cpp's Q4_0 or Q8_0 on KleidiAI. Speed and
  quality both need their own comparison rather than an assumption.
