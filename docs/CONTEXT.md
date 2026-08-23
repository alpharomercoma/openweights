# OpenWeights: Working Context

> Living state file. Update at every milestone so no information is lost across sessions
> or context compaction. Newest facts win; keep it accurate rather than exhaustive.

Last updated: 2026-08-10 (end of Phase 1)

## What this project is

A native Android app that runs open-weight LLMs from Hugging Face entirely on-device.
The ChatGPT / Claude / Gemini experience, chat, histories, multimodal input, voice, 
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
| Qualcomm Device Cloud `sun` (second test device) | Qualcomm **SM8750** (Snapdragon 8 Elite) | 14.8 GiB total | Adreno 830; OpenCL backend verified here, see below |
| Qualcomm Device Cloud `pineapple` (second Qualcomm test device) | Qualcomm **SM8650** (Snapdragon 8 Gen 3) | 11 GiB total | Adreno 750. Corrected from SM7675 / Adreno 732: `pineapple` is Qualcomm's codename for SM8650, and the reservation reports `ro.soc.model=SM8650` against `ro.board.platform=pineapple`. It is a flagship, not a mid-range part. The offload crossover measured here is still real, but the explanation that went with it, that a weaker CPU moves it, does not survive the correction and the cause is unattributed until something re-measures it |

### Measured facts for the dev device (adb, 2026-08-09)

```
ro.product.model      = 2602BPC18G        (ro.product.device = dash)
ro.build.version      = Android 16 (SDK 36)
ro.soc.model          = MT6991
abilist               = arm64-v8a cores = 8
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

AGP 9.3.1 · Kotlin 2.3.20 (AGP 9 compiles Kotlin itself: do **not** apply
`org.jetbrains.kotlin.android`) · KSP 2.3.11 · Hilt 2.60.1 · Compose BOM 2026.06.01 ·
Material3 1.4.0 · OkHttp 5.4.0 · llama.cpp pinned at tag **b10549** (submodule).
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

- [x] **P0** Toolchain + scaffold: app installs and runs on the Poco
- [x] **P1** llama.cpp JNI engine + streaming chat: real generation verified on-device
- [x] **Chat UI**: follow-tail scroll, collapsed reasoning, Markdown with code blocks,
      long-press actions, slash-command palette
- [x] **Compaction**: folds older turns into a model-written summary before the context
      window fills, so long conversations continue instead of dying
- [~] **Compute backend choice**: engine enumerates ggml devices at runtime and the
      Adreno OpenCL backend is compiled in and verified on a Snapdragon 8 Elite. The
      choice is not yet in Settings, and `gpuLayers` still defaults to 0, so the GPU is
      available but nothing offloads to it yet.
- [x] **P2** HF Hub: Keystore-encrypted token vault, search, GGUF header parse over range
      requests, fit estimator, resumable verified downloads, Discover/Models/Settings screens
- [x] **Tool calling**: tools are rendered into each model's own syntax and calls are
      parsed back; verified on-device with LFM2.5 emitting
      `get_weather(city='Manila')`. Execution and permission prompts are still to come.
- [x] **P3** Product: conversations and the usage ledger persist in Room, Usage dashboard,
      a conversation drawer for reopening past chats, and per-model hyperparameters
- [x] **P4** Multimodal in: libmtmd is compiled in and wired through the engine, the
      message model, storage and the UI. Verified on-device: LFM2.5-VL-1.6B describes a
      real image attached from the photo picker, and Voxtral-Mini answers about a real
      recording. Video is sampled into frames by the app, because libmtmd decodes video by
      shelling out to `ffmpeg`. Multimodal out is text plus Android TTS read-aloud, with
      dictation through the on-device recogniser.
- [x] **Test tiers**: `./gradlew verify` runs ktlint, detekt, Android lint on the release
      variant, assemble and the unit and Robolectric tiers in one command, 339 tests.
      `verifyOnDevice` is the instrumented tier and is separate because it needs a phone and
      model files: 19 app tests and 16 engine tests, last green on a Snapdragon 8 Gen 3 on
      2026-08-15. A standalone native probe covers the C++ that JNI makes awkward to test,
      such as the UTF-8 validator.

      Two things to know before running the device tier. It wants two models, not one:
      `model.gguf` for everything and `bench/qwen.gguf` for the two prefix-reuse tests, which
      are about a property a hybrid model does not have (see
      [research/inference-engines.md](research/inference-engines.md)). And a device sitting on
      its lockscreen fails every Compose test with "no compose hierarchies found", which reads
      like the app is broken and is not: `adb shell wm dismiss-keyguard` first.
- [ ] **P5** Play production: the code is done and the paperwork is drafted. API 36, 16 KB
      alignment, the AAB and the JNI-survives-R8 guard are verified in the build; the
      listing, the data safety answers row by row, the generative AI declaration and the
      privacy policy are written out in [store-listing.md](store-listing.md) and
      [privacy-policy.md](privacy-policy.md). What is left needs a person: the upload key,
      the graphics, the questionnaire, the foreground service video, and publishing the
      policy at a URL.

## Multimodal: what libmtmd gives us, and what it does not

`GGML_BUILD_MTMD=ON` builds `libmtmd.so` (1.2 MB), which turns an image, an audio clip or
a video frame into embeddings the language model attends over. It needs a second GGUF 
the **projector**, published as `mmproj-<model>-<quant>.gguf` next to the model.

The contract, and the traps in it:

- The prompt must contain one **media marker** per attachment, at the position the
  attachment belongs, and the bitmap count must match the marker count exactly. The marker
  is `mtmd_get_marker(ctx)`: do not hardcode `<__media__>`.
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
  record is empty while the cache is nearly full.
- Attachments are untrusted files handed to third-party decoders that read the whole thing
  into memory before knowing what it is. There is a 64 MB cap and a `catch` around the
  bitmap load, because `std::bad_alloc` crossing JNI kills the process.

Projectors are renamed to `mmproj-<model file name>.gguf` on download, so pairing at load
time is a lookup rather than a guess. The convention match (equal model identity with the
quantization suffix stripped) is kept as a fallback for files placed by hand over adb.

Video is sampled into four frames with `MediaMetadataRetriever` and sent as images. Use
`OPTION_CLOSEST`, never `OPTION_CLOSEST_SYNC`: sync frames are keyframes, a short clip can
have exactly one, and the sampler then returns the same picture four times, which looks
like working video support right up until you check the thumbnails.

Measured on the dev device with LFM2.5-VL-1.6B Q4_K_M + Q8_0 projector:
**13.4 s to first token** for one 448x448 image, **69.8 s** for four video frames, then
**29 to 32 tok/s** decode either way.
Attachments are downscaled to a 1024 px longest edge before they reach the projector 
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

## The top bar is the runtime readout

Every other chat app can put a product name there because the thing behind it never
changes. Here it changes with every download, so the bar carries the runtime's identity 
quantization, compute device, context window, and swaps to a named state while it is busy:
loading weights, reading the prompt, generating, folding earlier turns, cooling down.

"Reading the prompt" earns its place: with four video frames attached that state lasts 70
seconds, and a spinner cannot say what the wait is for. "Cooling down" comes from
`ThermalPolicy.isThrottling()`. A generation that has quietly halved its thread count looks
exactly like a slow model otherwise.

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
vocabulary: 1.3 KB of useful metadata instead of 8 MB. And `attention.head_count_kv` is
a *per-layer array* on hybrid architectures like LFM2, where attention runs in only a
third of the blocks; treating it as uniform overstates the KV cache almost fourfold and
would turn fits into refusals.

One consequence: `general.file_type` is written *after* the tokenizer,
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
| **Shipping default (split threads)** | **4 / 8** | **59.8, 76.9** | **16.2, 16.4** | **274, 352 ms** |
| Same, after removing false backpressure cancellation | 4 / 8 | 59.8 | **18.0** | 352 ms |

### Thermal state changes the right answer (2026-08-10)

After a long run of inference tests, the same benchmark inverted:

| Threads | Prefill (cold) | Prefill (hot) |
|---|---|---|
| 2 |: | 73.6 |
| 4 | 55.3 | 55.6 |
| 8 | **69.5** | **28.0** |

On a cold device more threads win; on a throttled one they lose badly, and decode peaked
at 5 threads instead of 4. A fixed thread count is therefore wrong roughly half the time.

`ThermalPolicy` now re-plans the count before every reply from
`PowerManager.getCurrentThermalStatus()`, and `llama_set_n_threads` applies it at runtime.
At `THERMAL_STATUS_CRITICAL` and above it stops generating entirely and says so: Android is
already shedding load to avoid shutting down, and sustained inference is among the heaviest
things a phone can be asked to do.

Two findings:

1. **Compiling the right CPU backend gains about 4.5x on prefill.** Without
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
  requests and reported **"Will not run at this context length: needs 8.08 GB of 7.15 GB
  usable · KV cache 192 MB"**. The refusal path works on real data.
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
and the app cannot read it: `chmod 666` fixes it. Files the app downloads itself are
unaffected.

## Second device: Snapdragon 8 Elite, and the GPU backend (2026-08-10)

Run on a Qualcomm Device Cloud device over an SSH tunnel to the QDC ADB server. The local
adb server has to be stopped first, because the tunnel binds the same port 5037.

```sh
adb kill-server
ssh -i <key.pem> -o ExitOnForwardFailure=yes -L 5037:<device-host>:5037 -N sshtunnel@ssh.qdc.qualcomm.com &
adb devices   # reaches the remote server through the tunnel
```

**The device.** `SM8750` (Snapdragon 8 Elite, board `sun`), 8 Oryon cores, 15.5 GB RAM,
Android 16 / API 36, Adreno 830 v2. CPU features: dotprod, i8mm, bf16, and **no SME2**,
unlike the Dimensity 9500 in the Poco. Both phones therefore take the `armv8.6_1` CPU
backend, which is the i8mm one.

**Measured with `llama-bench`, Gemma 3 1B Q4_K_M, 762 MiB:**

| test | CPU, 6 threads | Adreno 830, all layers | GPU vs CPU |
| --- | ---: | ---: | ---: |
| pp128 | 149.6 t/s | 722.0 t/s | 4.8x |
| pp512 | 135.8 t/s | 745.6 t/s | 5.5x |
| pp2048 | 121.2 t/s | 653.5 t/s | 5.4x |
| tg64 | 58.2 t/s | 41.1 t/s | 0.71x |

So the GPU is worth roughly five times the CPU at reading a prompt and about a third
slower at writing the answer. Reading is compute-bound and the Adreno has far more of it;
writing one token at a time is bound by memory bandwidth, where a tuned CPU kernel with
i8mm is hard to beat. That is why the backend is built in and offered rather than switched
on: `gpuLayers` defaults to 0, and the phone-sized default stays the CPU.

Thread count matters more than expected. On this chip decode peaks at 4 to 6 threads and
collapses at 8: tg64 goes 47.8 (t=2), 56.9 (t=4), 54.7 (t=6), **23.0 (t=8)**. Prefill
still improves to 6. Using every core is the wrong answer for decode, which is what the
per-reply thread plan already assumes.

**Three things that silently produce a GPU that is not there.** All three were hit here,
and each one looks like success until you read the layer assignments.

1. `LD_LIBRARY_PATH=.:/vendor/lib64` makes the OpenCL backend load and report **"platform
   IDs not available"**, then run every layer on the CPU while still printing `OpenCL` in
   the backend column. Putting vendor libraries on the search path pulls them into the
   wrong linker namespace. Use `LD_LIBRARY_PATH=.` and let the linker resolve
   `libOpenCL.so` itself.
2. Packaging Khronos's ICD loader in the APK shadows the driver. It looks for
   `/vendor/etc/OpenCL/vendors`, which Android devices do not have. The loader is a link
   target only, excluded from the APK by `jniLibs.excludes`.
3. Being listed in `/vendor/etc/public.libraries.txt` is not enough. Since Android 12 an
   app must also name the library in `<uses-native-library>`, or `dlopen` fails with
   "library not found" on a device that plainly has one.

**Instrumented tests on this device: 15 tests, 0 failures, 6 skipped.** Unlike the Poco,
QDC devices allow installing a new test package, so `connectedDebugAndroidTest` works
here. The skips are five multimodal tests with no projector pushed and one tool-calling
test, which now skips rather than fails when the loaded model's template does not render
tools. Gemma 3 1B's does not, and a dropped tool definition is indistinguishable from a
model that chose not to call anything, so `LoadedModelInfo.supportsTools` was added to
tell cannot apart from did not.

## Discover searches by app, not by tag (2026-08-10)

Search asks the Hub for `apps=llama.cpp`, not `filter=gguf`. The Hub computes the app
filter from whether llama.cpp can load the repository; the tag is just a tag anyone can
attach. Measured against the live API, sampling the top 500 by downloads: 411 repositories
appear under both, 89 only under the tag, 89 only under the app.

What the tag adds and the app filter drops is the part that matters. `filter=gguf` offers
`city96/Wan2.1-I2V-14B-480P-gguf` (a video diffusion model, 14 GB, for ComfyUI),
`handy-computer/whisper-large-v3-turbo-gguf` (whisper.cpp's format), and
`jukofyork/creative-writing-control-vectors-v3.0` (not a model). None of them load here.
There is a live test pinning this: `theLlamaCppFilterExcludesGgufThatIsNotALanguageModel`.

`library=gguf` is a third thing again and is wrong for this: it returned
`google-bert/bert-base-uncased` at the top.

The app filter is not a clean chat-model list either. Across 200 results the task tags run
text-generation 100, image-text-to-text 46, none 36, any-to-any 6, feature-extraction 4,
translation 4, sentence-similarity 3, text-to-video 1. Filtering by `pipeline_tag` is
therefore offered but off by default, because a third of repositories carry no task and
would disappear.

Other query parameters confirmed against the live API: `num_parameters=min:2B,max:4B`
(bare `4B` returns nothing, the prefixes are required), `author`, `gated=false`,
`sort=trendingScore` (`trending_score` is rejected), and `expand[]`. Repeated `apps`
values are a union, not an intersection, so there is no point sending more than one.

`expand[]=gguf` would give a repository's true parameter count and context length in the
search response, and is not used: it drags each repository's whole chat template along
with it, and 30 results grow from 15 KB to 270 KB. The size badge on a row is read out of
the repository name instead, which is where people read it anyway.

## One reply, one string (2026-08-10)

A reply exists in three places: the entry on screen, the row in Room, and the history
resent to the model next turn. They have to be the same string, or the chat says one thing
and reopens saying another. Three ways they used to drift, all now closed:

- Publishing to the screen is coalesced to a frame, so the engine's buffer runs ahead of
  it. Both the completion and the stop path apply the whole buffer before anything is
  written.
- For the formats llama.cpp recognises, the raw stream still contains the tool invocation
  syntax that was lifted out of what is displayed. The stored text is rebuilt from the
  parsed parts, with reasoning put back in the `<think>` tags the Kotlin parser reads, so
  reopening renders what was shown.
- Writes are launched rather than awaited, so the screen never waits on the disk. They
  queue behind one fair mutex in `ChatViewModel`, along with the reads that depend on
  them, so a regeneration cannot read past a pending insert and a stopped reply cannot
  land after the question that followed it.

Stopping with nothing produced removes the placeholder rather than storing a blank turn.
A stopped reply is stored without stats: those only arrive with a completion, and the
lifetime ledger should not carry invented numbers.

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

### Two things the suite needs and never says so

Both cost a run each to discover, because the error they produce points somewhere else.

**Wake the phone and drop the lock screen first.**

```sh
adb shell input keyevent KEYCODE_WAKEUP && adb shell wm dismiss-keyguard
```

Every `ChatFlowTest` case fails with `No compose hierarchies found in the app` when the
keyguard is up. The message reads as a Compose or a `setContent` problem and is neither:
the activity reaches RESUMED and is PAUSED a few milliseconds later, because the window
behind a lock screen is never visible, so nothing composes. A QDC device arrives locked.

**Put a model where the app itself looks, not just where the engine tests look.**

```sh
adb shell mkdir -p /sdcard/Android/data/io.github.alpharomercoma.openweights.debug/files/models
adb shell cp /data/local/tmp/openweights/model.gguf \
  /sdcard/Android/data/io.github.alpharomercoma.openweights.debug/files/models/qwen.gguf
