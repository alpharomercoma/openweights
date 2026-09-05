# The vision encoder is the image turn (2026-09-05, night)

After the image-token work in `image-tokens.md`, an image turn on the phone the report came
from (Poco X8 Pro Max, MediaTek MT6991) was about 44 s for a balanced picture, of which the
vision tower was 35 to 43 s. The language model's prefill of the 519 tokens was a few
seconds; the follow-up, a quarter of a second. So the remaining cost is one thing, the
encoder, and this note is about what can be done with it: what the literature says, and what
other vision models measure on the same phone with the same pictures.

## What the literature says

- **FastVLM (Apple, CVPR 2025, arXiv 2412.13303).** The encoder, not the LLM, dominates
  time-to-first-token at high resolution, and the right lever is a hybrid convolutional
  encoder that emits fewer tokens per pixel, then scaling the *input image* rather than
  pruning tokens after the fact. Their FastViTHD is 3.2x faster than SigLIP-SO400M at the
  same accuracy. That is the same conclusion the image-tokens work reached from the other
  side: send fewer pixels, do not fight the encoder afterwards. But it needs a different
  encoder, which means a different model.
- **Phase Matters (arXiv 2606.27906).** Characterizes vision encoding, prefill and decode
  separately on a Snapdragon 8 Elite. Vision encoding is static-shape and compute-dense and
  gets 20 to 45x over the CPU on the NPU; prefill gets 1.64x, decode 1.18x. In other words,
  the encoder is the one stage where an accelerator pays for itself, and the one stage that
  is easiest to export because its shape never changes.
- **Efficient Inference for LVLMs, survey (ACL 2026 Findings, arXiv 2604.05546).** Names
  "visual token dominance" as the bottleneck, and notes that upstream decisions dictate
  downstream cost: the encoder's resolution and token count set everything after it.
- **Rethinking Small VLM Quantization (arXiv 2607.08029).** Component-wise: the encoder,
  projector and LLM quantize differently, and SigLIP encoders can hit kernel-specific INT8
  latency anomalies on some hardware. INT4 saves memory but can add dequantization latency.
  Relevant because the projector files we ship are Q8_0 and the encoder is compute-bound on
  the CPU: a lighter weight format does not buy time here, only a faster path.
- **UltraViT (arXiv 2607.23373) and MagicVL-2B (arXiv 2508.01540).** Encoders redesigned
  for on-device latency: pyramidal mixers (1.7x), or an encoder under 100M parameters
  (41 percent less power at matched accuracy). Both are research models without GGUFs today.
- **Activation quantization of vision encoders (arXiv 2510.04547).** Once the LLM is
  quantized, the encoder becomes the primary time-to-first-token bottleneck, and quantizing
  it gives over 2x. Same diagnosis as ours.

The picture is consistent: for a small VLM on a phone, the encoder is the first-turn cost,
and the three ways out are a smaller encoder, fewer pixels into it, or an accelerator for it.
Fewer pixels is done. The other two are measured below.

## What the encoder costs, on paper

LFM2.5-VL-3B uses SigLIP2 NaFlex shape-optimized 400M: 27 layers, width 1152, MLP 4304,
patch 16, with a 2x2 pixel unshuffle after it. A balanced picture (524,288 pixels) is about
1,920 patches into the tower and 480 tokens out of it. Linear layers: about 0.82 GFLOP per
patch per pass, 1.6 TFLOP for the picture; attention over 1,920 patches adds about 0.46
TFLOP. Two TFLOP for one balanced picture.

| phone | encode | effective |
| --- | ---: | ---: |
| Snapdragon 8 Gen 3 (QDC, plugged in) | 12.6 s | ~160 GFLOPS |
| MT6991 (Poco, 25 percent battery, clocks capped) | 42.6 s | ~47 GFLOPS |

The Poco's cores were pinned during the encode: the big cluster at 2.0 GHz against 3.73,
the rest at 1.4 against 2.4, unchanging across ten seconds of sampling with the encoder on
five cores. The framework reports thermal status 0 and battery saver off; the process is a
foreground one on all eight cores with no utilization clamp. So the cap is the system's,
either HyperOS at a quarter battery or MediaTek's in-kernel thermal limit. Every model below
was measured under the same cap, so the comparison holds; the absolute numbers are a capped
phone and will move when it is charged.

## The knobs the engine already has, on the 3B

`VisionModelBenchmark` (engine androidTest) takes the model, projector, thread counts and
GPU layers as instrumentation arguments and logs one row per picture with what the model
read. LFM2.5-VL-3B, balanced form and page, same phone, same cap:

| run | form encode | page encode | note |
| --- | ---: | ---: | --- |
| default (8 batch threads) | 42.6 s | 46.1 s | clean run |
| 6 batch threads | 53.7 s | 59.9 s | a model push overlapped part of it |
| 4 batch threads | 61.6 s | 66.4 s | a model push overlapped it |
| gpuLayers 99 | 48.9 s | 55.0 s | OpenCL drops the Mali G925 as unsupported; CPU run |

