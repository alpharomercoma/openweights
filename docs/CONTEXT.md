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
- [ ] **Tool calling** — the foundation for the agent loop
- [~] **P3** Product: conversations and the usage ledger persist in Room, Usage dashboard
      built; per-model hyperparameter editing and a conversation list are still to come
- [ ] **P4** Multimodal: libmtmd vision/audio, dictation, TTS
- [ ] **P5** Play production: API 36 audit, 16 KB check, AAB, data safety, security review

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

## Open questions

- GPU offload: Vulkan on this GPU is expected to be slower than the tuned CPU path;
  OpenCL on the Snapdragon device is the more promising experiment. Revisit after P2.
- Whether inference needs a foreground service (currently foreground-app only, to avoid
  Play's `specialUse` review path).
- Thread defaults are calibrated on one device; the P2 benchmark should measure and store
  per-device values rather than relying on the core-count heuristic.