```

`/data/local/tmp/openweights` is where the engine tests read from and the app never looks
there. With no model installed the app opens on "Pick a model to begin", which has no
composer at all, so four `ChatFlowTest` cases fail hunting a "Message" field that the
screen is right not to be showing.

The same recipe runs `:app`'s instrumentation. Four classes there need a device, and each
asks a different question:

| class | what only a device can answer |
| --- | --- |
| `HarnessSmokeTest` | that the instructions, the template and the parser still add up to a reply |
| `ToolTurnOnDeviceTest` | that a tool result reaches the answer, proved by a word only the tool knows |
| `ToolChoiceBenchmark` | which tool a model reaches for, and whether it was told there were any |
| `WorkspaceOnDeviceTest` | that a file tool saying "saved" has saved something. Needs a folder shared through the picker and skips without one |

Swap the module and the class name:

```sh
./gradlew :app:assembleDebugAndroidTest
adb push app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk /data/local/tmp/owtest.apk
adb shell pm install -r -t --user 0 /data/local/tmp/owtest.apk
adb shell am instrument -w -r \
  -e class io.github.alpharomercoma.openweights.ui.chat.HarnessSmokeTest \
  io.github.alpharomercoma.openweights.debug.test/androidx.test.runner.AndroidJUnitRunner
```

### A skip and a pass print the same number

The device tier runs on `assumeTrue`, and it has to: these tests need weights that are not
in the repository, and a machine with no phone should not fail a suite it cannot run. The
cost is that "did not run" and "passed" are indistinguishable in the output, and there are
forty-eight of these preconditions.

`ToolTurnOnDeviceTest` is the sharp end. It reads whichever model is sitting at
`model.gguf`, then skips if that model renders no tools, and skips again if it declines to
call one. Push Gemma there and the whole tool path reports itself green with no tool ever
having run. That is not a hypothesis: pushing Hammer during a review made it fail for a
reason belonging to the model rather than the code, and the run before it had been green
for want of a model entirely.

So the preconditions that guard the tool path go through `Fixtures.require`, and a run can
be told not to accept them:

```sh
adb shell am instrument -w -r -e strict true \
  -e class io.github.alpharomercoma.openweights.ui.chat.ToolTurnOnDeviceTest \
  io.github.alpharomercoma.openweights.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Ordinary runs are unchanged and now log a `SKIPPED` line saying what did not happen. A
release run passes `-e strict true`, where a precondition that does not hold is a failure.
Use it before promoting a build: it is the only way a green device tier means what it
appears to mean.

## The harness has contracts, and they are tested on the host (2026-08-14)

The prompt engineering decisions in this repo are each justified by a measurement written
into a KDoc: `ANSWER_STYLE` costing 2,900 tokens before it existed and 286 after,
`DEFAULT_MAX_ROUNDS` at two because four measured at five and a half minutes. Those notes
are rationale. They were never protection: nothing failed when one of them changed.

`TurnRunnerTest` and the harness cases in `ChatViewModelTest` are the protection. They
script `FakeInferenceEngine` pass by pass, so the loop can be asserted without a model's
judgement in the way, and they run in the host tier under `./gradlew verify`:

- a tool named in prose is not run when tools were never offered, and is when they were
- never more than two rounds of tools, then exactly one pass with none
- every call the model makes is answered before the next pass
- plan mode runs nothing and still reaches an answer
- what a tool returns is cut to the context that is actually left
- a conversation that has to fold before its turn folds rather than throwing
- a turn with a tool in it is stored as one reply, and its work counted per pass
- a folded conversation still reports what its summary costs
- stopping while a tool waits for approval frees the screen
- a message that could not be saved says so

Six defects found on 2026-08-14 are the reason each of those exists. The worst two: a
conversation past three quarters full crashed the app on Send, because the compactor read
the composer's busy flag as "the engine is decoding"; and every pass of a tool turn was
written to storage, so a chat that searched reopened with a message the user never saw.

`verify` now also compiles `assembleDebugAndroidTest`. It did not, and the instrumentation
sources had rotted: `ChatScreen` gained two parameters and the screen test calling it had
not compiled since.

### What the GPU is worth, per shape of turn (2026-08-14)

`gpuLayers` existed on `ModelLoadParams` from the day the OpenCL backend landed and nothing
outside a test ever set it, so every install had run every layer on the CPU with no way to
say otherwise. There is now a switch in the model parameters, shown only where a GPU backend
registered.

The published figures for this chip come from `llama-bench`, which measures reading and
writing separately. A turn does both, and in a ratio that depends on what was asked.
`OffloadBenchmark` measures the two shapes through the engine that ships, Gemma 3 1B Q4 on
the Adreno 830:

| turn | prompt | generated | prefill | decode | wall |
| --- | ---: | ---: | ---: | ---: | ---: |
| chat, CPU | 16 | 300 | 266 ms | 6166 ms | **6.4 s** |
| chat, GPU | 16 | 300 | 2004 ms | 8173 ms | **10.2 s** |
| agent, CPU | 2017 | 46 | 13387 ms | 1129 ms | **14.5 s** |
| agent, GPU | 2017 | 42 | 3233 ms | 1340 ms | **4.6 s** |

**The GPU is 3.2x faster on a turn that used a tool and 1.6x slower on a plain chat turn.**
That is a far sharper split than the component figures suggested, and it is the whole
argument for a switch rather than a default: the right answer depends on what the user is
doing, which the app cannot know.

Repeated with Qwen 2.5 1.5B, the two rows move a long way:

| turn | prompt | generated | CPU | GPU |
| --- | ---: | ---: | ---: | ---: |
| chat | 37 | ~200 | **4.7 s** | 8.0 s |
| agent | 2077 | ~50 | 13.4 s | **8.5 s** |
| **load** | | | **0.8 s** | **11.9 s** |

Two things that matter more than the wall clocks. **Loading onto the GPU takes twelve
seconds against under one**, paid on every cold start, because the OpenCL kernels are built
then. And the crossover is a property of the model rather than of the phone: solving the
rates gives **a prompt 1.4x the answer for Gemma and 10x for Qwen**, seven times apart. A
threshold taken from the friendlier model sends Qwen to the GPU at five hundred prompt
tokens against a hundred and fifty of answer, where the CPU is three seconds faster and the
load cost another eleven. `Offload.AUTO` therefore uses the demanding end.

"Chat" and "agent" above are shapes of turn, not modes of the app. There is no chat mode:
every turn has the same tools available, and what actually moves is how much prompt there is
to re-read, which grows with the conversation and jumps whenever a tool returns.

Two things not to misread. The 2004 ms prefill for a sixteen-token chat prompt is one-off
GPU warm-up, not throughput, so the 8 pp/s it implies is meaningless; the agent row's 624
pp/s is the real figure and it lines up with `llama-bench`. And decode is 31 to 37 t/s on
the GPU against 41 to 49 on the CPU, a ratio of about 0.75, which matches the 0.71 already
recorded here.

CPU stays the default because a plain chat is the common case and the one where being slower
is felt immediately.

#### Reproduced on a second Snapdragon 8 Elite, and what switching actually costs

The device above was returned when its Device Cloud session ended. The figures were taken
again on a fresh `sun` instance provisioned from nothing: app installed, Qwen 2.5 1.5B Q4_K_M
fetched by the device itself from Hugging Face, no state carried over. `OffloadBenchmark` was
then run twice, once inside the full suite and once alone in a cold process.

| | CPU run 1 | CPU run 2 | GPU run 1 | GPU run 2 |
| --- | ---: | ---: | ---: | ---: |
| load | 748 ms | 818 ms | 3432 ms | 3138 ms |
| chat wall | 4729 ms | 4827 ms | 6343 ms | 8124 ms |
| agent wall | 13067 ms | 13283 ms | 8382 ms | 8399 ms |
| prefill | 182.2 t/s | 178.3 t/s | 390.1 t/s | 390.5 t/s |
| decode | 31.8 t/s | 32.4 t/s | 16.4 t/s | 16.2 t/s |

Solving those rates for the point where the two turn costs meet gives **10.10x and 10.13x**,
against the 10 that `CROSSOVER_NUMERATOR` already held. The constant was set from one
measurement on a device that no longer exists; it now has two independent confirmations to
within one percent. `Offload.AUTO` chose the faster side on both shapes: CPU for the chat
turn, which it won by 1.34x, and GPU for the agent turn, which it won by 1.56x.

