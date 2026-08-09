# OpenWeights — Working Context

> Living state file. Update at every milestone so no information is lost across sessions
> or context compaction. Newest facts win; keep it accurate rather than exhaustive.

Last updated: 2026-08-10 (end of Phase 1)

## What this project is

A native Android app that runs open-weight LLMs from Hugging Face entirely on-device.
The ChatGPT / Claude / Gemini experience — chat, histories, multimodal input, voice —
but for local GGUF models, with no account, no cloud, and no telemetry.

Distribution target: **Google Play Store**. Sideloading over ADB is only for development.
Fully open source under **Apache-2.0**, aimed at a developer audience.

## Locked decisions

| Decision | Value | Rationale |
|---|---|---|
| Language / UI | Kotlin + Jetpack Compose | True native; best perf and UX control |
| Inference engine | llama.cpp (GGUF), behind `InferenceEngine` | Only engine that can run *any* HF model; see `docs/research/inference-engines.md` |
| Application ID | `io.github.alpharomercoma.openweights` | Permanent once published to Play |
| License | Apache-2.0 | Permissive + patent grant; compatible with llama.cpp (MIT) |
| minSdk / targetSdk / compileSdk | 31 / 36 / 37 | Play requires API 36 for new apps from 2026-08-31; current AndroidX needs compileSdk 37 |
| ABI | arm64-v8a only | 32-bit ARM is irrelevant for LLM workloads; keeps the AAB small |
| CPU backend | `GGML_CPU_ALL_VARIANTS`, chosen at runtime | One APK that uses i8mm/SVE2/SME where present and still runs on older phones |
| Accounts / telemetry | None, ever | Privacy is the product promise and the Play data-safety story |

## Primary hardware targets

| Device | SoC | RAM | Notes |
|---|---|---|---|
| Poco X8 Pro Max (primary dev device) | MediaTek **MT6991** | 11.5 GiB total | Measured on-device, see below |
| TBD (second test device) | Snapdragon 8 Elite | TBD | Adreno; OpenCL backend is the interesting GPU path there |

### Measured facts for the dev device (adb, 2026-08-09)

```
ro.product.model      = 2602BPC18G        (ro.product.device = dash)
ro.build.version      = Android 16 (SDK 36)
ro.soc.model          = MT6991
abilist               = arm64-v8a         cores = 8
MemTotal              = 11 539 612 kB (~11.0 GiB); MemAvailable ~5.2 GiB at rest
/data free            = 382 GB of 478 GB
cpuinfo Features      = … asimddp sve sve2 svei8mm svebf16 i8mm bf16 fphp asimdhp …
```

**Correction to an earlier assumption: this device does _not_ expose SME or SME2.** It has
`i8mm`, `bf16`, `sve`/`sve2`, `svei8mm`, `svebf16`, and `asimddp` (dotprod). At runtime the
app selects `libggml-cpu-android_armv9.0_1.so` (DOTPROD + MATMUL_INT8 + FP16 + SVE2,
score 55). Never gate features on a SoC name; read the runtime score instead.

## Toolchain (installed 2026-08-09, macOS arm64)

