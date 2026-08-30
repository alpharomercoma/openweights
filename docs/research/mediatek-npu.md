# Targeting the MediaTek NPU

**Researched and measured 2026-08-30 against the target device: Poco X8 Pro Max,
MediaTek MT6991 (Dimensity 9400), Android 16 / API 36.**

## Decision

**No. Not now.** The blocker is not effort or access — it is that MediaTek's NPU
is an ahead-of-time whole-graph target and this app is a GGUF runner for models
its users choose. Those two facts cannot both hold. The triggers that would
reverse this are listed at the end; check them rather than re-deriving the
argument.

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
to bundle MediaTek's own adapter libraries, obtained from the registration-gated
NeuroPilot Express SDK, in the APK. That is what ExecuTorch's backend does.

## Why there is no ggml backend, and why writing one is not on the table

ggml dispatches operations one at a time against a dynamic graph. MediaTek's
published interface is the opposite shape: `ncc-tflite` (or `mtk_neuron`)
compiles a **whole** graph ahead of time into a proprietary `.dla` — a Deep
Learning Archive, a statically compiled binary for the MDLA and VPU — which
`NeuronRuntime` then executes.

The contrast with Qualcomm is the whole argument. llama.cpp has a Hexagon
backend because the Hexagon SDK lets you write and load **custom kernels** onto
the DSP; the repository carries its own `ggml/src/ggml-hexagon/htp/` sources and
a `libggml-htp.inf`. MediaTek publishes no equivalent. So a ggml MediaTek
backend is not hard work that nobody has done — the API it would need is not
public. Every shipped NPU backend for llama.cpp (Hexagon, CANN, OpenVINO)
exists because its vendor exposed something below whole-graph compilation.

## The two real paths, and what each would cost

**ExecuTorch MediaTek backend.** Explicitly supports D9300 and **D9400**, so the
target device qualifies. Needs the NeuroPilot Express SDK from MediaTek's portal
(`mtk_neuron`, `mtk_converter` wheels), a Linux host, and an ahead-of-time export
from a **PyTorch** model to `.pte`. Quantization is A16W16, A16W8, A16W4, A8W8 or
A8W4.

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