**What a switch costs.** Layers are assigned when the weights are mapped, so Auto changing
its mind means a reload. That reload is about 2.4 s dearer on the GPU, and the first prefill
after it carries a further 1.9 s of OpenCL warm-up: the GPU chat prefill was 1849 ms in the
cold process against 165 ms in the warm one, which is the same one-off already recorded as
2004 ms above. So flipping to the GPU costs roughly **4.3 s once**, against the 4.7 s the
agent turn saves. It therefore about breaks even on the first tool turn and is free money on
every one after it, which is the right shape for a threshold that only trips on conversations
already long enough to have several such turns left in them.

**One figure did not reproduce.** The 11.9 s GPU load recorded on the previous instance came
back as 3.1 s cold and 3.4 s warm here. The obvious explanation, that the earlier number paid
for OpenCL context creation and the later ones did not, is wrong: the cold process was the
faster of the two, and the warm-up demonstrably lands in first prefill instead. Nothing in
this run accounts for the gap, and the earlier device is gone, so it stands as a difference
between two instances rather than a property of the chip. Treat 3 s as the load cost on this
hardware and 12 s as evidence that it is not guaranteed.

#### The crossover is not a constant, and 10 is the worst value it has ever taken (2026-08-17)

`OffloadBenchmark` now solves the crossover instead of printing a table to solve by hand. It
warms each backend before measuring, so the OpenCL kernel build lands on a throwaway turn
rather than inside the prompt rate, and it reports what a switch costs and how many turns of
each shape repay it. Every `.gguf` in `/data/local/tmp/openweights` is measured, because the
answer differs per model and the whole point is to see that.

Run on a **Snapdragon 8 Gen 3 (SM8650, Adreno 750)**, all Q4_K_M, all four models in one
session:

| model | prefill CPU to GPU | decode CPU to GPU | crossover | switch |
| --- | --- | --- | --- | ---: |
| LFM2 1.2B | 117.4 to 202.9 t/s | 28.8 to **31.2** t/s | **none, GPU wins both** | 3.2 s |
| LFM2.5 2.6B | 50.7 to 90.0 t/s | 14.4 to **15.3** t/s | **none, GPU wins both** | 6.7 s |
| Qwen 2.5 1.5B | 70.8 to 129.9 t/s | 20.9 to 17.0 t/s | prompt > **1.68x** answer | 4.5 s |
| Gemma 3 1B | 63.4 to 222.4 t/s | 22.7 to 18.6 t/s | prompt > **0.87x** answer | 3.5 s |

**Qwen's crossover is 1.68 here and 10.1 on the Snapdragon 8 Elite.** Same model, same
quantisation, same engine, same benchmark: six times apart from the chip alone. The claim
recorded above, that the crossover is a property of the model rather than of the phone, is
wrong and this measurement is what refutes it. It is a property of both, and the mechanism is
visible in the rows: GPU decode barely moves between the two chips (17.0 t/s here against
16.4 on the 8 Elite) while CPU decode tracks the CPU (20.9 against 32). The Adreno is not the
variable. The CPU it is being compared against is.

**Both recommended models have no crossover at all on this chip.** They read faster *and*
write faster on the GPU, so there is no turn shape where the CPU wins, and `CROSSOVER_NUMERATOR
= 10` sends them to the CPU on every load. Ten is not a conservative choice here; it is the
largest value ever measured anywhere, and it is applied to models for which the correct value
does not exist.

**What a switch costs, decomposed.** The GPU load for LFM2 1.2B was 18.3 s the first time the
app ever used OpenCL on that install and 4.2 s on every run after it, with no code change in
between and across separate processes. The likely explanation, not proven here, is the
Qualcomm driver's own program cache being cold once per install: llama.cpp's cache is not
what does it. llama.cpp has one, in `cl-program-cache.cpp`, keyed to the device and
enabled by `GGML_OPENCL_KERNEL_CACHE_DIR`; unset, `default_cache_dir()` falls through to
`fs::temp_directory_path()`, which an Android app process has no usable value for, so that
layer is off and the driver's is doing the work. The rest is weight upload: within one process
the 2.6 B model loaded in 8.6 s against the 1.2 B model's 4.2 s, which is about 200 MB/s for
the extra bytes and is not shader compilation at all. Kernel build shows up separately as the
warm-up column, 1.3 to 2.3 s on first prefill.

**Moving the processor did nothing until now.** `savePreferences` wrote the choice to DataStore
and returned. Layers are assigned when llama.cpp maps the weights, so the setting waited for
the next load, which for most people never comes: the user moved it to GPU, the top bar went
on saying CPU because the weights really were still on the CPU, and nothing said the setting
was queued behind an event they had no reason to expect. It now reloads on the spot, keeping
the conversation, and the screen shows the state it already had for this. Driven on the same
SM8650 with LFM2 1.2B, the top bar goes `CPU · 4096 ctx` to `OPENCL · 4096 ctx`
and back, through "Loading the model into memory" each way. **19.4 s the first time the app
ever used OpenCL on that install, 4.4 s every time after.** The context window deliberately
still waits for the next load: growing it can fail for want of memory, and failing a load
nobody asked for would take the model away in exchange for a number being edited.

**What this means for `Offload.AUTO`.** A compiled-in ratio cannot be right: the correct value
ranges from "no threshold exists" to 10 across four models and two chips. The rates are not
predictable from the GGUF header either, since the two models with the closest geometry in the
set, Gemma 3 1B at 26 layers and Qwen 2.5 1.5B at 28, are the two furthest apart on the 8 Elite.
They have to be measured on the device that will run them, once, and kept.

### 4096 was three mistakes in one number (2026-08-17)

`SamplerParams.DEFAULT_CONTEXT_LENGTH = 4096` opened every model on every phone. It is far
below what a modern small model is trained for, it takes no account of the device, and it is
not bounded above by the model either: a model trained to 2048 was still asked for 4096, and
llama.cpp allows that, so past the training length the answers quietly degrade.

`ContextLengthBenchmark` loaded LFM2.5 2.6B Q4_K_M on the MT6991 at six widths, CPU only:

| window | load | RSS | prefill | decode |
| ---: | ---: | ---: | ---: | ---: |
| 4,096 | 7127 ms (cold) | 2717 MiB | 25.6 t/s | 15.8 t/s |
| 8,192 | 3720 ms | 3291 MiB | 64.7 t/s | 15.7 t/s |
| 16,384 | 3960 ms | 3339 MiB | 65.7 t/s | 16.5 t/s |
| 32,768 | 3985 ms | 3432 MiB | 28.6 t/s | 16.2 t/s |
| 65,536 | 4316 ms | 3588 MiB | 31.3 t/s | 16.3 t/s |
| 131,072 | 6365 ms | 3484 MiB | 29.7 t/s | 16.5 t/s |

**Nothing refused, and decode is flat.** 15.7 to 16.5 tokens a second across a thirty-two-fold
range of widths, so a wide window costs nothing per token. Load moves by about two and a half
seconds over the same range. Resident memory grows by roughly 200 MiB from 8k to 128k while
the cache reserved at 128k is about two gigabytes, which says what matters most here: **the
cache is allocated lazily and only the pages a conversation reaches are ever resident.** A
wide window is a promise about what the phone could hold, not a bill it pays at load.

The prefill column is noise rather than signal. The prompt is ten tokens, so the rate is
measured over almost nothing, and the 4096 row is the first load in the process.

So the default is now computed: as much as the model was trained for, as much as this phone
can hold, whichever is smaller, and never more than a third of usable memory for the cache.
`FitEstimator.defaultContextLength` does the arithmetic and `ContextWindows` reads the local
file's header with the same parser Discover uses over the network, so the window the app
opens with is the one the fit card promised before the download.

What bounds it is bytes per token rather than parameters, and that is why one number could
never have worked. LFM2.5 2.6B keeps a cache for ten of thirty blocks, which is 20 KB a token.
Qwen3 1.7B, a smaller model, has attention everywhere and 128-wide heads: 112 KB a token,
nearly six times as much. On this phone the first gets its full 128k and the second gets about
21k, and both are right.

Verified on the device: LFM2.5 1.2B Instruct opens at `ctx=128000` where it opened at 4096,
and the top bar says so.

**A wide window that is full is a different thing from a wide window that is empty**, and the
sweep above only measures the second. The window is a ceiling; what costs is what goes into
it. Measured separately, one load at 32k with a conversation grown into it:

| context in use | prefill | decode |
| ---: | ---: | ---: |
| empty | 0.5 s | 16.7 t/s |
| ~1,300 | 20 s | 14.3 t/s |
| ~5,100 | 122 s | 12.4 t/s |
| ~10,300 | 380 s | 9.6 t/s |

**Decode cost is affine in the context length**, which is what the mechanism predicts: every
token streams the whole of the weights once, a constant, and reads the whole cache once, which
grows linearly. So `seconds per token = a + b*n`.

**That first sweep was thermally confounded and the slope it gave was 26% too steep.** Context
and elapsed wall clock rise together in it, and 380 s of sustained decode is long enough to
throttle a phone: the same empty context measured 16.7 t/s on a cool device and 13.6 t/s after
an hour of benchmarks. `separatesContextFromHeat` brackets it instead, empty then full then
empty again after resting:

| | decode |
| --- | ---: |
| empty, first | 16.59 t/s |
| full, 5,149 tokens | 12.89 t/s |
| empty, after resting 180 s | **16.77 t/s** |

Returning to where it started is the proof that the middle reading is context rather than
heat. Refitted on those points: **a = 0.0600 s/token, b = 3.42e-6 s/token per token of
context**, so a/b is 17,500 tokens, which is where attention costs as much as the weights do.
Decode is at 90% of its empty speed by 1,900 tokens, 80% by 4,400 and 75% by 5,800.

**What follows for the defaults.** The window stays at the maximum, because a ceiling nobody
reaches is nearly free and it is what lets a long document be pasted at all. The number that
matters is when the conversation is folded, and that is now an absolute ceiling as well as a
fraction of the window: `DEFAULT_CEILING_TOKENS = 4096`, about a fifth slower than an empty
context on this hardware, and deliberately below what this device could bear because it is
also what an unmeasured phone has to live with.

**The header is not the trained length, and treating it as one was the bug this was meant to
fix.** `<arch>.context_length` is `max_position_embeddings`: how far the positional encoding
can reach, not how far the model was trained. It is systematically optimistic and the gap is
large.

| model | GGUF `context_length` | its own model card |
| --- | ---: | ---: |
| LFM2.5 1.2B Instruct | 128,000 | **32,768** |
| Qwen3 1.7B | 40,960 | **32,768** |

Both were read off the real files. Everything else a program can read is worse:

| source | LFM2.5 1.2B Instruct | Qwen3 1.7B |
| --- | ---: | ---: |
| GGUF `<arch>.context_length` | 128,000 | 40,960 |
| `config.json` `max_position_embeddings` | 128,000 | 40,960 |
| `tokenizer_config.json` `model_max_length` | 1e30 sentinel | 131,072 |
| `config.json` `rope_scaling` | null | null |
| **the model card, in prose** | **32,768** | **32,768** |

Four machine-readable numbers for Qwen3 and not one of them is what its own card says.
`model_max_length` is a tokenizer truncation default, which is why one model writes "do not
truncate" and the other writes its extended figure. `rope_scaling` is silent exactly where it
would help, because "we did not extend" and "we did not say" look the same. Llama 3.1 8B, the
model this correction was written for, was trained at 8k and stretched to 128k and its GGUF
still writes only `context_length = 131072` with no `rope.scaling.original_context_length` at
all, so the parser change below is correct and very nearly inert.

**There is no fifth field, and a prose parser is not the answer.** Both reviewers rejected it
independently and for the same two reasons. A card is unstructured, so a downward-only parse
throttles a capable model the first time a benchmark table mentions a smaller number, and it
fails silently rather than loudly. And a sideloaded GGUF has no card at all, which an app whose
whole claim is that it runs any file cannot treat as an edge case.