Eight threads is already the best the CPU gives; four to eight buys 1.45x, so the tower
is compute-bound and not waiting on memory. There is no GPU path on this phone: the OpenCL
backend is built for Adreno and refuses the Mali, and the app has no Vulkan build. Reading
did not change across any of these, as expected: same pixels, same tokens.

## Other vision models, same phone, same four pictures

Every model below was handed the balanced-stop files (about 524,000 pixels) and the same
questions, through the same engine, at temperature 0. The phone was the Poco, on battery
between 25 and 19 percent, **with a game running in the foreground the whole time**, which
is what pinned the clocks. Prefill is the whole first turn including the encode; the encode
lines did not survive the game's logcat traffic, but on the 3B the encode is 88 percent of
the prefill. "Read" is planted facts found in the answer.

| model | encoder | form | receipt | probe | page |
| --- | --- | --- | --- | --- | --- |
| LFM2.5-VL-3B Q4_0 (ships today) | SigLIP2 NaFlex 400M | 45.8 s, 2/4 | 67.2 s, 4/4 | 64.2 s, 4/4 | 57.9 s, 6/7 |
| Qwen3-VL-2B Q4_K_M | SigLIP2 300M, M-RoPE grid | 52.7 s, 2/4 | 50.1 s, 4/4 | 50.8 s, 4/4 | 52.8 s, 6/7 |
| SmolVLM2-2.2B Q4_K_M | SigLIP 400M, 512-px tiles | 66.3 s, 1/4 | 52.6 s, 2/4 | 57.3 s, 3/4 | 88.1 s, 0/7 |
| LFM2.5-VL-450M Q8_0 | SigLIP2 NaFlex 86M | 11.1 s, 1/4 | 12.4 s, 1/4 | 15.7 s, 4/4 | 14.4 s, 1/7 |
| Gemma 3 4B Q4_K_M | SigLIP 400M, one 896 square | 389 s, 1/4 | stopped | | |

(The 3B row is the ladder from `image-tokens.md`, run an hour earlier on the same phone
under the same game; the other rows are `VisionModelBenchmark`.)

What this says:

- **Qwen3-VL-2B reads exactly what the 3B reads** on all four pictures, in about the same
  time, with a language model a third smaller. Its encoder is the 300M SigLIP2 with its own
  dynamic grid; at these pixels it makes about the same number of tokens. It is a real
  alternative, not a faster one. Its picture tokens share positions along the grid, which
  the engine's record cannot describe, so a follow-up re-reads the picture from the
  embedding store instead of extending the cache; and it exposed a stats bug, fixed in this
  change: the engine reported positions as the prompt count, 63 tokens for a 500-cell
  picture. Cells are what the context holds, and cells are now what it reports: the same
  screenshot re-run after the fix shows 558 tokens, and its encode line survived this time,
  510 tokens in 44.3 s, the same encoder cost as the 3B to within a second.
- **SmolVLM2 is slower and reads worse.** Its tiler cut the balanced file into 512-pixel
  tiles plus a global view, so it paid for more encodes and lost the page entirely.
- **The 86M encoder is the only thing that is fast.** LFM2.5-VL-450M encodes in a quarter
  of the 3B's time, and reads the clean screenshot perfectly, but loses the handwriting, the
  receipt and the small print. That is the trade the literature describes: FastVLM and
  MagicVL get their speed from a smaller encoder trained for it, and this small encoder was
  not. It would make a fine "fast" tier for screenshots if the app offered one; it is not a
  replacement.
- **Gemma 3 4B is out of this phone's reach with a game running.** Its 2.5 GB weights and
  the game's memory pushed two gigabytes into swap and the one encode took six minutes. Not
  a measurement of the model; stopped after one picture.

## Where this leaves the encoder

Nothing in the engine's own knobs moves it: eight threads is the ceiling, there is no GPU
path on Mali, and the projector weights are not the bottleneck. Nothing in the model zoo
moves it either without paying in reading, except that Qwen3-VL-2B matches the 3B at a
smaller size. The remaining lever is the one every paper points at and the one Phase Matters
measured at 20 to 45x: run the encoder on the accelerator. The vision tower is a fixed-shape
graph (one balanced picture is always the same 1,920 patches into the same 27 layers),
which is exactly what NPU toolchains want and what the language model, with its variable
sequence, is not. On MediaTek that means NeuroPilot; on Qualcomm, QNN; and the encoder's
output is a plain tensor of embeddings that `mtmd_helper_decode_image_chunk` already
accepts, so the seam exists. That is the next experiment, and it is a different size of
work from tonight's.

Two measurements to redo before any of that: the 3B and Qwen3-VL-2B on this phone charged
and idle, because tonight's clocks were the game's, and the same two on the Snapdragon,
where the encode was 12.6 s before any of this.
