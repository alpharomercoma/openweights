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

The smallest honest next increment, when it happens, is one MNN OpenCL image proof: a
downloadable INT8 SD1.5 bundle, one fixed seed at 512 by 512 and 10 steps, five warm runs on
one real 8 Gen 2, recording wall time, per step time, the backend that actually ran, peak
PSS, and what cancellation releases. Shipped as "experimental, validated on this device with
this bundle", with no performance claim until that data exists.

Runtime licences are all permissive and compatible with an Apache-2.0 app. The **model**
files are not covered by that: Stable Diffusion and Sana checkpoints, Supertonic weights and
voices, and any Qualcomm QNN libraries each need their own review before distribution.