So: the parser prefers `<arch>.rope.scaling.original_context_length` where a file states it,
since that is free and right when present, and the automatic window is capped at
`SAFE_CONTEXT = 32768`. The argument for that number is that a cap can only bite on a model
claiming more than it. Anything trained to 2,048 or 4,096 writes that in its header and the
minimum takes it. What is left is the recent models claiming six figures, whose cards say
32,768 where they say anything. Memory is bounded before the cap and lands near 5,900 tokens
on a 4 GB phone, so the cap never binds where memory is tight and costs about fifty megabytes
where it is not. The header stays the hard bound and the slider still runs to it.

**The number is a safety bound and not a claim about competence**, and the sampler sheet used
to blur exactly that: it said the automatic window was "as much as the model was trained for",
which is the one thing it is not. Serving degraded output under a label implying the model is
good that far out is worse than picking the cap wrong, because nothing about it is visible to
the person reading the answer.

**Two things this does not answer.** Whether a/b transfers between phones: both reviewers said
approximately and neither said exactly, because weight streaming is bandwidth-bound while
attention reads are strided and latency-bound, and those scale differently across memory
subsystems. And prefill, which is the cost this leaves unguarded: 380 s for ten thousand
tokens is not a window problem and shrinking the window does not fix it, it only makes the
paste impossible instead of slow. The guard belongs on prefill itself, and is not built.

Both figures the app needs, a and b, are already in every reply it finishes: decode
milliseconds, tokens generated, and how full the context was. Fitting them from ordinary use
would make the ceiling this device's own number rather than this one.

### The tool loop, proven on hardware (2026-08-14)

`ToolCallingTest` had skipped since the day it was written, because the model pushed to
`/data/local/tmp/openweights/model.gguf` was Gemma 3 1B and its template renders no tools.
Pushing Qwen 2.5 1.5B Instruct instead un-skips it, and it passes: the model returns
`ToolCall(name=get_weather, argumentsJson={"city": "Manila"})`, parsed rather than salvaged.
**Keep a tools-capable model there.** With Gemma the whole tool path is untested and looks
green.

End to end in the app on the same device, Qwen loaded, `tools=true` at load:

| what | log line | what reached the screen |
| --- | --- | --- |
| tools on, explicit request | `withTools=true` then `calls=1`, `calls=0` | the `web_search` chip and the right answer |
| tools on, question it could not know | `offered=true calls=0` | it declined to search and said so |
| tools off in Tools | `withTools=false tools=[]` | answered from memory, no chip |

The middle row is the ceiling rather than a defect. Offered the tools and asked who a
stranger was, a 1.5B model answered "I don't have any specific information" instead of
searching. That is what BFCL measures at around 55 percent multi-turn for models this size,
and no scaffold fixes it: naming the tool in the question is what makes it call.

### A folder the model can work in (2026-08-14)

The three file tools go through the Storage Access Framework and one folder the user grants,
rather than `MANAGE_EXTERNAL_STORAGE`, which Google reserves for file managers and backup
apps and would put the Play release at risk. The app asks for no storage permission at all;
the manifest is unchanged.

**What the device settled.** Granting the root of internal storage is refused outright: the
picker shows "Can't use this folder, to protect your privacy choose another folder" and
greys out its own confirm button. So a workspace is always a subdirectory, and any copy that
invites someone to "share your phone" is wrong. Granting `Documents` works, reports
read and write, and survives `am force-stop`, which is what `takePersistableUriPermission`
is for and the reason attachments still have to be copied in while this does not.

**Three things the design got wrong before it was attacked**, found by codex and agy
independently and each fatal on its own:

1. `DEFAULT_MAX_ROUNDS = 2` made the feature impossible. Find, read, write is three rounds,
   and the third was refused, so a turn spent its whole budget and threw away the step that
   saved the work. Tools now declare whether they chain and only those turns pay for four.
2. Whole-file overwrite plus `ToolBudget` was a data-loss path, not a feature. A long file
   read back gets cut with `[cut short: no context left]`, and a model rewriting from that
   commits the truncated half over the whole. `write_file` therefore creates and refuses an
   occupied path, which removes the outcome rather than warning about it.
3. Confining by string prefix does not work here at all. Document ids are provider-defined
   and have no path structure, so a Drive child shares no prefix with its parent and there
   is nothing for `..` to climb. Containment is the walk down from the granted folder plus
   Android's own refusal to answer outside the grant.

**And one the model itself forces.** Asking a small model to put a file's text inside a JSON
string means asking it to escape every quote, backslash and newline, which it does not
reliably do. The envelope then fails to parse, the argument reads as absent, and the tool
answers "no content was given" while the content sits in the call. The strict reading is
kept where it works and only an already-broken call is scavenged.

**Still to measure.** The literature puts the accuracy cost of a larger tool catalogue at
7 to 85 percent, and going from two tools to five on a 2048 token window is exactly that
change. Whether `web_search` gets worse with the file tools registered has not been measured
on hardware yet, and the answer belongs here when it is.

### What six tools cost, and what actually goes wrong (2026-08-14)

The app carried two tool definitions in the morning and six by the evening, and the
literature prices a bigger catalogue at anywhere from 7 to 85 percent of tool-selection
accuracy. `ToolChoiceBenchmark` measures it on the model and the phone that ship: six
questions answerable with the two web tools alone, at two seeds, once with the small
catalogue and once with all six. Only questions that mean the same thing to both are used,
because asking whether the model picks `read_file` when `read_file` is not offered measures
nothing.

**Two tools scored 2 out of 12. Six tools scored 4 out of 12.** The feared degradation did
not appear; the larger catalogue was slightly better, and at this sample size that is
probably noise in the other direction. Either way, the thing worth worrying about was not
the thing worth worrying about.

**What the numbers actually found is worse, and has nothing to do with how many tools there
are.** The model's decision to look something up is close to inverted:

| asked | with two | with six |
| --- | --- | --- |
| the weather in Manila right now | answered from memory | answered from memory |
| who won Wimbledon this year | answered from memory | answered from memory |
| the current population of Tokyo | answered from memory | searched |
| read this URL | fetched it | fetched it |
| the capital of France | searched | searched |
| who wrote Pride and Prejudice | searched | searched |

It does not search for the things it cannot know, and it does search for the things it
certainly does. Only an explicit address works reliably. That is a bad trade twice over: the
answer is stale where it matters, and a question the model could answer instantly goes to a
third party and costs seconds.

Two honest caveats. Counting "searched for the capital of France" as a miss is a product
opinion rather than a fact, though it is the same opinion that makes the search tool worth
having. And this contradicts what is recorded further up this document for LFM2.5, which
reasoned that it knew a thing and answered, and went to look when it did not. That behaviour
was measured on a different model and does not carry over. Tool-choice findings are about a
model, not about the harness, and should be labelled with the model that produced them.

### The script tool, driven by the model (2026-08-14)

`run_script` works end to end: asked to multiply two five-figure numbers by writing a script,
the model called the tool at all three seeds and the sandbox returned the right answer.

The first run was 2 out of 3, and the one failure is worth keeping because it was invisible
from the host. The model wrote `let result = 48273 * 1179; return result;`, which as a script
is a syntax error, so nothing ran at all. It was picturing the source as the body of a
function somebody would call, which is a perfectly reasonable thing to picture. A third of
the attempts were being lost to the shape of the answer rather than to anything wrong with
the arithmetic.

The interpreter now reads a script the second way when the first way fails to parse, and
only then, since a syntax error means nothing executed and there is no work to repeat. A
genuinely broken script still reports its own complaint rather than the rewriting's. With
that and one clearer sentence in the tool's description, the same question at the same three
seeds came back 3 out of 3.

### Tool choice: 42 percent to 75 percent, and how it was found (2026-08-14)

`ToolChoiceBenchmark` compares arrangements that differ in one thing each, on the phone and
the models that ship. Twenty four routing decisions per arm: eight questions, half needing a
tool and half not, at three seeds. It counts the two ways of being wrong apart, because they
are not the same mistake. Answering "the weather right now" out of memory is a wrong answer;
searching for the capital of France is a slow right one.

Qwen 2.5 1.5B Q4_K_M, Snapdragon 8 Elite:

| system message | sampling | right | over-called | under-called | ms |
| --- | --- | ---: | ---: | ---: | ---: |
| the old wording | 0.8 | 10/24 | 6 | 7 | 1345 |
| the new wording | 0.8 | 14/24 | 2 | 8 | 1056 |
| **the new wording** | **greedy** | **18/24** | **0** | 6 | **1069** |
| new wording, no answer-style line | greedy | 12/24 | 12 | 0 | 1541 |
| date and routing, no answer-style line | greedy | 14/24 | 9 | 1 | 1479 |

Two changes, and each was measured on its own before both were kept.

**The wording.** It used to say "search only when the answer depends on something you cannot
recall", which asks a 1.5B model a question about its own memory. Naming the kinds of
question instead, and telling it what day it is, took it from ten to fourteen. The date is
most of that: a model cannot tell that "this year's final" is past its training data if
nobody tells it the year.

**Greedy while a tool is on the table.** Choosing among tools is an argmax and the
leaderboards score it that way. Fourteen to eighteen, and slightly faster. Only while tools
are offered: the pass that writes the final answer out of tool results has none, so prose
keeps the user's sampler.

**The hypothesis that was wrong, and why it is written down.** The first guess was that
`ANSWER_STYLE`, which opens the system message with "answer from what you know", was arguing
the model out of using its tools. It was not. Removing it made things worse, 12/24 with
twelve over-calls, because it is the only thing holding back the other failure. The two lines
pull in opposite directions on purpose and neither works alone.

**What the first attempt at this measured, which was nothing.** It sent no system message at
all, at a temperature the app does not use, over twelve observations per arm, and concluded
from 2/12 against 4/12 that a larger tool catalogue was harmless. Twelve Bernoulli trials
cannot separate those: Fisher's exact puts it at p = 0.64. The old wording is kept in the
benchmark as a `superseded` arm so the improvement stays reproducible rather than being a
number in a commit message.

### Gemma and LFM render no tools at all (2026-08-14)

`gemma-3-1b-it-Q4_K_M` and `LFM2-1.2B-Q4_K_M` both come back from `supports_tools()` false:
their chat templates drop tool definitions on the floor. The probe is honest, rendering a
tool with an unmistakable name and looking for it, so this is a property of the templates
rather than a bug in the check.

The app does not fail on those models. It quietly becomes a chatbot, and every measurement
above reaches exactly one of the three families tested. No amount of prompt work moves that,
because there is nothing to prompt: the definitions never reach the model. A generic path,
putting the schemas in the system message and parsing a call back out, is the only thing that
would take two thirds of the tested models off zero, and it is the largest piece of work
outstanding on the agent loop.

### Three families, one prompt, and why that does not work (2026-08-15)

Re-run on a second Device Cloud instance, `pineapple` (Snapdragon 8 Gen 3), which is not the
`sun` the earlier tables were measured on, so the timings are not comparable with them. The
catalogue here was three tools rather than six, because no folder was shared on this device
and the file tools correctly excluded themselves.

The point of the run was the models that used to be skipped. Both now route through the
prompted path and both call tools:

| model | route | best arm | right | over-called | under-called |
| --- | --- | --- | ---: | ---: | ---: |
| Qwen 2.5 1.5B | native | greedy | 17/24 | 0 | 7 |
| Gemma 3 1B | prompted | the superseded wording | 12/24 | 9 | 0 |
| LFM2 1.2B | prompted | shipped | 13/24 | 0 | 11 |

**The wording tuned on Qwen is worse on Gemma.** Shipped scores 8/24 there against 12/24 for
the wording it replaced, because it deliberately errs towards looking things up and Gemma's
problem is the opposite: twelve over-calls out of twelve chances at it. Qwen under-calls and
Gemma over-calls, so one sentence cannot serve both, and the tool prompt being a single
global constant is the thing that is actually wrong.

**A tidy theory about that, and its refutation.** Gemma is on the prompted path, which ends
by telling the model to reply with a JSON object, and that instruction looked like an
invitation to call something. If that were the mechanism, LFM2 would over-call too, since it
takes the same path. It does not: zero over-calls and eleven under-calls, the opposite
failure. So the bias belongs to the model rather than to the route, and choosing a wording by
route would have been the wrong fix for a plausible reason.

