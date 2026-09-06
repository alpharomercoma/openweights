# Pictures through ExecuTorch

*2026-09-07. What it took to make a compiled vision export read a picture in this app,
and what was measured on the way.*

## The export

Software Mansion publishes LFM2.5-VL for their React Native library as one `.pte`
holding three programs plus constants:

| Method | Role |
|---|---|
| `vision_encoder` | SigLIP2 tower plus projector; takes one `[1, 3, 512, 512]` float image, raw 0 to 255, rescale and normalise baked into the graph; returns `[1, 256, 2048]` |
| `token_embedding` | ids to embeddings |
| `text_decoder` | embeddings plus cache position to logits |
| `get_max_context_len` | 2048 |

Their runner letterboxes the picture to the encoder's input (aspect preserved, margins
filled from the corner colour), converts to RGB, channels first, and hands the raw pixels
over. It does not tile: the HF processor's up to ten 512 tiles plus thumbnail is not what
this export does. One picture is one encoder call and 256 positions.

## The runtime this app ships can drive it

The prebuilt `executorch-android` 1.4.0 AAR exposes `LlmModule(MODEL_TYPE_MULTIMODAL, ...)`,
`prefillPrompt`, `prefillImages(FloatArray, w, h, c)` and `generate`. Its JNI runs every
prefill call at once, so call order is cache order. The stock multimodal prefiller feeds
each segment to `text_decoder` in turn, text through `token_embedding`, an image through
`vision_encoder`. Software Mansion replaced that prefiller with one that splices image
embeddings into a fused id buffer and converts dtypes for a hybrid fp32 encoder over a
bf16 decoder, which raised the question whether the stock one would take this export.

It does. Measured on the Poco X8 Pro (Dimensity 9400) with the 450M export:

| Step | Measured |
|---|---|
| Encoder on one 512 square | 1.5 s |
| Positions after the `user` header and the picture | 284 |
| Reply to a red square on blue | "The square is red and the background is blue." |
| Decode | 80 to 85 tok/s |

`ExecuTorchVisionOnDeviceTest` repeats this through the engine from a PNG on disk.

## The layout

The chat template renders `<image>` inside the user turn; the processor expands it to
`<|image_start|>`, the image tokens, `<|image_end|>`. Here the picture is not tokens but
embeddings between two text prefills, so the engine renders the conversation with a
marker where each picture sits, splits the prompt at the markers, and feeds: the text up
to the picture ending in `<|image_start|>`; the picture; then `<|image_end|>` leading the
next text. The last text goes to `generate`. The Java API cannot report the encoder's
input shape (`MethodMetadata` carries a name and backends), so the square is a
per-family constant on the prompt template: 512 for LFM2.5.

## What is deliberately not done

- A turn with pictures never extends the cache and is never extended. The text record
  the engine keeps cannot describe embeddings, so the next turn re-feeds everything,
  pictures included, at 1.5 s per picture. Correct first, fast later.
- No warm for a conversation with pictures, for the same reason.
- Only LFM2.5-VL. Qwen3-VL and the rest stay refused: no export this app can feed, and
  no template that says how.
- No tiling. This export takes one square; a document photo loses small print the way
  a 512 thumbnail would.
