# Targeting the MediaTek NPU

**Researched and measured 2026-08-30 against the target device: Poco X8 Pro Max,
MediaTek MT6991 (Dimensity 9400), Android 16 / API 36.**

## Decision

**Not yet — but the hardware case is strong, and every earlier reason given in
this document for doubting it turned out to be wrong when measured.** The MDLA
beats the CPU at this app's central kernel by three times at decode width and
ten to nineteen times at prefill widths. What stands in the way is engineering
and product fit: a partial ggml backend, ops the API does not have, and a model
catalogue the user chooses. The triggers are at the end.

## Measured: NPU against CPU, same kernel, same phone

**These numbers were wrong when first published and are corrected here.** The CPU
side allocated weights with `ggml_backend_alloc_ctx_tensors`, on the default
buffer. KleidiAI registers its own buffer type whose `set_tensor` **repacks**
weights into the layout its kernels need, and llama.cpp allocates there through
`ggml_backend_dev_get_extra_bufts`. Skipping that measured the generic CPU path,
and the NPU's advantage was reported as up to 19× when it is nearer 2×.

One `NEURON_FULLY_CONNECTED` of `[M x 2048] @ [2048 x 8192]` in `QUANT8_ASYMM`
pinned to `mtk-mdla`, against the same multiply through ggml's `MUL_MAT`.
**Median** of twenty on both sides — the earlier version compared the NPU's best
against the CPU's best, which is noisier and flattered the accelerator.

| M (tokens) | CPU Q4_K | CPU Q4_0 | CPU Q8_0 | CPU F16 | CPU BF16 | NPU int8 | **NPU × vs best CPU** |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 — decode | 17.0 | 19.3 | **20.5** | 14.0 | 6.3 | ~21 | **1.02** |
| 32 | 143.3 | 318.6 | **448.9** | 97.8 | 10.7 | 566 | **1.26** |
| 128 — prefill chunk | 181.6 | 493.5 | **833.4** | 120.2 | 10.8 | 1,776 | **2.13** |
| 512 | 188.8 | 547.9 | **1,101.4** | 125.0 | 10.8 | 1,566 | **1.42** |

*(GOP/s, higher is better. `Q4_0` and `Q8_0` sit on the KleidiAI buffer; `Q4_K`,
`F16` and `BF16` cannot and stay on the default one — which is the finding.)*

**At decode width the two are level. At prefill widths the NPU is worth about
1.3× to 2.1×.** Not the order of magnitude this document previously claimed.

The CPU ordering is the more useful result: **Q8_0 > Q4_0 > Q4_K**, with Q8_0
reaching 1,101 GOP/s at width 512 against Q4_K's 189. Q4_K has no KleidiAI
kernel, so most of what looked like an NPU win was really the CPU being measured
on its slow path.

Caveats that remain. Weights are graph constants on both sides. This is one
operation with no RMSNorm, no attention and no fallback boundaries, and a real
backend pays a crossing at every op the NPU cannot take. Compiling each shape
costs 180–240 ms.

## Would an 8-bit or unquantised model unlock it?

The obvious escape from "the NPU has no 4-bit type" is to stop shipping 4-bit.
NeuronAdapter has `FLOAT16`, `FLOAT32` and the `QUANT8` family, so a Q8_0 model's
weights map straight onto the hardware with no requantisation at all. Measured,
that is half right, and the half that fails is the interesting one.

Median of twenty, GOP/s, same `[M x 2048] @ [2048 x 8192]`:

| M | CPU Q4_K | CPU Q8_0 | CPU F16 | NPU int8 | NPU f16 |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1 — decode | 17.0 | 20.5 | 14.0 | ~21 | **will not run** |
| 32 | 143.3 | 448.9 | 97.8 | 566 | — |
| 128 — prefill | 181.6 | 833.4 | 120.2 | 1,776 | 998 |
| 512 | 188.8 | 1,101.4 | 125.0 | 1,566 | 1,355 |

*(CPU Q8_0 corrected onto the KleidiAI buffer; the earlier figures here were the
unaccelerated path.)*

**Eight bits is the NPU's format and it is worth about ten times the CPU at
prefill widths.** No conversion step, no accuracy question beyond the one the
quantisation already asks: a Q8_0 GGUF's weights are what the MDLA wants.

**Unquantised is worse than 8-bit on this hardware, and cannot decode at all.**
F16 runs at roughly two thirds of the int8 rate, and at a single token
`NeuronExecution_compute` returns error 5 — it compiles and then refuses to
execute. An F16 model could prefill on the NPU and would have to decode
somewhere else.

**Decode gains nothing either way.** An earlier revision of this document put the
NPU at 2.8x the CPU at one token. That came from a single run of 84.5 GOP/s;
five consecutive runs give 30–68 GOP/s best and 20–23 median, against the CPU's
18.9. At decode width the two are level, and the honest reading is that the NPU
has no advantage there at all.