**Also found here, and fixed the next day.** LFM2 1.2B fails with `llama_decode returned 1`
partway through a sustained run, after something like thirty generations on one loaded
engine. The other two families ran a hundred and sixty each without it. It is a real defect
in the engine or in that model's cache handling rather than anything to do with tool choice,
and it is what stopped the benchmark before LFM's remaining arms.

That was written before the cause was known and this line said "not yet fixed" for longer
than it was true. Both halves were found on 2026-08-15 and are below: the benchmark was
accumulating context between cases, and underneath that, `llama_memory_seq_rm` returns false
on recurrent and hybrid models and the engine ignored the answer. `engine_session.cpp` starts
over when a rollback is refused, and `SustainedUseTest` reproduces the field error against
the old code in about ninety seconds and is flat against this one.

### The wording is finished, and two models were never the problem (2026-08-15)

Full detail in `docs/research/tool-calling.md`. The short version, on `pineapple`
(Snapdragon 8 Gen 3), catalogue of three tools:

**Four system messages, six models, and three of them did not notice.** Llama 3.2 3B and
Granite 3.3 2B scored 12/24 with twelve under-calls under every wording, which for a set that
is half tool questions is the score of a model that never calls anything. Phi 4 Mini scored
15/24 with nine over-calls under every wording. Not close to each other: identical, case by
case. There is nothing left to find by rewriting the instruction, so the benchmark stopped
paying for arms that measure it.

**A model that never calls is either declining or ignorant, and `supportsTools` cannot tell
you which.** The benchmark now renders one question with and without the tools, clearing the
context either side, and compares what the engine had to prefill:

| model | without tools | with tools | difference |
| --- | ---: | ---: | ---: |
| qwen2.5-1.5b | 38 | 470 | 432 tokens of definitions arrived |
| llama3.2-3b | 44 | 543 | 499 |
| granite3.3-2b | 68 | 591 | 523 |

Both silent models were told. Their under-calling is judgement, not a renderer we broke, which
is the opposite of what the earlier note about Gemma and LFM found and had to be checked
rather than assumed.

**576 generations to 84**, by removing what the data showed measured nothing: four wordings to
two formats, eight cases to six, three seeds to one. The seed cut rests on determinism at
temperature zero, which the run now proves rather than assumes: on a native template the two
arms are the same prompt twice, and every case has to answer identically or the run fails.

**`llama_decode returned 1` was accumulation.** The benchmark clears the context between
cases now, and LFM2 1.2B finished both arms where it used to die after about thirty
generations and take the rest of the suite with it. That says what triggered it there. Whether
a long conversation in the app can reach the same state is a separate question and is not
answered by this.

**What a catalogue costs to describe**, measured by a host test that fails if either drifts:
378 tokens for the three tools every install has, 672 for all six once a folder is shared. On
a 2048 token window the second is a third of it, spent on every pass of every turn.

**Prose salvage has never been observed to help.** The route each call arrives by is recorded
now, so the same generations score with and without it. Over seventy two it fired five times:
twice on Llama, net nothing, and three times on Granite, where the arm scored 2/6 with it and
3/6 without. Five firings is not a reason to delete a path built on watching real turns, and
none of these cases has the shape it was built for, which is a model that has already been
handed a tool result. It is on the record and counted every run.

**The Hermes envelope did not win.** `<tool_call>` against the bare object, on the three models
that read their format from the prompt: 1 to 2, 2 to 3, and 4 to 3. Net one case in eighteen,
which at six cases an arm is noise. The parser reads both spellings regardless, because
refusing the one a model was tuned on means refusing the call it made.

### Auto stopped asking about the second search (2026-08-17)

Auto is the default mode and the first tool call of a turn has never asked. What did ask was
the *second* network call: `AgentRunner` remembered that untrusted text had entered the turn
and then gated anything with `leavesTheDevice`, which is both web tools. Two searches to
answer one question is ordinary, and the recommended models over-call, so the prompt appeared
in the normal case. A prompt that appears in the normal case is a prompt that gets tapped
through, which leaves the app slower and no safer.

The gate now keys on the two things that are actually different about the two tools rather
than on the one thing they share:

| | leaves the device | destination chosen by | gated after untrusted text |
| --- | --- | --- | --- |
| `web_search` | yes | the app's configured provider | no |
| `fetch_url` | yes | the model | **yes** |

A page can say "now fetch `https://example.test/?d=...`" and read its own server log. It
cannot do that through a search, which goes to the provider whatever the query says; an
attacker would have to already own that provider's logs. So `Tool.sendsWhereTheModelSays` is
the flag the injection gate reads, and only `fetch_url` sets it.

The second shape is unchanged and is now tracked separately as `Tool.readsPrivateData`, set
only by `read_file`: once the user's own text is in the turn, **anything** leaving the device
asks, search included, because there the destination is beside the point. That is what
`docs/privacy-policy.md` and the Play data-safety table already describe, and both stay true.

What this trades away: after a page has been read, a search query can still carry text from
that page to the search provider without a prompt. Bounded by the query length cap, by the
provider being ours rather than the attacker's, and by every call being a row in the reply
naming its argument.

**`/yolo` waives both of the remaining checks**, and is a fourth `AgentMode` rather than a
setting. Typed, never persisted, named in the runtime line for as long as it is on, and gone
with the process. It does not switch tools on: the Tools screen is a decision made ahead of
time and a mode that reached into it would be answering a question the user already answered.
The Play data-safety row for Files and docs, the privacy policy and the store listing were all
written around the prompt being the only route out for a file, so all three now name the mode
as well. A declaration that holds only in the default mode is wrong for anyone who changed
the mode.

### Ordering or caching, and three engine faults (2026-08-15)

Full detail in `docs/research/tool-calling.md`. Measured on a fresh `pineapple` instance.

**It is the ordering.** Reversing the tool list changed the choice in 4 of 18 decisions;
asking the same question over a warm KV cache instead of a cold one changed 0 of 18. Gemma 3
1B in forward order picked `web_search`, the first tool listed, for all six cases including
the three that needed no tool. Its over-calling was position, not judgement, which is also why
four different system messages had made no difference to it.

**`llama_memory_seq_rm` returns false on recurrent and hybrid models, and we ignored it.**
A transformer's cache is a row per token and can be cut anywhere; a recurrent one carries a
running state and can only roll back as far as it kept snapshots for, which by default is not
at all. The engine rewound `n_past_` regardless, nothing was removed, and the next batch went
in after the tail that should have gone. `SustainedUseTest` reproduces the field error in
about ninety seconds against the previous code and is flat at 328 tokens against this one.
It was not a benchmark artefact: `n_past_` counts generated tokens and `cached_` does not, so
the rollback path is taken on **every follow-up turn of every conversation** on LFM2,
Granite-hybrid, Jamba and Nemotron-H.

**Stop during prefill left the cache inconsistent.** Batches that already decoded are in the
KV cache while the bookkeeping still describes the prefix, so the next turn finds nothing to
remove and appends after the orphans.

**The final pass no longer withdraws the tool definitions.** Withdrawing them moved the first
differing token to the tool block, which these templates put near the front, and re-prefilled
everything behind it: 257 tokens of a 578 token turn, growing with the conversation. The round
limit is a sentence at the tail now, and the withdrawal survives only for a model that asks
anyway, once.

### Coverage (2026-08-14)

`./gradlew koverLog` prints the totals, `koverHtmlReport` writes the detail. Host tier only,
so anything only a device can run reads as zero here even where a device test covers it.

| area | line coverage |
| --- | ---: |
| whole project | **27%** |
| `core/common/model` | 97% |
| `core/data` | 66% |
| `core/tools` | 49% |
| `ui/chat` | 32% |
| every Compose screen outside chat | 0% |

The aggregate is not the interesting number. Coverage of the turn loop and what it writes,
which is where this month's defects were, is high because the tests were written against
them one at a time:

| | |
| --- | ---: |
| `AgentRunner`, `ChatWriter`, `CompactionPolicy` | 100% |
| `TurnRunner` | 98% |
| `ConversationCompactor` | 97% |
| `ChatViewModel` | 91% |

The zeros are Compose: Discover, Dashboard, Settings, Models, and the design system. Chat is
the only screen with tests, and those run on a device. No threshold fails the build, on
purpose: a percentage over a Compose screen says more about how much of it is a lambda than
about whether it works, and a number that must be met is a number people write tests
against.

## Artifact sizes (2026-08-10)

| artifact | size |
|---|---:|
| Release AAB | **20.9 MB** |
| Release APK | **23.7 MB** |
| Debug APK | 231 MB |

The app ships **no model**. Weights are downloaded by the user, which is the whole point:
the catalogue is Hugging Face, not a list someone curated for them.

The release build carried 105 MB of unreadable symbol tables until 2026-08-10.
`libllama-common.so` alone was 65.3 MB and strips to 4.3 MB. The cause is worth
remembering: AGP's strip task needs the NDK to find `llvm-strip`, and a module that does
not compile C++ has no `ndkVersion`, so its strip task copies the libraries through
untouched. `:core:engine` stripped correctly and `:app` then repackaged the originals from
the merged output. `ndkVersion` is now set for every module in the convention plugin, not
just the one with CMake.

The debug APK stays large on purpose: debug builds keep symbols, skip R8, and are never
what a user installs.

For scale, the assistants we are measured against: ChatGPT 169.6 MB, Gemini 148.3 MB to
download and 212 MB installed, Claude 58.6 MB. All three are thin clients around a network
API. We are smaller than all of them while carrying an inference engine, seven CPU backend
variants and a GPU backend, because the part that is actually large is the model, and the
model is not ours to ship.

## Tool calling, and where llama.cpp stops helping

Prompts are now rendered by llama.cpp's `common_chat`, which knows how to write tool
definitions into each model family's own syntax. That is a large amount of per-model
knowledge to borrow rather than reimplement.

Its *parser* does not cover everything. LFM2.5 emits
`<|tool_call_start|>[get_weather(city='Manila')]<|tool_call_end|>`: Python call syntax,
not JSON, which llama.cpp's parser returns as ordinary prose. So the engine tries
llama.cpp first and falls back to `ToolCallParser`, a small unit-tested Kotlin parser that
recognises named formats and gives up on anything else. A parser that guesses at unknown
syntax produces confident nonsense; one that recognises formats it knows is safe to fall
back on.

Two traps found while wiring this up:

- `common_chat_params.prompt` **already ends with** whatever opens the assistant turn.
  `generation_prompt` is the same text kept separately for the parser. Appending it
  duplicates the turn header and the model then answers itself.
- A reasoning model needs a real token budget before it ever reaches a tool call. LFM2.5
  thinks for 50+ seconds; a 256-token budget truncates mid-thought and looks exactly like
  a parser bug.

## What on-device reasoning is for, and what tool wording is worth (2026-08-23)

Two suites were built and run off-device against the real GGUFs, because tool-calling
behaviour is a property of the weights and the prompt rather than of the phone. A 142 case
suite over a 14 tool registry, and a 48 case suite over this app's own eight tools with its
real system message. Prompts were verified byte identical to `llama-server /apply-template`,
grading is deterministic with no LLM judge, and a call written inside `<think>` is not
counted because the runtime never executes one. Both suites and the grader were reviewed
adversarially before any run, which is where the think-block hole was caught.

### Reasoning is load bearing for exactly one thing

The 2.6B template ends every generation prompt with `<|im_start|>assistant\n<think>`, so
thinking is not optional and `enable_thinking=false` is a no-op, which `Session::supports_thinking`
already detects. Forcing the block shut with a `</think>` prefill costs 15.5 points, and the
loss is not spread out:

| category | thinking on | thinking off |
|---|---|---|
| argument fidelity | 20/20 | 20/20 |
| tool selection | 14/16 | 15/16 |
| parallel calls | 12/12 | 12/12 |
| chaining | 8/8 | 8/8 |
| **no-call detection** | **17/18** | **5/18** |
| **missing argument** | **7/10** | **0/10** |

Every mechanical skill survives. Only judgement dies. Three separate prompt-side
interventions all land on the same ~73% floor, so abstention has to be computed and cannot
be instructed. About 470 characters of planning per turn, roughly 117 tokens, which at the
Poco's measured 16.8 tok/s is **seven seconds before the answer starts streaming**. That
wait is what the judgement costs.

### Tool wording is the strongest lever found, and it is model specific

