# Runtimes that could generate a picture or a voice on a phone

Researched 2026-08-24, before anything was built, because the answer changed what to build.

The brief was to evaluate MNN, ExecuTorch, LiteRT-LM and ONNX Runtime as an **experimental
secondary** runtime for multimodal *output*. llama.cpp is not being replaced and does not
move.

## The finding that matters most

**For none of the four is there a vendor published, reproducible end to end latency and peak
memory measurement for image generation on a Snapdragon 8 class Android phone.** Not a slow
number. No number.

There are seconds-per-iteration claims in forum posts with no model hash, no resolution, no
step count, no backend, no thermal state and no method for peak RSS. Google's 2023
announcement of on-device image generation says "as quickly as ~15 seconds on higher end
devices" and names no device, resolution, step count or quantization.

So the runtime cannot be chosen on published performance, because there is none to choose
on. It has to be chosen on whether the Android path is maintained at all, and then measured
here.

A second finding, which recurs across all four: **"multimodal" almost always means image and
audio *input* to a language model.** ExecuTorch documents image and audio prefill;
LiteRT-LM's audio support is audio passed *into* an LLM. Neither is a step toward output.

## What each can actually do on Android today

| Runtime | Image out | Speech out | Licence | Verdict |
| --- | --- | --- | --- | --- |
| **MNN** | Yes, maintained: SD1.5 family and Sana, CPU and OpenCL | Yes, new: Supertonic TTS in MNNChat | Apache-2.0 | The only real candidate |
| **ExecuTorch** | Experimental only | No maintained path | BSD-3 | Strong platform, not this |
| **LiteRT-LM / MediaPipe** | Yes, but **deprecated** | No | Apache-2.0 | Do not start here |
| **ONNX Runtime GenAI** | Listed **under development** | No, Whisper is ASR | MIT | Most integration work |

Details worth keeping:

- **ExecuTorch** has an open report of its Android Stable Diffusion path on Qualcomm QNN
  producing random noise on an 8 Elite. Its documented Android audio example is Whisper,
  which is speech *recognition*.
- **MediaPipe Image Generator** works and has a codelab, and its own current Android page
  marks it deprecated and no longer actively maintained. Adopting a deprecated task for a
  new feature is a decision to port it again later.
- **ONNX Runtime GenAI**'s own support matrix puts Stable Diffusion under development, and
  its Java/Android binding is build-from-source with no published package.
- **MNN** is Apache-2.0, released 3.4.1, 3.5.0 and 3.6.0 across March to June 2026, and the
  recent releases contain Android diffusion and TTS work. Its Android generation surface is
  still app and demo shaped rather than a polished SDK, which is a cost rather than a
  blocker.

## On the NPU, for all of them

Qualcomm NPU artefacts are **per chip**. MNN's QNN converter takes a target SoC id and
Hexagon architecture; ONNX Runtime's QNN context binaries are compiled against a target and
a QNN version. An 8 Gen 2 artefact is not an 8 Gen 3 artefact.

That is the same conclusion the chat path reached about NPU access and it has the same
consequence: the NPU is a later, device specific experiment, not a toggle. Start on CPU and
OpenCL.

## What is being built now

Not a runtime. `:core:generation` is pure Kotlin with no JNI: `ImageGenerator`,
`SpeechSynthesizer`, a capability each runtime states rather than the interface assuming,
and a `GenerationBundle` that carries the runtime, the quantization, the sizes and the
target chip so the interface cannot claim a size or a voice the weights do not have.

`InferenceEngine` stays text only. It speaks of a KV cache, a chat template, tools and
tokens per second, none of which a diffusion model has, and the settings sheet has already
had to be rescued once from offering parameters that reach nothing.

The smallest honest next increment, when it happens, is one MNN OpenCL image proof: a locally
converted and checksummed SD1.5 bundle whose source revision, licence and conversion arguments
are recorded, one fixed seed at 512 by 512 and 10 steps, and repeated runs on one real 8 Gen 2.
The run must record wall time, step timing, the backend that actually ran and peak PSS. An INT8
or downloadable bundle is a later deliverable, not something this repository has established.
Any result is labelled "experimental, validated on this device with this bundle", with no
performance claim until the checklist below has been completed.

Runtime licences are all permissive and compatible with an Apache-2.0 app. The **model**
files are not covered by that: Stable Diffusion and Sana checkpoints, Supertonic weights and
voices, and any Qualcomm QNN libraries each need their own review before distribution.

## MNN, built and measured (2026-08-24)

The research above said MNN was the only real candidate and that nobody publishes a size or
a build cost for it. Both are now measured on this project's own toolchain: NDK r29,
`arm64-v8a`, `android-29`, Release, on an M5 with `-j10`.

**It builds.** MNN 3.6.1 configures and compiles for Android arm64 with diffusion and OpenCL
in **105 seconds**.

Two configuration facts worth keeping, because both cost a cycle to find:

- `MNN_BUILD_DIFFUSION=ON` forces the OpenCV surface on, and that surface enables
  `MNN_IMGCODECS`, which supplies the image writer used by Stable Diffusion. Turning OpenCV
  off does not help: the flag is forced, not defaulted.
- With `MNN_SEP_BUILD=OFF`, `MNNOpenCV` becomes an OBJECT library and its `POST_BUILD` step
  is illegal, so CMake refuses. `MNN_SEP_BUILD=ON` is required, which means four shared
  MNN prerequisite libraries rather than one. A usable diffusion bridge also needs MNN's
  separate `diffusion` and `llm` libraries and a project-owned JNI library.

**What the measured prerequisite subset costs**, stripped, arm64 only:

| library | stripped |
| --- | --- |
| `libMNN.so` | 2,707 KB |
| `libMNN_CL.so` (OpenCL backend) | 2,190 KB |
| `libMNN_Express.so` | 698 KB |
| `libMNNOpenCV.so` | 253 KB |
| **measured subtotal** | **5,849 KB, about 5.8 MB** |

This is not the APK cost of image generation. The build also produces `libdiffusion.so` and
`libllm.so`, and an integration will add its own JNI shared object. Their stripped sizes and
the resulting APK delta were not recorded, so no complete runtime-size total is claimed here.

**What is still not done, and is the actual cost.** The MNN source is already pinned in this
repository. A working image generator still needs a JNI bridge of this project's own, SD1.5
or Sana weights converted to MNN format and eventually delivered on demand, and measurement
runs that turn "it generates" into latency and peak-memory evidence. The native prerequisite
build removes one unknown; it does not yet establish a working or distributable feature.

### The model bundle is more than four files

MNN's Stable Diffusion README names `text_encoder.mnn`, `unet.mnn`, `vae_decoder.mnn` and
`tokenizer.mtok`. The supplied conversion script invokes `MNNConvert` with
`--saveExternalData=1`, so each `.mnn` also has a sibling `.mnn.weight` file. A benchmark
fixture therefore contains these seven files:

```text
text_encoder.mnn
text_encoder.mnn.weight
unet.mnn
unet.mnn.weight
vae_decoder.mnn
vae_decoder.mnn.weight
tokenizer.mtok
```

The repository does not contain this bundle, a download location, or verified hashes for it.
The conversion script also does not make INT8 the default and does not propagate a failed
`MNNConvert` subprocess as a failing process. A reproducible conversion wrapper must choose
and record the quantization arguments, fail if any conversion fails, require every expected
file to be non-empty, and write hashes for all seven outputs. Distribution additionally waits
on a review of the source checkpoint's model licence.

### Benchmark acceptance checklist

The first benchmark is an instrumented proof with a trusted fixture pushed to the device. It
is not a production downloader or a public generation feature.

Before a run, record:

- app build and MNN commits; checkpoint repository and immutable revision; conversion command;
  the seven file sizes and SHA-256 hashes;
- device model, SoC, Android version, GPU/driver identity and thermal state;
- requested and verified runtime backend. A request for OpenCL is not evidence that OpenCL
  ran; reject or skip the result if the selected backend cannot be verified as OpenCL.

Use one fixed prompt, seed `42`, 512 by 512 output, 10 steps and guidance `7.5`. Those values
match the fixed SD1.5 path in the pinned MNN source. Keep negative prompts, variable sizes,
previews and other guidance values out of this proof because that path does not implement
them.

Measure cold load and cold generation separately. Then perform one unreported warm-up and
five measured generations, reporting every sample plus median and p95 rather than only an
average. Use memory mode 1 for repeated runs: the pinned implementation releases modules
during a run in modes 0 and 2, so those modes require a fresh load and are not warm runs.
Record total wall time, progress-callback timestamps, baseline/post-load/peak/post-unload PSS,
and the stripped APK native-library delta including `libdiffusion.so`, `libllm.so` and the JNI
bridge. State whether image encoding is included in wall time.

Every measured run must produce a non-empty, decodable 512 by 512 image with non-zero pixel
variance. Preserve the output and raw measurements with the result. A fixed-seed hash may be
used only after the same device/backend/build has established that byte-for-byte output is
stable; otherwise record an explicitly chosen image-comparison threshold.

Cancellation is not part of the current proof. The pinned Stable Diffusion denoising loop has
no cancellation hook, so the benchmark must say "not implemented" rather than infer release
behaviour from closing or killing the process. Before any public generator claims cancellation,
it needs a between-step cancellation check, a distinct cancelled result, a bounded stop-time
test, proof that no final file was published, and post-unload PSS evidence.

The published Maven artifact is not an option and should not be revisited: `com.alibaba.android:mnn`
was last updated **2021-02-24** at version 0.0.8, against an upstream now on 3.6.1. It is
abandoned, and nothing about diffusion or TTS exists in it.