So the shape of any NPU design is forced, and it is narrower than "use the NPU":
**8-bit weights, matmul only, prefill only, decode stays on the CPU.**

That is not free, and the cost lands on the half that gains nothing. Q8_0 is
about 1.8x the bytes of Q4_K_M — 1.25 GB against 0.7 GB for a 1.2 B model — and
decode is bandwidth-bound, so it pays that tax on every token while getting no
NPU benefit. The CPU is also slower on Q8_0 than on Q4_K at every width measured.

Extrapolating from the current 131 t/s prefill and 36.3 t/s decode, and assuming
matmuls are most of prefill, a 2,000-token prompt with a 200-token reply might go
from about 21 s to about 13 s, while a 100-token prompt with the same reply goes
from about 6 s to about 10 s. **The trade is long prompts against short ones**,
which is a product question rather than an engineering one, and those two figures
are arithmetic on top of single-operation measurements rather than anything
observed end to end.

## Is a prefill/decode split even expressible in ggml?

Yes, and it is not a new idea — it is the mechanism CUDA already uses for partial
offload:

```c
static bool ggml_backend_cuda_device_offload_op(ggml_backend_dev_t dev, const ggml_tensor * op) {
    return get_op_batch_size(op) >= dev_ctx->op_offload_min_batch_size;
}
```

`ggml_backend_dev_offload_op` asks whether an op should move to a backend even
though its data lives elsewhere, and CUDA answers with a batch-size threshold.
That is prefill-on-accelerator, decode-on-CPU, with a tunable cut-off. A Neuron
backend would answer `supports_op` for `MUL_MAT` alone and `offload_op` for
batches above a threshold; `ggml_backend_sched` routes everything else to the
CPU on its own. The KV cache never has to leave the CPU, because attention stays
there — only matmul activations cross.

So the scheduling is free. What is not free, in the order it would bite:

**Activation quantisation.** ggml activations are F32 and `QUANT8_ASYMM` wants
per-tensor int8 with a scale, so every offloaded op quantises in and dequantises
out. ggml already does this for Q8_0 matmuls, but with *per-block* Q8_1; a
per-tensor asymmetric scale is cruder, and the risk is output quality rather than
speed.

**Amdahl.** Only matmuls move. RMSNorm, attention, softmax and RoPE stay on the
CPU, so a tenfold matmul win is about fivefold overall if matmuls are 85–90% of
prefill — a fraction nobody here has measured.

**Weight residency.** The benchmark baked weights in as graph constants, which
would mean a second full copy of the model.
`NeuronModel_setOperandValueFromMemory` with `libneuron_buffer_allocator` exists
to share a DMA buffer instead, and that path is untested here.

**And the awkward one: this app already removed most of the opportunity.**
`TurnRunner` is built so each turn extends the KV cache and only new tokens
prefill — measured earlier at 48 tokens against 1,222 for a tool round that
reused its prefix. At those widths the NPU advantage is a fraction of its peak
and the fixed costs are proportionally largest. The turns that would gain are the
ones the cache work was written to eliminate: a conversation's first turn, a
pasted document, a large tool result, and any full invalidation — which on a
hybrid model like LFM2.5 cannot always be avoided. Those are a minority of turns
and they are also the slowest ones, so the question is not settled by the kernel
number alone.

## What is actually on the device

More than expected. The full MediaTek APU stack is present — `libapusys.so`,
`libapu_mdw.so`, the APUWare AIDL servers, the MVPU compilers — and so is the
NeuroPilot Neuron runtime: `libneuron_runtime.so`,
`libneuron_adapter_mgvi.so`, `libneuron_graph_delegate.mtk.so`,
`libtflite_mtk.so`.

NNAPI is alive here too, which is worth stating because it is easy to check
wrongly. There is no `/vendor/lib64/hw/*neuralnetworks*` — the old HIDL shape —
but `service list` shows three AIDL driver shims:

```
android.hardware.neuralnetworks.IDevice/mtk-dsp_shim
android.hardware.neuralnetworks.IDevice/mtk-mdla_shim
android.hardware.neuralnetworks.IDevice/mtk-neuron_shim
vendor.mediatek.hardware.apuware.apusys.INeuronApusys/default
```

with `/vendor/lib64/libneuralnetworks_sl_driver_mtk_prebuilt.so` for the NNAPI
Support Library. `MDLA` is the NPU proper. NNAPI is nonetheless the wrong door:
it is deprecated from Android 15, frozen to new features, and its op set was
designed for convolutional vision graphs rather than for the dynamic shapes,
KV cache and weight-only int4 an LLM decode step needs. Google's own
replacement path is LiteRT, below.