Three rules, each traceable to the category it fixed: say what the tool is **not** for
(no-call 17/18 to 18/18), say what to do when a required argument is **missing** rather than
just naming it (missing argument 7/10 to 10/10, where a bare "requires X" made the model
invent X), and pair near-miss tools **contrastively** (selection 14/16 to 16/16).

| configuration | 142 case suite |
|---|---|
| 8B-A1B, plain discipline prompt | 138/142 |
| 2.6B QAD-Q4_0, rewritten descriptions | 135/142 |
| 8B-A1B, template default | 134/142 |
| 2.6B QAD-Q4_0, template default | 127/142 |
| 8B-A1B, **rewritten** descriptions | 127/142 |

The last row is the warning. Wording tuned on the 2.6B **costs the 8B 7.8 points**, with
out-of-scope handling falling 8/8 to 4/8, and does nothing at all on Q4_K_M. Changing the
default model means re-running the suite; the prompt work does not travel.

Statistically: 20 variants were scored against the 142 case suite and the winner was tuned
on it, so its p = 0.02 does not survive correction for the comparisons. What the claim rests
on is replication, +5.6 in sample and +6.2 then +4.2 on a different registry at greedy and
at production sampling.

### Measured dead ends, so nobody spends a day on them again

- **Quantised KV cache.** Only 8 of 30 layers attend, head_dim 64, so 16 KB per token.
  Q8_0 saves about 30 MB at 4K context. Not worth the backend risk.
- **The model's own sampling.** The GGUF ships `general.sampling.temp = 0.2` and nothing
  reads it, but 0.2 scores 69.4% against the app's 0.8 at 71.5%. The mismatch is real and
  is not costing anything.
- **Raising `DEFAULT_MAX_ROUNDS`.** Measured with a closed loop and real tool execution:
  9/10 tasks complete at a cap of 2, 4 and 6 alike. The one failure had budget to spare.
- **A grammar-gated micro-verification prefix.** 81.7% at 50 tokens, but the gate's own
  yes/no is right only 63% of the time. A slot to record a decision does not create the
  computation needed to reach it.
- **Logit bias on `<|tool_call_start|>`.** Swept -0.5 to -3.0, no effect at greedy.
- **A hard reasoning budget.** Free on the 48 case suite, costs 5.7 points on the 142 case
  one. The smaller suite has fewer hard abstention cases.
- **Routing through the 1.2B.** The cascade scores 37/48 against the 2.6B's 39. The 1.2B is
  not better at judging, it is quieter: d' of 1.05 against 2.41 for the 2.6B and 2.95 for
  the 8B, whose recall matches the 2.6B at half the false alarm rate.
- **Renaming `web_search`.** Over-calling redirected to `ask_user` rather than reducing.
  Forbidding one route makes the model find another; a "do not fetch" clause in a tool
  result made it invent `web_fetch_url`.

### Python in the sandbox is viable

MicroPython's `ports/embed` cross-compiles for Android arm64 with the NDK already installed,
unmodified: **292 KB**, with a caller-supplied heap via `mp_embed_init(heap, heap_size,
stack_top)` and a per-call teardown via `mp_embed_deinit`. So the integration is easy.

It should still not be done. Twelve code tasks, same tool, only the language named, and the
model's own programs executed:

| the model's code, run for real | correct |
|---|---|
| Python on CPython | 11/12 |
| JavaScript on node, the QuickJS analogue | 10/12 |
| **Python on real MicroPython** | **4/12** |

The model writes full Python and MicroPython is a subset: `ImportError` for `re` and `json`,
`SyntaxError: decimal numbers not supported` on float formatting, and syntax the subset does
not carry. Shipping it would take code execution from 10/12 to 4/12. Keep QuickJS. Full
CPython is ~15 MB plus a stdlib, which is a different conversation.

### What was actually missing was the loop, not the language

`run_script` required inline `source`, and `files` were read in as data rather than run, so
a program the model saved with `write_file` could never be executed. Authoring and running
are two turns and the second had no way to refer to the first. `run_script` now also takes
`path`, reading a saved `.js` file as the program, bounded at 16 KB because a program is
not a dataset. Measured: both halves of the loop fire, and on the fifty cases that predate
the change the score is unmoved at 38/50, two moving each way, which is greedy noise. No
unit test: `Sandbox` and `Workspace` are concrete classes over an Android `Context` and this
module has no mocking framework, so the verification is behavioural through the suite.

### Where the shipped configuration landed

Re-measured from the source as it stands, after every trim, on the app's own tools at
greedy: **40/48 against 35/48** at the start of the work, six cases fixed and one lost. At
production sampling over three seeds it is 75.7% against 71.5%, with no-call detection
18/36 to 24/36 and the searches that should happen still 18/18. The trimming done to fit
the catalogue budget cost nothing: the last 177 characters removed gained two cases rather
than losing any, which is the second time wording that read as load bearing turned out not
to be. Nothing here is significant once the twenty variants tried are accounted for; the
case rests on the same direction in three suites and two sampling regimes.

### Still unmeasured

Thread pinning, the 8B-A1B's real decode rate at 4.8 GB resident, and wall-clock
confirmation of the ratios above. `llama-bench` already accepts `--cpu-mask`, `--cpu-strict`
and `--prio`, so pinning needs no app change to test. All three run from one script once a
device is reachable.

**All three were measured on 2026-08-23. See the section below.**

## The phone answered, and it moved the prefill thread count (2026-08-23)

A Snapdragon 8 Gen 3 came back on the cloud rack, so the questions left open above are no
longer arithmetic. Everything below is `llama-bench` from the flags the app ships
(`GGML_CPU_ALL_VARIANTS`, `GGML_BACKEND_DL`, `GGML_CPU_KLEIDIAI`, `-O3`), LFM2.5 2.6B QAD
Q4_0, and **every reading was started with the hottest thermal zone below 56 C**, which is
6 C over this device's idle floor. The first setting of each sweep is repeated last, so
drift is visible rather than assumed.

The chip, read off the device rather than off a spec sheet: `ro.soc.model` SM8650, eight
cores, `CPU part` 0xd80 twice at 2.27 GHz (Cortex-A520), 0xd81 five times at 2.96 GHz
(A720), 0xd82 once at 3.19 GHz (X4). `/proc/cpuinfo` Features carries `i8mm` and `bf16` and
carries **no `sve` and no `sve2`**, so ggml loads `libggml-cpu-android_armv8.6_1.so` and the
two Armv9 variants in the APK are correctly declined. That is the right choice: i8mm is what
KleidiAI's Q4_0 microkernels use, and QAD Q4_0 is what the recommended checkpoints ship.
Backend variant dispatch, in other words, is already doing its job and needs nothing.

### Prompt processing was being given two cores that slow it down

`ThermalPolicy.plan()` handed prompt processing every core the phone reports. On a chip whose
cores are not all the same core, that costs a quarter of the throughput.

| threads | prefill, pp512 |
| ---: | ---: |
| 3 | 91.17 |
| 4 | 104.90 |
| 5 | 121.70 |
| **6** | **138.98** |
| 7 | 125.59 |
| 8 | 105.19 |

Bracket: the same six thread setting read 142.35 at the start of the sweep and 137.32 at the
end, so 3.5% of drift sits under a 32% spread. The ordering is not in doubt.

The mechanism is the barrier at the end of every operation. ggml gives each thread an equal
slice and the step costs whatever the slowest thread costs, so adding an A520 does not add
its throughput, it imposes its latency on the other seven.

**Pinning is not the fix, and this is the reading that says so.** Six threads restricted to
cores 2 to 7 with `--cpu-strict 1` gave 138.81 against 137.32 to 142.35 unpinned: no
difference worth a threadpool. Given six busy threads the Linux scheduler already puts them
on the six fast cores. What it cannot do is refuse the seventh and eighth, and that is the
whole of the problem. Pinning does rescue a count that is already wrong, eight threads pinned
across all eight cores gave 122.04 against 105.19 unpinned, but it never reaches what simply
asking for six does. The caveat is that `llama-bench` had the device to itself; an app also
running a UI is a case this cannot see, and if scheduler contention ever shows up in a real
turn, affinity is the tool for it.

So `CpuTopology` reads `cpuinfo_max_freq` per core and **drops the slowest cluster when at
least half the cores are left without it**: six here, eight on the all big core MT6991 where
eight was already measured fastest, four on the commonest 4 + 4 Android phone, and all eight
on a 2 + 6 phone where two cores cannot carry the work. The threshold falls at half because
that is where `k` times the speed of the k-th fastest core stops improving, given a little
core doing about half a big core's work per step.

`cpuinfo_max_freq` rather than `scaling_max_freq`: the second moves with heat and with
whatever else is running, and a thread count that changes because a background app woke up is
not a thread count. Any core that will not answer discards the whole reading and every core
is used, because a partial table makes a heterogeneous chip look uniform exactly when the
refused cores are the ones that differ. Whether an ordinary app process can read that file at
all is a question an adb shell cannot answer, so `CpuTopologyOnDeviceTest` asks it from
inside one.

### Decode was already right

| threads | decode, tg128 |
| ---: | ---: |
| 2 | 17.26 |
| 3 | 22.68 |
| **4** | **24.95, 24.98, 24.99** |
| 5 | 25.97 |
| 6 | 22.93 |

Four is what `cores / 2` picks here and it is inside the noise of the best reading. Five is
one standard deviation away and costs a core's worth of power for it. Pinning again did
nothing: 24.71 on cores 4 to 7, 23.90 on cores 2 to 5, against 24.99 unpinned.

Seven and eight are not in the table because they are nowhere near the answer and they are
expensive to measure. In an earlier uncooled pass seven gave 11.11 and eight did not finish
128 tokens in five minutes, with the big cores clocked down to 1.7 GHz at 72 C while the two
A520s held their full 2.27. That is the same barrier seen from the other side.

### OpenCL on the Adreno 750 is not worth having for this model

The note above from 2026-08-17 says both recommended models have no crossover on this chip,
that the GPU is faster at reading and at writing, and that `CROSSOVER_NUMERATOR = 10`
therefore sends them to the wrong processor on every load. **That is no longer true of what
ships.** Two things changed under it: the recommended checkpoints moved from Q4_K_M to QAD
Q4_0, and the measurement now includes a context that is not empty.

One binary, built with both backends, so every row below comes from the same process image.
Six threads for CPU prefill and four for CPU decode, which is what the sweeps above chose.

| shape | GPU, all layers | CPU |
| --- | ---: | ---: |
| prefill 64, empty context | 159.2 (sd 16.1) | 152.5 (sd 7.0) |
| prefill 128, empty context | **223.9** (sd 6.8) | 145.7 (sd 2.1) |
| prefill 512, empty context | 144.8 (sd 49.3) | **139.5** (sd 1.1) |
| prefill 512, depth 1024 | 82.9 | **122.1** |
| prefill 512, depth 4096 | 66.1 | **91.4** |
| decode, empty context | 21.7 | **25.0** |
| decode, depth 1024 | 8.2 | **23.3** |
| decode, depth 4096 | 4.5 | **19.4** |

**The one thing the GPU wins is a burst that a real prompt exhausts.** A 128 token batch into
an empty context runs at 223.9 and does it stably. Stretch the same work to a 512 token batch
and it falls to 144.8 with a standard deviation of 49.3 on the mean, level with the CPU and no
longer a measurement of anything steady. The first prefill of the whole session read 264.79,
and the same setting gave 144.54 at the end of that run and 144.77 on a cooled rerun. A
128 token batch takes about half a second and a 512 token batch about three and a half, which
is the difference between staying in the Adreno's boost state and stepping out of it. It is
not kernel compilation, which would have made the first reading slower rather than faster.

Everything else is one-sided. With a thousand tokens of context, which is where every real
turn of this app starts because the system message and eight tool definitions are about that
size before the user has typed anything, the GPU reads at 68% of the CPU's rate and writes at
35% of it. At four thousand tokens it writes at 23%.

One more reading makes the shape of that collapse plain without any depth argument at all.
Asked for 256 tokens instead of 128, from the same empty context, the GPU falls from 21.67 to
**8.15** while the CPU holds at 24.37. The context it is decaying against is the one it is
writing itself.

Three controls, so the decode collapse is not left as a mystery. Flash attention made it worse
rather than better (4.98 with `-fa on` against 9.03 with `-fa off` at depth 1024), and a q8_0
KV cache made it worse again (5.01), so the `auto` default is already choosing correctly and
there is no missing kernel to switch on.

