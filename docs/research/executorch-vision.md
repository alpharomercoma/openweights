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

## Other families

*2026-09-07, later.* The question was which of the text families this app renders also
have a compiled vision export it can feed. The answer was found by reading each export's
method list and configuration on the phone, since the Java API reports names and not
shapes.

| Family | Export | Verdict |
|---|---|---|
| LFM2.5-VL | Software Mansion, 450M and 1.6B | Fed, above |
| Gemma 3 4B | `pytorch/gemma-3-4b-it-HQQ-INT8-INT4`, optimum-executorch | Fed: 896 square, 256 positions, window 2048 (the file says so; the card says 1024), pixels normalised to -1..1 (see below) |
| SmolVLM2 500M | `mlboydaisuke` community export | Refused: methods `token_embeddings` and `text_model` from an older exporter, no window method; the 1.4.0 multimodal runner aborts the process on it ("Required metadata method get_max_seq_len not found") |
| Qwen3-VL | Software Mansion | Refused: the encoder takes patchified `[1024, 1536]` pixel values and deepstack features, not an image |
| Llama 3.2, Phi-4, Qwen2.5, SmolLM3 | none published | Nothing to feed |

Each fed family states five facts on its prompt template as a `VisionSpec`: the square the
encoder takes, the positions one picture occupies, the text before and after the picture
as the processor writes it, the range the pixels arrive in, and how a picture that is not
square is placed on the square. Software Mansion's runner letterboxes; Gemma 3's processor
resizes straight to 896 by 896 with pan-and-scan off, so a letterbox there would hand the
encoder margins it was never trained on (a 16:9 photo would be 44% padding). The engine
uses the spec for all of it; nothing else in it knows which family it is feeding.

The pixel range is the fact most easily got wrong. Software Mansion baked rescale and
normalise into the LFM2.5-VL graph, so it takes the bytes. The optimum export of Gemma 3
wraps the SigLIP tower alone (`VisionEncoderExportableModule.forward` calls
`model(pixel_values)`), so it takes what the processor would have produced: mean 0.5,
standard deviation 0.5, which is the bytes divided by 255, centred and doubled. ExecuTorch's
own e2e runner feeds Gemma 3 in 0..1 and its README shows it answering; the processor's
range is what the tower was trained on, so that is what this app feeds.

Measured on the Poco X8 Pro with the Gemma 3 4B export and the same red square on blue,
through `ExecuTorchVisionOnDeviceTest` pointed at the file with `-e pte` and `-e tokenizer`:

| Step | Measured |
|---|---|
| Positions after the `user` header and the picture | 285 |
| Time to first token, encoder on one 896 square included | 27.8 s |
| Reply | "The square is red, and the background is blue." |
| Decode | 11 tokens in 1.6 s, about 7 tok/s |

The optimum export also carries the processor's settings as methods (`image_mean`,
`image_std`, `do_normalize`, `rescale_factor`, `image_seq_length`, `size`), which is how
a later version of this app could read the spec from the file instead of the template.
Software Mansion's export carries none of them, so the template stays the source for now.

A probe that fails is not remembered as a fact: the models list asks again next time
rather than showing a vision export as text-only for the rest of the process, and probes
run one at a time so two refreshes cannot map a 3 GB file twice.

An export that reports no window is refused at load when it would be opened for pictures,
because the runner's answer to a missing window method is an abort, not an error the app
could catch. `ExecuTorchMethodsOnDeviceTest` lists a file's methods and tries the
multimodal runner on it, which is how a new publisher's export gets judged before a
template is written for it.

## What is deliberately not done

- A turn with pictures never extends the cache and is never extended. The text record
  the engine keeps cannot describe embeddings, so the next turn re-feeds everything,
  pictures included, at 1.5 s per picture. Correct first, fast later.
- No warm for a conversation with pictures, for the same reason.
- LFM2.5-VL and Gemma 3 only. Qwen3-VL and SmolVLM2 stay refused for the reasons in the
  table, and the rest have no export.
- No tiling. These exports take one square; a document photo loses small print the way
  a 512 or 896 thumbnail would.