| Tool | Version | Location |
|---|---|---|
| JDK | Homebrew OpenJDK 21.0.12 | `/opt/homebrew/opt/openjdk@21` |
| Android SDK | cmdline-tools 22.0 | `/opt/homebrew/share/android-commandlinetools` |
| Platforms | android-36, android-37.0 | |
| Build tools | 36.1.0, 37.0.0 | |
| NDK | 29.0.14206865 (r29) | 16 KB page alignment by default (Play requirement) |
| CMake (SDK) | 4.1.2 | |
| platform-tools / adb | 37.0.1 | |
| Gradle | 9.7.0 (wrapper checked in) | |

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
```

## Build & dependency versions

AGP 9.3.1 · Kotlin 2.3.20 (AGP 9 compiles Kotlin itself — do **not** apply
`org.jetbrains.kotlin.android`) · KSP 2.3.11 · Hilt 2.60.1 · Compose BOM 2026.06.01 ·
Material3 1.4.0 · OkHttp 5.4.0 · llama.cpp pinned at tag **b10333** (submodule).
Single source of truth: `gradle/libs.versions.toml`.

## Module map

| Module | Responsibility | Status |
|---|---|---|
| `:app` | Compose UI, navigation, ViewModels | chat screen only |
| `:core:common` | Shared domain models (messages, sampler/load params) | done |
| `:core:designsystem` | Theme, colour/type tokens, telemetry components | done |
| `:core:engine` | `InferenceEngine` + llama.cpp JNI (`src/main/cpp`) | done |
| `:core:hub` | Hugging Face client, GGUF parser, downloader | **P2, not started** |
| `:core:data` | Room, DataStore, Keystore token vault, usage ledger | **P3, not started** |
| `:core:device` | Device profiler, fit estimator, benchmark calibration | **P2, not started** |

## Phase status

`docs/ROADMAP.md` holds the full plan, including the agent runtime work.

- [x] **P0** Toolchain + scaffold — app installs and runs on the Poco
- [x] **P1** llama.cpp JNI engine + streaming chat — real generation verified on-device
- [x] **Chat UI** — follow-tail scroll, collapsed reasoning, Markdown with code blocks,
      long-press actions, slash-command palette
- [x] **Compaction** — folds older turns into a model-written summary before the context
      window fills, so long conversations continue instead of dying
- [~] **Compute backend choice** — engine enumerates ggml devices at runtime; GPU backends
      not yet compiled in (see ROADMAP for why), Settings screen not built
- [x] **P2** HF Hub: Keystore-encrypted token vault, search, GGUF header parse over range
      requests, fit estimator, resumable verified downloads, Discover/Models/Settings screens
- [x] **Tool calling** — tools are rendered into each model's own syntax and calls are
      parsed back; verified on-device with LFM2.5 emitting
      `get_weather(city='Manila')`. Execution and permission prompts are still to come.
- [x] **P3** Product: conversations and the usage ledger persist in Room, Usage dashboard,
      a conversation drawer for reopening past chats, and per-model hyperparameters
- [x] **P4** Multimodal in: libmtmd is compiled in and wired through the engine, the
      message model, storage and the UI. Verified on-device — LFM2.5-VL-1.6B describes a
      real image attached from the photo picker. Audio input works through the same path
      with an audio projector (none tested yet); video needs `MTMD_VIDEO=ON`, which the
      vendored tag does not enable. Multimodal out is text plus Android TTS read-aloud;
      dictation is still to come.
- [ ] **P5** Play production: API 36 audit, 16 KB check, AAB, data safety, security review

## Multimodal: what libmtmd gives us, and what it does not

`GGML_BUILD_MTMD=ON` builds `libmtmd.so` (1.2 MB), which turns an image, an audio clip or
a video frame into embeddings the language model attends over. It needs a second GGUF —
the **projector**, published as `mmproj-<model>-<quant>.gguf` next to the model.

The contract, and the traps in it:

- The prompt must contain one **media marker** per attachment, at the position the
  attachment belongs, and the bitmap count must match the marker count exactly. The marker
  is `mtmd_get_marker(ctx)` — do not hardcode `<__media__>`.
- `mtmd_input_text` has a `text_len` field. **Leaving it uninitialised** makes mtmd build a
  `std::string` of garbage length and throw `std::bad_alloc`, surfacing as the useless
  message "image preprocessing error". This cost an hour; it is the single easiest mistake
  to make against this API.
- Media occupies KV-cache positions that no token describes, so the token-prefix reuse that
  makes text follow-ups cheap **cannot** apply to a turn with an attachment. `Session`
  tracks `n_past_` separately from `cached_` and flips `cached_covers_context_` to false,
  which forces the next turn to re-evaluate from position zero.
- `mtmd_helper_log_set` has to be called separately from `llama_log_set`, or projector
  failures go to stderr and vanish on Android.
- `context_used()` must report `n_past_`, not the token count. After a media turn the token
  record is deliberately empty while the cache is nearly full.
- Attachments are untrusted files handed to third-party decoders that read the whole thing
  into memory before knowing what it is. There is a 64 MB cap and a `catch` around the
  bitmap load, because `std::bad_alloc` crossing JNI kills the process.

Projectors are renamed to `mmproj-<model file name>.gguf` on download, so pairing at load
time is a lookup rather than a guess. The convention match (equal model identity with the
quantization suffix stripped) is kept as a fallback for files placed by hand over adb.

Video is sampled into four frames with `MediaMetadataRetriever` and sent as images. Use
`OPTION_CLOSEST`, never `OPTION_CLOSEST_SYNC`: sync frames are keyframes, a short clip can
have exactly one, and the sampler then returns the same picture four times — which looks
like working video support right up until you check the thumbnails.

Measured on the dev device with LFM2.5-VL-1.6B Q4_K_M + Q8_0 projector:
**13.4 s to first token** for one 448x448 image, **69.8 s** for four video frames, then
**29–32 tok/s** decode either way.
Attachments are downscaled to a 1024 px longest edge before they reach the projector —
a full-resolution phone photo tiles into many more patches and turns seconds into minutes.

## Debugging native code without installing anything

HyperOS intermittently blocks installing *new* packages, which kills instrumentation runs
(reinstalling an existing package still works). A standalone native probe sidesteps it
entirely and has a far faster edit-run loop:

```sh
NDK=$ANDROID_HOME/ndk/29.0.14206865
CXX=$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android31-clang++
SRC=core/engine/src/main/cpp
LIBS=$(dirname $(find core/engine/build/intermediates/cxx -name libmtmd.so | head -1))
$CXX -std=c++17 -O2 -I$SRC -I$SRC/llama.cpp/include -I$SRC/llama.cpp/ggml/include \
  -I$SRC/llama.cpp/common -I$SRC/llama.cpp/tools/mtmd -I$SRC/llama.cpp/vendor \
  probe/main.cpp $SRC/engine_session.cpp \
  -L$LIBS -lllama -lggml -lggml-base -lllama-common -lmtmd -llog -o probe/engine_probe