**This is a fact about llama.cpp's OpenCL backend on Adreno, not about the silicon.** The
Adreno 750 is not short of bandwidth or of integer throughput. What happened is that the CPU
side received a vendor's hand written microkernels for exactly the format the app ships, and
the GPU side did not: KleidiAI's i8mm path lifted CPU decode from the 14.4 t/s measured on
Q4_K_M to 25.0 t/s on QAD Q4_0, while the GPU went from 15.3 to 21.7 and lost the race it
used to win. A crossover measured on Q4_K_M does not describe a Q4_0 model, and the 2026-08-17
table should be read as being about Q4_K_M rather than about this app.

**So the conservative default was right, and for a reason nobody had measured.**
`Offload.AUTO` keeps its constant. Solving for the crossover at an empty context gives 1.80,
so even the most favourable shape needs a turn that reads nearly twice what it writes; at
depth 1024 there is no crossover at all in the GPU's favour, because it is behind on both
halves.

The two CPU columns were taken through the OpenCL build at `-ngl 0` and reproduce the CPU
only build to within one percent (139.48 against 138.98 prefill, 24.98 against 24.98 decode),
which is what says the two halves of the comparison are comparable.

**And it is not a fact about one model.** All three recommended models, same binary, same
session:

| model | prefill CPU | prefill GPU | decode CPU | decode GPU |
| --- | ---: | ---: | ---: | ---: |
| LFM2.5 1.2B QAD Q4_0 | **323.7** | 209.3 | **54.2** | 15.9 |
| LFM2.5 2.6B QAD Q4_0 | 139.5 | 144.8 | **25.0** | 21.7 |
| LFM2.5 8B-A1B Q4_K_M | **76.3** | 56.0 | **24.8** | 5.2 |

The CPU wins every cell but one, and that one is a tie. The 1.2B is the clearest of the
three and the one that most directly overturns the earlier table: on Q4_K_M it read 117.4 on the CPU and 202.9 on the GPU, and on QAD Q4_0 it reads
323.7 on the CPU and 209.3 on the GPU. Decode went the same way, 28.8 against 31.2 before and
54.2 against 15.9 now. Nothing about the GPU changed. The CPU got vendor microkernels for the
format the app downloads.

### The three recommended models, timed on the same phone in one session

The 8B-A1B went on the recommended list on a routing score, with the mixture-of-experts
claim taken on trust: thirty-two experts with four used per token, so a phone should pay
roughly a 1B model's arithmetic for an 8B model's judgement. That had never been timed.

Six threads for prefill, four for decode, except where noted. The device had been
benchmarking for hours, so these are hot readings and belong beside each other rather than
beside the cooled sweeps earlier in this section.

| model | on disk | prefill, pp512 | decode, tg128 |
| --- | ---: | ---: | ---: |
| LFM2.5 1.2B, QAD Q4_0 | 661 MiB | 323.7 | 54.2 |
| LFM2.5 2.6B, QAD Q4_0 | 1.48 GiB | 138.98 (cooled) | 25.0 |
| LFM2.5 8B-A1B, Q4_K_M | 4.79 GiB | 76.3 | **24.8** at six threads |

**The mixture of experts delivers what the listing claims.** An 8.47 billion parameter model
writes at the same rate as a 2.7 billion parameter dense one, 24.8 against 25.0, which is
what four active experts predicts and is the entire reason it is worth 4.8 GB of a phone.
Reading is slower, 76.3 against 139, because a prefill batch touches every expert.

Two things about it that the listing should carry and did not.

**It wants six decode threads, not four.** At four it read 17.03 with a standard deviation of
17.97, which is two repetitions that disagreed by a factor of several: the first faults 4.8 GB
of weights in from storage while `MemFree` sits at 74 MB, and the second runs warm. At six it
settles at 24.79. The app's `cores / 2` gives four. For a dense model that is right; for this
one it is not, and nothing in the current heuristic knows the difference.

**It is a memory decision before it is a speed decision.** Resident, it leaves this
eleven-and-a-half gigabyte phone with under a hundred megabytes free, and the fit card is the
only thing standing between a user and a load that gets the app killed.

### One real turn, end to end, which is the number a person feels

Throughput tables are not seconds. So: the exact system message `ChatViewModel` builds today,
the eight shipped tool definitions serialised the way `common_chat_tools_to_json_oaicompat`
serialises them, one ordinary question, greedy, 128 tokens of answer, through
`llama-completion` on the device. **The prompt is 1,238 tokens before the user has typed
anything**, which is the fact the rest of this rests on.

| | prefill | prefill rate | whole turn |
| --- | ---: | ---: | ---: |
| CPU, 8 batch threads, what shipped | 13.11 s | 94.5 t/s | 18.50 s |
| CPU, 8 batch threads, repeat | 13.52 s | 91.6 t/s | 18.83 s |
| **CPU, 6 batch threads, the change** | **9.19 s** | **134.8 t/s** | **14.54 s** |
| GPU, all layers | 6.04 s | 204.9 t/s | 23.00 s |

**Four seconds off the first token of every new conversation**, and four seconds off the whole
turn, from one line that stops handing the batch two A520s. The two readings of the shipped
setting bracket each other to within 2%, so this is the change and not the weather.

And the GPU row is the whole GPU argument in one line. It reads the prompt fastest of
anything here, three seconds quicker than the best CPU configuration, and then spends
seventeen seconds writing 127 tokens at 7.5 a second and finishes eight and a half seconds
behind. A turn would have to ask for almost no answer at all before that trade paid.

### Four knobs the engine hard-codes, and none of them was wrong

`engine_session.cpp` fixes `n_batch` and `n_ubatch` at 512, leaves flash attention at `auto`
and the KV cache at f16, and none of those had been measured on a chip like this. All four
were swept. The device had been benchmarking for hours by this point and its idle floor had
risen from 50 C to about 60 C, so these readings are lower than the cooled ones earlier in
this section and are only compared with each other.

**Logical batch size.** A 1,024 token prompt at six threads: 128.0 t/s with the shipped
`n_batch = 512`, which puts it in as two calls, against 122.0 with `n_batch = 2048`, which
puts it in as one. Fewer, larger calls are not faster here, and the smaller one keeps peak
compute-buffer memory down, which is what it was chosen for.

**Micro-batch size**, which is what actually decides the shape of the matrix multiply:

| n_ubatch | pp1024 |
| ---: | ---: |
| 128 | 97.8 |
| 256 | 98.7 |
| 512 | 96.6 |
| 1024 | 95.9 |

Flat. There is nothing here, and 512 stays for the memory it does not use.

**Flash attention.** `pp512` reads 99.5 with `-fa on` against 96.0 with `-fa off`, and
`tg128` at depth 4,096 reads 15.82 against 15.91, which is the same number twice. So it
helps prefill slightly, does nothing to decode, and `auto` is already turning it on. On the
Adreno the same control goes the other way and turning it on is a mistake, which is recorded
in the OpenCL section above. Both of those are arguments for leaving `auto` alone.

**KV cache type.** At depth 8,192, where the cache is finally large enough for its type to
matter, f16 decodes at 13.02 t/s and q8_0 at 11.51. Quantising it is 12% slower and buys
memory that `ContextWindows` already showed is allocated lazily and never touched. f16 stays.

**A long answer, which the peak figure does not describe.** 512 tokens in one run, no
cooling: 20.01 t/s at four threads and 22.04 at five. The cooled 128 token sweep put five
marginally ahead too, 25.97 against 24.98. Two readings both favouring five by 4 to 10% is
suggestive and it is not enough: the long run is a single repetition, the short one is inside
one standard deviation, and `cores / 2` gives four on the MT6991 where four was measured
fastest. Left at four, written down as the next small thing to bracket properly.

**Does the thread count move with the prompt length?** No, and this is the check that says
the six thread finding is not an artefact of one prompt size:

| prompt | 6 threads | 8 threads |
| ---: | ---: | ---: |
| 128 | 99.1 | 90.5 |
| 512 | 138.98 (cooled) | 105.19 (cooled) |
| 1,024 | 128.0 | not run |
| 2,048 | 92.7 | 86.7 |

Six wins at every length measured. The margin is smaller in the hot readings than in the
cooled ones, which is the throttle compressing the difference rather than the difference
going away.

#### The thermal back-off is calibrated on the wrong chip

`ThermalPolicy`'s docstring rests on a measurement from the dev device: cold, prefill scaled
to eight threads at 69.5 t/s; after a long run the same benchmark gave **73.6 t/s at two
threads and 28.0 at eight**. Fewer threads win once the big cores are throttled, so the
policy cuts hard as the phone warms.

That is not what this chip does. The same sweep run with no cooling between conditions, on a
device sitting at 62 to 74 C after hours of benchmarking:

| threads | prefill, hot |
| ---: | ---: |
| 2 | 43.42 |
| 3 | 51.91 |
| 4 | 69.50 |
| **6** | **98.90** |
| 8 | 90.28 |

**Six still wins, and two is 2.3 times slower than six.** Backing off to `MIN_THREADS = 2`
here would not protect throughput, it would halve it twice over, and it would do it at the
moment the user is already waiting longest.

Two caveats keep this from being a licence to delete the back-off. This sweep was hot but it
was not throttled by Android: the policy fires on `PowerManager.currentThermalStatus`, and a
rack unit with real cooling may never report `THERMAL_STATUS_LIGHT` at all, so nothing here
describes the state the policy actually acts in. And a phone in a hand is a different thermal
system from a phone in a rack.

What did change is an accident this session introduced. `LIGHT` halved the batch count, which
was written when that count was the core count; once `CpuTopology` started answering six it
began asking for three, a number the table above puts at 52% of six. It now backs off to the
generate count instead, which is four on both phones measured and is exactly what `LIGHT` gave
before any of this. The premise itself is left alone and is in the open questions.

### Every follow-up turn was re-reading the whole conversation

This is the largest thing on the list and it had been invisible, because a slow second turn
looks exactly like a slow model.

`Session::generate` compares the freshly rendered prompt against the tokens already in the
KV cache and decodes only what is new. On an SM8650, with LFM2.5 2.6B and a system message
the size of the real one, it was decoding all of it, every turn:

```
turn 1: prompt=1159 tokens prefill=8633ms
turn 2: prompt=1220 tokens prefill=9568ms      <- the whole conversation, again
```

`PrefixReuseOnDeviceTest` prints the comparison that explains it. The cache held 1,204
tokens, the new prompt was 1,220, and 1,158 of them matched. Here are the two sequences at
the token where they stop:

```
cached: [\n][<|im_start|>][assistant][\n][<think>][The][ user][ is][ asking]
prompt: [\n][<|im_start|>][assistant][\n][The][ user][ is][ asking][ about]
```

**One token.** LFM2.5's template pre-opens the thinking block in the generation prompt, so
what the engine decoded for turn one ends `<|im_start|>assistant\n<think>` and then the
reply. When turn two re-renders the same conversation that reply is a history message, and
the template drops thinking from history unless it is told otherwise, so the `<think>` is
simply not there. The prefix diverges at the head of the assistant turn.

**And one token cost all 1,158 of them**, because of the second half of the mechanism. The
engine asks `llama_memory_seq_rm` to roll the cache back to the divergence. A transformer
cache can be cut anywhere; LFM2 is a hybrid and carries a running convolution state rather
than a row per token, so it refuses, and the engine correctly starts over rather than
decode against a cache that no longer describes the prompt. A one token mismatch and a
total loss are the same event here.

Three things had to agree before the cache could be kept, and none of them did:

1. **The reply that goes back as history has to be the reply that was decoded.**
   `parseAssistantReply` trims the thinking and drops a leading newline from the answer,
   for the screen, and `canonicalText` reassembles those trimmed parts with the tool syntax
   already lifted out. Every one of those is right for the transcript and wrong for the
   cache. `assistantHistoryText` now keeps the raw stream, and `TranscriptEntry.history`
   carries it for as long as the process lives. It is not persisted: a chat reopened from
   storage has an empty cache anyway, so there is nothing for it to match.
2. **The opening tag has to be put back.** It was in the prompt rather than in the output,
   so the reply arrives with a closing tag and no opening one. That is exactly the case
   `parseAssistantReply` already recognises, and it is what tells `assistantHistoryText`
   when to prepend one.