## The allowlist is not permission

`libneuron_runtime.so` is named in `/vendor/etc/public.libraries.txt`, which is
what normally lets an ordinary app out of its linker namespace to reach a vendor
library. It does not work here. Asked from inside an app process rather than
from an adb shell — the shell has a different namespace and answers an easier
question — the loader refuses:

```
System.loadLibrary(neuron_runtime)         -> dlopen failed: library "libneuron_runtime.so" not found
System.loadLibrary(neuron_adapter_mgvi)    -> dlopen failed: library "libneuron_adapter_mgvi.so" not found
System.loadLibrary(neuronusdk_adapter.mtk) -> "/system_ext/lib64/libneuronusdk_adapter.mtk.so" ... is not accessible
System.loadLibrary(neuralnetworks)         -> LOADED
```

So there is no shortcut of dlopen'ing the vendor runtime. The supported route is
to bundle MediaTek's own adapter libraries in the APK, which is what ExecuTorch's
backend does. Getting them is easier than expected — see below.

## Why there is no ggml backend — corrected

An earlier version of this document said MediaTek publishes nothing below
whole-graph compilation, so the API a ggml backend would need "is not public".
**That was wrong, and it was never checked.** `NeuronAdapter.h` ships in the
freely-downloadable SDK and is an NNAPI-shaped, op-level graph API:

```c
int NeuronModel_addOperand(NeuronModel* model, const NeuronOperandType* type);
int NeuronModel_addOperation(NeuronModel*, NeuronOperationType, uint32_t inputCount, ...);
int NeuronModel_addOperationExtension(...);
int NeuronModel_getSupportedOperations(...);
```

with 174 operation constants including `NEURON_BATCH_MATMUL`,
`NEURON_FULLY_CONNECTED`, `NEURON_SOFTMAX`, `NEURON_ADD`, `NEURON_MUL`,
`NEURON_GATHER` and `NEURON_QUANTIZE`. A graph can be built programmatically and
then compiled. So a ggml backend is *architecturally* conceivable, in the way
the Qualcomm QNN, Rockchip, CANN and OpenVINO backends are.

The decision does not change, but the reasons have to be the true ones. Reading
the same header for what it cannot represent:

**There are no 4-bit tensors.** The complete list of tensor types is `BOOL8`,
`FLOAT16`, `FLOAT32`, `INT32`, `QUANT16_ASYMM`, `QUANT16_SYMM`, `QUANT8_ASYMM`,
`QUANT8_ASYMM_PER_CHANNEL`, `QUANT8_ASYMM_SIGNED`, `QUANT8_SYMM` and
`QUANT8_SYMM_PER_CHANNEL`. Nothing narrower than eight bits exists. Every model
this app ships is Q4_K_M or Q4_0, so reaching the NPU means widening the weights
to int8 or fp16 first — two to four times the memory traffic, in a decode step
whose cost is dominated by streaming weights. The accelerator would be handed a
strictly harder problem than the CPU currently solves.

**There is no RMSNorm.** The only normalisations are
`INSTANCE_NORMALIZATION`, `L2_NORMALIZATION` and
`LOCAL_RESPONSE_NORMALIZATION` — the convolutional-vision set. Every model in
the catalogue uses RMSNorm, so each one would have to be composed from
primitives or fall back to the CPU, and every fallback is a round trip across
the device boundary, twice per layer.

**Compilation is per shape.** `NeuronCompilation_finish` invokes the AOT
compiler. A ggml graph changes shape on every token as the KV cache grows, so a
backend needs shape bucketing and a compiled-network cache to avoid recompiling
constantly. This is solvable and it is real work.

So the honest summary is: not "impossible", but "the API is a poor fit for
4-bit LLM inference, and the two mismatches that matter are in the type system
rather than in the effort".

## The two real paths, and what each would cost

**ExecuTorch MediaTek backend.** Explicitly supports D9300 and **D9400**, so the
target device qualifies. Needs the NeuroPilot Express SDK, a Linux host, and an
ahead-of-time export from a **PyTorch** model to `.pte`. Quantization is A16W16,
A16W8, A16W4, A8W8 or A8W4.

The SDK is **not gated**, which this document previously got wrong. It is a
plain public download with no account, no NDA and no partner status, from
<https://neuropilot.mediatek.com/resources/public/npexpress/en/docs/npexpress>
— note `public` in the path. Verified 2026-08-30 by downloading
`neuropilot-express-sdk-8.0.8-build20250925.tar.gz` (79 MB, HTTP 200, no
credentials). It contains exactly what ExecuTorch names, at the documented
versions:

```
libneuronusdk_adapter.mtk.so          ELF 64-bit aarch64
libneuron_buffer_allocator.so         ELF 64-bit aarch64
mtk_neuron-8.2.23-py3-none-linux_x86_64.whl
mtk_converter-8.13.0+public-cp310-cp310-manylinux_2_17_x86_64...whl
api/NeuronAdapter.h
LICENSE AGREEMENT.pdf
```

Two practical notes. The tarball is served with an opaque S3 filename, so rename
it to the package name before extracting. And the wheels are **linux_x86_64 and
CPython 3.10 only** — an Apple Silicon Mac cannot run the toolchain natively; it
needs a Linux x86_64 host, a `--platform linux/amd64` container, or a cloud
instance. Only the host toolchain has that constraint; the two `.so` are aarch64
for the phone.

The licence is a click-through MediaTek EULA, and its grant is more permissive
than the "confidential" watermark suggests. It is `royalty- and fee-free`, and
allows You to "distribute and sublicense the Software solely in object code
format and as incorporated in Your software application to be used in
conjunction with MediaTek chipsets" — so shipping the two `.so` inside an APK is
permitted. What is not permitted is distributing the SDK "on a standalone
basis". It also explicitly allows use "for the purpose of benchmarking", which
many vendor licences do not, so published numbers would be above board.

**LiteRT NeuroPilot accelerator.** Google's replacement for the old TFLite
NeuroPilot delegate, generally available, with a unified `CompiledModel` API that
promises NPU use "without requesting vendor-specific compilers, runtimes, or
library dependencies". Models are `.tflite`, with AOT recommended and on-device
compilation possible; LLMs go through LiteRT-LM. Its published model coverage is
a short named list — Qwen3-0.6B, Gemma-3-270M, Gemma-3-1B, Gemma-3n-E2B,
EmbeddingGemma-300M — and the chips named are Dimensity 9500, 9300 and 8300.
**D9400 is not confirmed**, which was not resolvable from public documentation.

Both routes mean a second inference engine, a second model format, a second
model catalogue and a per-SoC artifact matrix, living beside llama.cpp.

## Why that is the wrong trade for this app

The product is that a person browses Hugging Face, downloads whichever GGUF they
like, and `FitEstimator` tells them beforehand whether it will run. NPU execution
requires per-model, per-chip compilation from a graph format we never possess,
on a gated Linux toolchain. **A stranger's GGUF cannot be compiled on the
phone.** So the NPU cannot make this app faster; it can only bolt on a separate,
fixed, vendor-compiled catalogue behind a second runtime — which is a different
product wearing this one's name.

The upside is also smaller than it sounds, by our own measurements
(`docs/research/gpu-backends.md`): decode already runs at 36.3 t/s for a 1.2 B
model, comfortably faster than anyone reads. The real cost is prefill at
131 t/s — a 2,000-token context is about fifteen seconds — and prefill is
genuinely what an NPU is good at. That is the one honest argument in favour, and
it does not survive contact with the catalogue problem.

## What would reverse this

Check these rather than re-arguing:

1. **A MediaTek backend lands upstream in ggml.** OpenVINO and Hexagon both did.
   Then this becomes a build flag beside `GGML_OPENCL`, and the answer flips
   immediately.
0. **A matmul-only ggml backend.** The measurement makes this the interesting
   option: cover `MUL_MAT` on the MDLA and let everything else — RMSNorm,
   attention, the ops NeuronAdapter does not have — fall back to the CPU, with
   int8 requantisation at load and a compiled-network cache keyed by shape. The
   open question is not whether the NPU is fast enough. It is whether the
   per-op crossings and the fallbacks eat a 10x kernel advantage, and that is
   answerable with a second experiment rather than by argument: run one
   transformer block end to end, split that way, and compare against the same
   block on the CPU.
2. **LiteRT-LM broadens** to a large model catalogue or accepts GGUF, *and*
   names D9400. Then a "these particular models run on the NPU" mode becomes a
   coherent feature rather than a second app.
3. **The product changes shape** — if OpenWeights ever ships one or two curated
   models instead of any GGUF, ahead-of-time compilation stops being a
   contradiction and becomes a build step.

## Sources

- [ExecuTorch MediaTek backend](https://docs.pytorch.org/executorch/stable/backends-mediatek.html)
- [NPU acceleration with LiteRT](https://developers.google.com/edge/litert/next/npu)
- [MediaTek NPU and LiteRT](https://developers.googleblog.com/mediatek-npu-and-litert-powering-the-next-generation-of-on-device-ai/)
- [Neuron SDK: compiler and runtime](https://genio.mediatek.com/doc/iot-aihub/ai_hub/ai-workflow/neuron-sdk.html)
- [llama.cpp Snapdragon/Hexagon backend](https://github.com/ggml-org/llama.cpp/blob/master/docs/backend/snapdragon/README.md)
