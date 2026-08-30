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
implementation("org.pytorch:executorch-android:1.4.0")
```

**1.4.0, not the 1.0.0 the documentation shows** — the docs lag Maven Central, which is
worth knowing before copying a version out of a guide. Maven publishes `executorch-android`
(XNNPACK), `executorch-android-qnn` and `executorch-android-vulkan`.
**MediaTek is not among them** — it requires
building from source with the NeuroPilot SDK in place. So the CPU path is a Gradle
dependency and the NPU path is a from-source build behind a portal.

Bringing up CPU first exercises the entire Android side — the router, `.pte` loading, the
template, streaming, tool parsing, the model store — while the gated part stays off the
critical path. Switching to the NPU afterwards is an export flag and a delegate library,
not a rewrite. It also yields an honest ExecuTorch-CPU against llama.cpp-CPU number, which
nothing here has.

## What this engine gives up

Three of these are permanent, and the first is the one that matters for this app.

1. **Reuse works, but only by feeding the runtime the way it actually behaves.**
   *Measured 2026-08-31 on the Dimensity 9400, Qwen3-1.7B q8da4w.* ExecuTorch does not
   match a prefix — it **continues**. `pos_` survives a generation and `generate` appends
   wherever it left off. Re-sending a conversation is therefore a bug, not an optimisation:
   turn one ended at `pos_ 2047` and turn two was refused for appending 2068 more into a
   2048-token window.

   The engine keeps the runtime's cache when the newly rendered prompt genuinely begins
   with what has already been fed, and sends only the remainder:

   | | fed | reused | prefill |
   | --- | ---: | ---: | ---: |
   | turn 1 | 903 | 0 | 5,852 ms |
   | turn 2 | **20** | **1,192** | **215 ms** |

   A second turn costs **0.037×** the first, against **1.36×** when every turn re-read the
   whole conversation. Decode is unchanged at about 27 t/s.

   Three things had to be true for that, and each was found by being wrong first:

   - **History is rendered verbatim, diverging from upstream's template on purpose.** Qwen3
     drops a reply's reasoning once a newer question arrives. The cache holds what was
     actually *generated*, reasoning included, so applying that rule describes a
     conversation the runtime is not holding and throws the cache away. A model that thinks
     could otherwise never reuse anything. The cost is that old reasoning stays in context.
   - **Switching reasoning off costs the cache**, which is the opposite of how it sounds.
     Qwen3 disables it by closing an empty `<think>` block *in the assistant opener*; that
     text is fed and sits in the cache, and the template never reproduces it when the turn
     becomes history.
   - **A reply cut short by a token budget cannot be reused.** A sampled token only enters
     the cache when it is fed back in to produce the next one, so whatever ended generation
     never got there. Ending at a stop marker makes that token identifiable and it is simply
     excluded; ending at a budget leaves it as ordinary text no character position can
     identify, so the engine gives up the cache rather than guess. Asking for 48 tokens
     measured a ratio of **1.96 — worse than no cache at all**, purely because nothing ever
     finished. Production asks for the rest of the window, so replies end naturally.

2. **A curated catalogue.** A `.pte` is compiled ahead of time on a desktop, per backend,
   and for an NPU per SoC. Models arrive because somebody ran a build. That is precisely the
   constraint this project exists to escape, which is why this is the second engine.
3. **Every prompt format written by hand.** A GGUF carries its chat template and llama.cpp
   renders it, so it can run a model nobody here has heard of. A `.pte` carries a compiled
   graph and a tokenizer. ExecuTorch's own documentation says the C++ runner needs the chat
   template "applied manually when running". `Qwen3Prompt` is that, transcribed from
   upstream's Jinja and held to it byte for byte by `Qwen3PromptTest`.
4. **No attachments, for now.** *Corrected 2026-08-31:* earlier text implied ExecuTorch
   could not carry them. It can — there are vision and multimodal model types and prefill
   entry points for images and audio. Nothing here uses them, so pictures and audio stay
   on llama.cpp and libmtmd by choice rather than by limitation.

## How it is wired

`ModelFormat` answers which runtime reads a file — `.gguf` for llama.cpp, `.pte` for
ExecuTorch — and `RoutingInferenceEngine` acts on it, so the five `InferenceEngine` call
sites never learn there are two. Weights are hundreds of megabytes, so the router unloads
one backend before loading into the other, and builds a backend only when a model needs it.

A `.pte` is installed with its tokenizer as a sibling of the same name
(`Qwen3-1.7B.pte`, `Qwen3-1.7B.tokenizer.json`), because a `.pte` says nothing about which
tokenizer produced it and the wrong one gives fluent nonsense rather than an error.

`tools/executorch/export_qwen3.sh` builds both.

## Three things only the device said

None of these are visible from the API, the documentation, or a test against a fake.

- **`seqLen` silently overrides `maxNewTokens`.** Setting both makes the runtime resolve
  the allowance from the sequence length: asking for 24 new tokens behind a 907-token
  prompt produced 1,141, which is 2048 − 907. The budget parameter did nothing at all. The
  bridge sets only `maxNewTokens` and lets the model's own `get_max_seq_len` do the
  clamping.
- **Nothing stops on end-of-turn tokens.** llama.cpp knows them from the GGUF; ExecuTorch
  streams whatever it decodes, so every reply arrived ending in a visible `<|im_end|>`.
  Stop markers now belong to the prompt template, which is the only thing that knows them.
- **The stats keys are `prompt_tokens` and `generated_tokens`**, not the `num_`-prefixed
  names. Guessing wrong reported zero tokens for every generation, which the fallback to
  wall-clock timing hid rather than surfaced.

## Open

- What verbatim history costs in answer quality. It is a deliberate divergence from
  upstream's template and it buys 27x on prefill, but nothing here has measured whether
  keeping old reasoning in context makes replies worse.
- Token boundaries. The cache is compared as *text* while it holds *tokens*, and
  `tokenize(A + B)` need not equal `tokenize(A) + tokenize(B)`. Every split currently lands
  next to `<|im_end|>`, which Qwen3 declares a non-stripping special token and which
  therefore forces a stable boundary — so this is sound today and would need re-checking
  for any other model.
- A like-for-like comparison. These numbers are Qwen3-1.7B at q8da4w on ExecuTorch; the
  llama.cpp figures in [`npu-prefill-multiturn.md`](npu-prefill-multiturn.md) are
  LFM2.5-1.2B at Q4_0/Q8_0. Same ballpark, different models, so neither is evidence about
  the other.
- Quantization is not comparable to the GGUF path. The recipe is `q8da4w` — 8-bit dynamic
  activations, 4-bit weights — against llama.cpp's Q4_0 or Q8_0 on KleidiAI. Speed and
  quality both need their own comparison rather than an assumption.