3. **The template has to be willing to render it.** LFM2.5's reads
   `message.thinking or message.reasoning or message.reasoning_content` and emits it only
   when `preserve_thinking` is set or the message is after the last user turn. So
   `render_prompt` splits the leading `<think>...</think>` into `common_chat_msg::
   reasoning_content` and passes `preserve_thinking` as a template argument. Templates
   that do not know the argument ignore it.

Measured after, same device, same conversation, same test:

| | turn 2 prompt | turn 2 prefill |
| --- | ---: | ---: |
| before | 1,220 tokens | 9,568 ms |
| **after** | **17 tokens** | **189 ms** |

**Fifty times faster to the first token of a follow-up**, and the 17 tokens are the new
question and its turn markers, which is exactly what should have been paid all along.

Two things this costs, stated rather than buried. The thinking now stays in the context, so
a conversation reaches the compaction ceiling sooner; it was always in the KV cache, so it
costs window rather than time. And the model sees its own earlier reasoning on later turns,
which is not what a template that drops it assumes. That is a change to the prompt and it
was measured rather than argued: see the loop task comparison below.

#### The same thing was happening inside every tool turn, and it is worth more

A turn that calls a tool runs the model two to four times, and each round re-renders the
conversation so far. `TurnRunner` put the asking turn back as `pass.raw.withoutReasoning()`,
which strips the thinking the model produced and which the cache therefore holds, so every
round after the first paid a full prefill of a conversation that was getting longer.

`ToolLoopReuseOnDeviceTest` measures both forms against each other through the shipping
engine, one loaded model, one session:

| second round of a tool turn | prompt decoded | prefill |
| --- | ---: | ---: |
| the asking turn with its thinking stripped | 1,222 tokens | 9,586 ms |
| **the asking turn as it was decoded** | **48 tokens** | **488 ms** |

Forty-eight tokens is the tool result and its turn markers, which is all that is new. The
round budget is two by default and four when a chaining tool is present, so on this device
the stripping was costing somewhere between nine and twenty-eight seconds of every agentic
turn.

The comment that forbade sending it whole was right when it was written and is not any more.
It said that replaying a literal `<think>` block into a template that opens one itself is
nonsense the model tries to continue, which is what happens when the tags arrive as content.
With `reasoning_content` and `preserve_thinking` the template renders a proper block instead,
and the closed loop suite says completion does not move: 9 of 10 either way, the same task
failing. What it does cost is length, 44% more generated tokens, which at 23 tokens a second
is about six seconds a task against the nine a round this buys back.

**One gap is left.** The transcript entry for a tool turn keeps only the final answer, so the
turn *after* one still re-renders a single assistant message where the cache holds four or
five. Closing that means the entry carrying the whole loop rather than its last pass, which
is a change to the transcript model and not only to what is sent, and no measurement here
covers what the model does when it sees a previous turn's tool calls and results.

The diagnosis generalises past this one tag. Any divergence between the decoded tokens and
the re-rendered ones costs the whole cache on a hybrid model, so a tool result that is
reformatted, an answer that is cleaned up, or a template that renders a turn differently in
history than it did live will all do the same thing. `llama.cpp` has a bounded escape hatch
for this, `llama_context_params::n_rs_seq`, which keeps N per-token snapshots of the
recurrent state and makes a rollback of up to N tokens succeed; `llm_arch_supports_rs_rollback`
lists LFM2 and LFM2MOE among the architectures that support it. It is marked experimental
upstream and it is not free: at 255 snapshots with a 512 micro-batch, the graph allocator
aborted the process on this device with `ggml_new_object: not enough space in the context's
memory pool`. Left alone for now, and worth revisiting as a safety net rather than as the
fix.

#### What keeping the thinking costs, measured rather than assumed

Sending a reply back whole is a change to the prompt, and the prompt is what the earlier
work in this document spent a week on. So it was run through the closed loop tasks, which
are the only suite here that generates its own history instead of being handed a scripted
one: ten tasks against the app's real eight tools, a fake filesystem and web, greedy, round
cap four.

The comparison is deliberately harsher than what ships. In "kept" the model's thinking goes
back on **every** assistant turn including the ones inside a single tool loop, which the app
does not do: `TurnRunner` still strips reasoning from within-turn messages, for the reason
written above the line that does it. So this is an upper bound on the change.

| | completed | rounds used | tokens generated |
| --- | ---: | ---: | ---: |
| thinking dropped, as before | 9 / 10 | 24 | 3,202 |
| thinking kept, upper bound | 9 / 10 | 23 | 4,620 |

**Completion does not move**, and the one task that fails, `L4-budget-total`, fails both
ways for the same reason. Nine of the ten final answers are worded differently, which is
what changing a prompt does.

**What does move is length: 44% more tokens generated.** Two tasks account for most of it,
`L8-missing-then-ask` at 462 to 1,339 and `L10-no-tool` at 523 to 1,059, both of them cases
where the model has seen itself reasoning and then reasons about that. At 23 tokens a second
that is real time, and it is the reason the within-turn half of this was left alone rather
than shipped on the same evidence: the cross-turn case was measured on the device and is
worth four to nine seconds a turn, the within-turn case has only this harness behind it, and
the comment in `TurnRunner` that forbids it records a failure someone actually saw.

### A prompt cache on disk would take the first turn from twelve seconds to under two

The fix above makes the *second* turn nearly free. The first one still pays for 1,238 tokens
of system message and tool definitions, and so does every new chat, and so does every cold
start. llama.cpp can already serialise a sequence's KV cache to a file
(`llama_state_seq_save_file` / `llama_state_load_file`); the engine uses neither, and
`llama-completion` exposes the same thing as `--prompt-cache`, which is what makes this
measurable without writing the feature first.

Same device, same prompt, 32 tokens of answer:

| | prefill | whole turn |
| --- | ---: | ---: |
| cold, no cache | 10.74 s | 12.29 s |
| the run that writes the cache | 12.21 s | 14.02 s |
| **reading the cache** | **0.00 s** | **1.68 s** |
| reading it again | 0.00 s | 1.68 s |

The cache file is **20.6 MB** for 1,238 tokens, which is 16.7 KB a token and agrees with the
20 KB a token this model's geometry predicts. Writing it costs about a second and a half on
the turn that does it.

So the ceiling on a shipped version of this is a first turn that answers in under two
seconds instead of twelve. What it needs to be correct rather than fast is invalidation: the
cache is only valid for an exact token prefix, so it is keyed on the model, the context
length, and the rendered system message, and the system message contains today's date, which
means it is rebuilt daily and whenever a tool is switched on or off. Twenty megabytes for one
slot is affordable; a slot per conversation is not.

Not implemented here. Measured, sized, and left with the number that says whether it is worth
writing.

### Energy per token could not be measured on this device

Throughput hides the axis a phone actually lives on, so the CPU and GPU runs above were
repeated with `/sys/class/power_supply/battery/current_now` sampled once a second. The
readings are not usable and it is worth writing down why rather than quietly dropping them.
The rack unit reports `status = Discharging` at `capacity = 100`, which is a tethered device
with charging suspended rather than one running off its battery; the sampled mean during a
256 token decode came out **below** the idle mean, 3,631 against 4,150 in whatever units this
kernel is using, and the node's own resolution moves in steps of several seconds. Joules per
token needs a phone that is genuinely on battery, and this is not one.

It stays on the list because it is the one argument that could still favour the GPU. Losing
by 15% on decode while drawing half the power would be a different trade from losing by 15%
at the same power, and nothing here can tell those apart.

### llama.cpp has an NPU backend for this exact chip and the app does not build it

"Are we fully leveraging llama.cpp" has one clear answer and it is no. The vendored tree
carries `ggml/src/ggml-hexagon`, a Qualcomm Hexagon (HTP) backend, with `docs/backend/snapdragon`
and prebuilt-per-architecture kernel libraries `libggml-htp-v73/75/79/81.so`. `GGML_HEXAGON`
defaults to OFF and `core/engine/build.gradle.kts` does not turn it on.

What is already in our favour:

- **The ops are there.** `ggml_backend_hexagon_device_supports_op` covers `MUL_MAT`,
  `MUL_MAT_ID`, `FLASH_ATTN_EXT`, `ROPE`, `SOFT_MAX`, `RMS_NORM`, `GLU`, and `SSM_CONV`,
  which is the one that matters: LFM2's short convolution block is built on `ggml_ssm_conv`
  (`src/models/lfm2.cpp`), so the recommended architecture is not excluded by construction.
- **The quantisation is there.** The backend repacks Q4_0, Q8_0 and MXFP4, and the
  recommended checkpoints ship QAD Q4_0.
- **The size fits.** An HTP session maps about 3.5 GB, and the models this app recommends
  are 0.7 to 1.6 GB. Only the 8B-A1B at 4.8 GB would need the multi-session split.
- **Upstream's own numbers are not small.** Llama 3.2 1B Q4_0 on a v79 HTP: 136 t/s prefill
  and **51.6 t/s decode**. This device's CPU does 25.0 t/s decode on a 2.6B.

What stands in the way, honestly:

- It needs the **Hexagon SDK** to build, which upstream distributes through a Docker image
  (`ghcr.io/snapdragon-toolchain/arm64-android`) rather than as a package. That is a
  reproducibility question for an open source Android build, not merely a setup step.
- It is marked **experimental** upstream.
- Nothing here has run it. Every number above is upstream's or is read off the source.

This is the largest unexplored lever in the product and it is the honest answer to "can we
use a better engine": the better engine is the one already vendored, with a backend switched
off.

## Open questions

Ordered by what a measurement here would be worth, largest first.

- **The Hexagon NPU backend**, which is vendored and switched off. See the section above.
  Upstream reports 51.6 tokens a second of decode on a 1B where this device's CPU does 25.0
  on a 2.6B. The blockers are the Hexagon SDK as a build dependency and an experimental
  label, not the model architecture, whose one unusual operation the backend implements.
- **A prompt cache on disk.** Measured at twelve seconds to 1.7 for a first turn, 20.6 MB
  a slot, needs an invalidation key and nothing else that is hard.
- **Carrying a tool turn's whole loop into the next turn's history.** Within a turn this is
  done and is worth nine seconds a round; across turns the transcript entry still keeps only
  the final answer, so the turn after an agentic one re-reads everything. Needs the entry to
  hold the loop rather than its last pass, and needs someone to check what a model does when
  it sees a previous turn's tool calls and results, which nothing here measured.
- **Per-device rates for `Offload.AUTO`.** The crossover has now been measured at 1.80 for
  the shipped model and quantisation on an Adreno 750, against a compiled-in 10, and against
  "no crossover exists" for the same model in Q4_K_M. One constant cannot cover that. The
  probe is cheap: sixteen decoded tokens on each backend after a warm-up, which decides the
  sign, and only a negative sign needs the full ratio.
- **Whether the thermal back-off helps or hurts.** Measured hot on an SM8650, fewer threads
  are strictly worse, which is the opposite of what the MT6991 reading the policy was built
  on says. Neither reading was taken in the state the policy actually fires in, because
  `THERMAL_STATUS_LIGHT` needs a phone that is genuinely throttling and a rack unit is not.
  Needs a device in a hand.
- **Five decode threads rather than four**, worth 4 to 10% on this chip in two readings that
  are individually too weak to act on. One bracketed sweep would settle it.
- Whether inference needs a foreground service (currently foreground-app only, to avoid
  Play's `specialUse` review path).
- Over-calling on general knowledge: the 2.6B still searches for the capital of Peru, and
  eleven interventions moved it once. It looks like a disposition of the checkpoint rather
  than a wording that has not been found. Reproduced again on the device on 2026-08-23:
  asked for the population of Tokyo it thinks for one paragraph and then calls `web_search`.

Closed by the 2026-08-23 device session: thread pinning (measured, no effect, the count was
the whole problem), OpenCL offload for the recommended models on an Adreno 750 (measured,
not worth having), micro-batch size (measured, flat from 128 to 1024), logical batch size
(measured, 512 is right), flash attention (measured, `auto` is already choosing right on both
backends), a quantised KV cache (measured, 12% slower and buys nothing), and whether an
ordinary app process can read `cpuinfo_max_freq` (it can, `CpuTopologyOnDeviceTest` says so
from inside one).