adb push $LIBS/*.so probe/engine_probe /data/local/tmp/probe/
adb shell "cd /data/local/tmp/probe && LD_LIBRARY_PATH=. ./engine_probe model.gguf mmproj.gguf image.png"
```

It links the real `Session`, so it tests our code and not just llama.cpp's.

## Working practice: independent review

After each substantial change, run a second reviewer over the diff rather than trusting a
self-review:

```sh
codex exec --sandbox read-only "Review the Kotlin and C++ in this repo (skip the vendored
llama.cpp submodule). Focus on correctness, concurrency, resource leaks, code smells."
```

The first pass found ten real bugs, including one that silently disabled compaction
entirely. Self-review did not catch it because the code read exactly as intended.

## What inspecting a model costs

Reading a GGUF header over HTTP range requests, measured against the real Hub:

```
parsed lfm2: 30 blocks, kv heads [0,0,8,0,0,8,0,0,0,8,...], read 131072 bytes in 1 request
KV cache at 4096 tokens: 64 MB across 8 attending blocks, versus 240 MB if every block
were charged
```

Two things make this work. llama.cpp writes `general.*` and the architecture's own keys
before the tokenizer, so parsing can stop at the first `tokenizer.` key and skip the
vocabulary — 1.3 KB of useful metadata instead of 8 MB. And `attention.head_count_kv` is
a *per-layer array* on hybrid architectures like LFM2, where attention runs in only a
third of the blocks; treating it as uniform overstates the KV cache almost fourfold and
would turn fits into refusals.

One consequence worth remembering: `general.file_type` is written *after* the tokenizer,
so it is normally absent from a cheap parse. The quantization label comes from the
filename instead, which is where people read it anyway.

## Device measurements

Model: `LiquidAI/LFM2.5-2.6B-GGUF` Q4_K_M (1.67 GB, 2.697 B params, 30 layers, trained to
128 000 tokens). Context 2048. Measured by `ThreadCountBenchmark` / `LlamaCppEngineTest`.

| Build | Threads (gen/batch) | Prefill tok/s | Decode tok/s | TTFT |
|---|---|---|---|---|
| Single generic CPU backend (NEON only) | 4 / 4 | 15.3 | 11.5 | 1377 ms |
| Same, 8 threads | 8 / 8 | 21.3 | 13.9 | 987 ms |
| **Runtime-selected armv9.0_1 backend** | 4 / 4 | 55.3 | 16.8 | 381 ms |
| Same, 8 threads | 8 / 8 | 69.5 | 12.8 | 303 ms |
| **Shipping default (split threads)** | **4 / 8** | **59.8–76.9** | **16.2–16.4** | **274–352 ms** |
| Same, after removing false backpressure cancellation | 4 / 8 | 59.8 | **18.0** | 352 ms |

### Thermal state changes the right answer (2026-08-10)

After a long run of inference tests, the same benchmark inverted:

| Threads | Prefill (cold) | Prefill (hot) |
|---|---|---|
| 2 | — | 73.6 |
| 4 | 55.3 | 55.6 |
| 8 | **69.5** | **28.0** |

On a cold device more threads win; on a throttled one they lose badly, and decode peaked
at 5 threads instead of 4. A fixed thread count is therefore wrong roughly half the time.

`ThermalPolicy` now re-plans the count before every reply from
`PowerManager.getCurrentThermalStatus()`, and `llama_set_n_threads` applies it at runtime.
At `THERMAL_STATUS_CRITICAL` and above it stops generating entirely and says so: Android is
already shedding load to avoid shutting down, and sustained inference is among the heaviest
things a phone can be asked to do.

Two findings worth keeping:

1. **Compiling the right CPU backend is worth ~4.5× on prefill.** Without
   `GGML_CPU_ALL_VARIANTS`, ggml falls back to plain armv8-a with no dotprod or i8mm.
2. **Generation and prompt processing want different thread counts.** Decode is
   bandwidth-bound and peaks at roughly the big-core count (4 here, and gets *worse* at 8);
   prefill is compute-bound and keeps scaling to all 8. `ModelLoadParams` exposes both.

## Product notes discovered while testing

- LFM2.5 is a **reasoning model**: it emits a `<think>…</think>` block before its answer.
  The chat UI must render reasoning collapsed by default (P3), or replies look like the
  model is talking to itself.

## Verified on the device (2026-08-10)

Every screen exercised on the Poco with the screen kept awake:

- Discover searches the live Hub and lists results.
- Opening `unsloth/Qwen3-Coder-30B-A3B-Instruct-GGUF` parsed each file's header over range
  requests and reported **"Will not run at this context length — needs 8.08 GB of 7.15 GB
  usable · KV cache 192 MB"**. The honest refusal path works on real data.
- Settings accepted an access token, encrypted it, and verified it: **"Signed in as
  alpharomercoma"**.
- Models lists the local GGUF; tapping it loads the model and returns to Chat.
- Chat generated at **16.1 tok/s, 0.55 s to first token, 961 tokens**, with reasoning
  collapsed to "Thought for 50.0s", markdown rendered, and a syntax-highlighted Kotlin
  block with a copy button.

Two defects found by looking at it, both fixed: the transcript did not end up scrolled to
the bottom (finishing a reply adds a stats line and reasoning header *above* the answer,
which grows the item after the last token, so the follow-tail signal had to include the
whole entry rather than just its text), and the model detail kept the search field and
sort chips on screen instead of behaving like its own screen.

One environment note: a model file placed with `adb` lands as `shell:ext_data_rw` mode 660
and the app cannot read it — `chmod 666` fixes it. Files the app downloads itself are
unaffected.

## Development device access

Poco X8 Pro Max over **wireless debugging**. Ports change every time the pairing dialog is
opened, so discover them instead of guessing:

```sh
dns-sd -B _adb-tls-pairing._tcp local      # find the instance name
dns-sd -L "<instance>" _adb-tls-pairing._tcp local   # gives host:port
adb pair 192.168.100.171:<pair-port> <code>
dns-sd -L "<instance>" _adb-tls-connect._tcp local
adb connect 192.168.100.171:<connect-port>
```

Two gotchas on this device:
- **HyperOS blocks `adb install`** unless "Install via USB" is on in Developer options;
  the error is `INSTALL_FAILED_USER_RESTRICTED`. Installing via
  `adb push … && adb shell pm install -r -t --user 0 …` is what we use.
- **The app cannot read a models directory created by the shell user.** Let the app create
  `Android/data/<pkg>/files/models` on first launch, then move files into it.
- mDNS can register the device twice (`adb devices` shows an IP entry and a `_tcp` entry),
  which makes Gradle's `connectedAndroidTest` install to both and fail. `adb disconnect`
  the duplicate.

**Rule: never modify anything on the device outside our own package.**

## Running the engine tests on-device

Gradle's `connectedAndroidTest` trips over the install restriction above, so install and
run instrumentation by hand:

```sh
adb shell mkdir -p /data/local/tmp/openweights
adb push LFM2.5-2.6B-Q4_K_M.gguf /data/local/tmp/openweights/model.gguf
./gradlew :core:engine:assembleDebugAndroidTest
adb push core/engine/build/outputs/apk/androidTest/debug/engine-debug-androidTest.apk /data/local/tmp/owtest.apk
adb shell pm install -r -t --user 0 /data/local/tmp/owtest.apk
adb shell am instrument -w -r \
  -e class io.github.alpharomercoma.openweights.core.engine.LlamaCppEngineTest \
  io.github.alpharomercoma.openweights.core.engine.test/androidx.test.runner.AndroidJUnitRunner
```

Note the class name has no `.test` suffix even though the APK's application id does.

## Artifact sizes (2026-08-10)

Release AAB **26.7 MB**, of which the native libraries are the bulk: `libllama.so` plus
seven CPU backend variants. The debug APK is 144 MB because debug builds keep unstripped
native symbols — do not quote that number as the app's size. Trimming native debug symbols
and verifying the Play-delivered download size is a P5 task.

## Tool calling, and where llama.cpp stops helping

Prompts are now rendered by llama.cpp's `common_chat`, which knows how to write tool
definitions into each model family's own syntax — that is a large amount of per-model
knowledge worth borrowing rather than reimplementing.

Its *parser* does not cover everything. LFM2.5 emits
`<|tool_call_start|>[get_weather(city='Manila')]<|tool_call_end|>` — Python call syntax,
not JSON — which llama.cpp's parser returns as ordinary prose. So the engine tries
llama.cpp first and falls back to `ToolCallParser`, a small unit-tested Kotlin parser that
recognises named formats and gives up on anything else. A parser that guesses at unknown
syntax produces confident nonsense; one that recognises formats it knows is safe to fall
back on.

Two traps found while wiring this up, both worth remembering:

- `common_chat_params.prompt` **already ends with** whatever opens the assistant turn.
  `generation_prompt` is the same text kept separately for the parser. Appending it
  duplicates the turn header and the model then answers itself.
- A reasoning model needs a real token budget before it ever reaches a tool call. LFM2.5
  thinks for 50+ seconds; a 256-token budget truncates mid-thought and looks exactly like
  a parser bug.

## Open questions

- GPU offload: Vulkan on this GPU is expected to be slower than the tuned CPU path;
  OpenCL on the Snapdragon device is the more promising experiment. Revisit after P2.
- Whether inference needs a foreground service (currently foreground-app only, to avoid
  Play's `specialUse` review path).
- Thread defaults are calibrated on one device; the P2 benchmark should measure and store
  per-device values rather than relying on the core-count heuristic.
