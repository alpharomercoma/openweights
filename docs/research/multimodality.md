# Multimodality on a phone: what is possible, and what is marketing

Research behind the multimodal work in OpenWeights. The question is narrow: for a phone
running open-weight GGUF models with no server behind it, **which modalities can actually
go in, and which can actually come out?**

## The short answer

| Direction | Modality | Status in OpenWeights | Why |
|---|---|---|---|
| **In** | Text | Shipped |: |
| **In** | Image | Shipped | libmtmd vision projector; verified on-device |
| **In** | Audio | Shipped | libmtmd audio projector; verified on-device with LFM2.5-Audio-1.5B |
| **In** | Video | Shipped as sampled frames | libmtmd's own video path shells out to `ffmpeg`; we decode four frames with Android's `MediaMetadataRetriever` instead. Verified on-device |
| **In** | Live video | Not shipped | Same mechanism as video, but prefill cost makes it dishonest to offer: see below |
| **Out** | Text | Shipped |: |
| **Out** | Speech | Shipped | Android `TextToSpeech`, on-device, no network |
| **In** | Dictation | Shipped | Android's *on-device* recogniser only, never the online one |
| **Out** | Image | Not shipped | Needs a diffusion runtime; llama.cpp does not do this |
| **Out** | Video | Not shipped | Not plausible on a phone at any quality worth having |

## Input: llama.cpp's libmtmd is the whole story

llama.cpp handles multimodal input through **libmtmd** (`tools/mtmd`), which loads a
separate GGUF, the **projector**, published as `mmproj-<model>-<quant>.gguf`, and turns
media into embeddings the language model attends over. One runtime covers every supported
family: LFM2-VL, Qwen3-VL, Gemma 3, MiniCPM-V, InternVL, SmolVLM, Pixtral, GLM-4V and
others for vision; Ultravox, Voxtral and Qwen2-Audio for audio.

That mattered for the engine decision. The alternative was a second runtime per modality,
which would have doubled the native surface for no gain. The whole point of choosing
llama.cpp was that any GGUF on the Hub loads without a conversion step, and projectors
inherit that property.

### Video is where the documentation oversells

`mtmd_helper_support_video()` exists and the API accepts video files. Reading
`tools/mtmd/CMakeLists.txt` shows what that costs: `MTMD_VIDEO` requires `LLAMA_SUBPROCESS`
and **an `ffmpeg` binary in `PATH`**, because frame extraction is done by shelling out.
Neither is available to an Android app. There is no ffmpeg on the device, and shipping and
executing one is both a packaging problem and a Play policy problem.

So OpenWeights samples frames itself with `MediaMetadataRetriever` and attaches them as
images. That is what the ffmpeg path does anyway, and it keeps the work inside the app.

The honest limit is arithmetic, not API support. On the dev device a single 448×448 image
costs **13.4 s of prefill**, and four sampled frames cost **69.8 s**. Eight would be over
two minutes before the first token.
Frame count is therefore a deliberate, visible choice rather than something hidden behind a
"video supported" checkbox, and *live* video, which needs this to happen continuously, is
not something a phone-sized model can do today. Claiming otherwise would be the kind of
demo that works once on stage.

### Audio input, measured

LFM2.5-Audio-1.5B at Q4_0 is 696 MB with a 220 MB projector: comfortably phone-sized, and
the smallest audio-capable pair on the Hub worth using. Handed a four-second recording of
"The capital of Portugal is Lisbon, and the year was 1984", it returns that sentence
verbatim after **557 ms of prefill**. Audio is far cheaper than vision: a whole spoken
sentence costs less prompt processing than a single 448 px image.

Note what the repository also ships and llama.cpp does not use: `vocoder-` and `tokenizer-`
GGUFs. LFM2.5-Audio is speech-to-speech, but libmtmd implements the understanding
half only. Downloading the vocoder would buy nothing today.

## Dictation is a different thing from audio input

Audio input is the model listening. Dictation is the phone transcribing so you can type
with your voice, and Android's default recogniser streams that audio to Google.

OpenWeights uses `createOnDeviceSpeechRecognizer` and nothing else. Where the offline
language pack is missing, dictation says so and stops, rather than quietly opening the
network connection the user was told would never happen. That costs availability on some
devices; the alternative makes the app's central claim untrue for one button.

## Output: text is what a language model produces

This is the part most "any-to-any" messaging obscures. A GGUF language model emits tokens.
Everything else is a second model.

- **Speech out.** Half of this has changed and the conclusion has not, yet. It was true
  that llama.cpp implemented no audio decoders; the vendored tree now carries `tools/tts`
  and generative pipelines for Qwen3-TTS and Pocket-TTS in `libmtmd`, so the engine is no
  longer the obstacle. Qwen3-Omni is still a 30B MoE far past what a phone can hold, and
  the app still has no JNI for `mtmd_helper_gen_audio` and nowhere to play a wav. Android's
  own `TextToSpeech` runs on-device, needs no network, and gives the user the thing they
  actually wanted: the reply read aloud. That is what OpenWeights ships today, and a real
  speech model is now a build rather than a wait.

  What such a build inherits from the current sheet is almost nothing: three sampling
  fields out of nine, and no chat template, so no system prompt and no tools. That is
  already modelled by `OutputModality`, and CONTEXT.md carries the table.
- **Image out.** Would need a diffusion runtime, `stable-diffusion.cpp` or MNN, as a
  second engine with its own weights, its own memory budget and its own UI. Defensible
  later; out of scope for a chat app whose promise is running *language* models.
- **Video out.** Not plausible on a phone.

### On "any-to-any"

Qwen3-Omni and Qwen3.5-Omni accept text, image, audio and video and emit text and
speech. They are also 30B-class mixture-of-experts models. Quantized to 4 bits the weights
alone exceed what a 12 GB phone can spare, and llama.cpp implements their *understanding*
path, not their speech decoder. Any-to-any is real, and at that size it is a server
capability. The smaller speech pipelines llama.cpp has since added are a different and much
more plausible thing than any-to-any, and they are what a phone could actually run.

## The message shape: following the Vercel AI SDK

The AI SDK converged on a `parts` array per message, where a file part carries an IANA
`mediaType` alongside its data, and the older image-specific part is deprecated. It is worth
copying because it is the shape every provider now normalises to, and because it is
clear about a thing our engine also needs: what a file *is* is a property of its bytes, not
of which field it arrived in.

`MessagePart` mirrors it, with one deliberate divergence. The AI SDK carries a URL or base64
data; ours carries a **local filesystem path**, because libmtmd reads the file directly and
base64 in a message would mean holding every attachment in memory and in the database. The
app copies attachments into its own storage on the way in, which also makes them survive the
picker's read permission being revoked.

## What this cost, concretely

The projector is a second download and a second resident allocation. For LFM2.5-VL-1.6B it
is 583 MB against a 731 MB model: nearly half again as much. The fit estimator counts it,
Discover says so before the download starts, and deleting the model deletes it. A vision app
that reported only the model size would call a model comfortable and then run the phone out
of memory.

## Sources

- `tools/mtmd/mtmd.h`, `mtmd-helper.h`, `mtmd-image.cpp`, `CMakeLists.txt` in llama.cpp b10333
- llama.cpp `docs/multimodal.md` for the supported-model list
- Vercel AI SDK message-part reference (`FilePart`, `mediaType`, deprecated `ImagePart`)
- Qwen3-Omni model card for the any-to-any claim and its parameter count
