<div align="center">

![](play/graphics/readme-logo.png)

# OpenWeights

**Run open-weight AI models on your phone.**<br>
No account, no cloud, no telemetry.

[![License](https://img.shields.io/badge/license-Apache--2.0-052B42?style=flat-square)](LICENSE)
[![Android](https://img.shields.io/badge/Android-12%2B%20·%20arm64--v8a-052B42?style=flat-square)](#requirements)
[![Engines](https://img.shields.io/badge/engines-llama.cpp%20·%20ExecuTorch-052B42?style=flat-square)](docs/research/inference-engines.md)

</div>

Search Hugging Face from inside the app, find out whether a model will actually fit *your*
device before you spend the download, and chat with it. Conversations you can branch, edit
and fold when the context runs tight; image, audio and video input; an assistant that can
search the web, show pictures, read a page, run a sandboxed script, work in a folder you
share, build a live website, document or slide deck on a canvas, watch something on a
schedule, remember facts across conversations, or plan its steps first and let you tick
them off. Every token is produced by your own hardware, through
[llama.cpp](https://github.com/ggml-org/llama.cpp) for GGUF models and
[ExecuTorch](https://github.com/pytorch/executorch) for compiled `.pte` models.

> **Status: v2, preparing for Play.** [`docs/CONTEXT.md`](docs/CONTEXT.md) is the living
> record of what works, what was measured, and what is still wrong.

## Why

Every other on-device app hands you a catalogue somebody else chose. OpenWeights hands you
the Hub: browse GGUF and ExecuTorch repositories, inspect fit, and run supported
architectures locally without a vendor pipeline or a first-party catalogue.

## What it does differently

**Browse the Hub, not a fixed catalogue.** The Hub search is the model list, across both
runtimes. Most of what is on there is far too large for a phone, and some architectures are
not supported, which is why the fit check is shown before download.

**Honest about your device.** Before downloading, the app reads the GGUF header over HTTP
range requests and says what the file needs at your context length, roughly how fast it will
run, and whether it will run at all. The same parser then sizes the context window it opens
with, so the number promised before the download is the number you get after it.

**Real numbers, in front of you.** Tokens per second, time to first token, cache hit rate
and context fill are on screen while you chat rather than hidden.

**Fast to first token, on purpose.** The instructions and tool definitions are read into
the KV cache while you type, snapshotted, and persisted to disk, so a fresh chat answers in
about a second where it used to pay a twenty-second prefill — measured and written up in
[`docs/research/first-turn-latency.md`](docs/research/first-turn-latency.md).

**Yours to tune.** Temperature, top-p, top-k, repeat penalty, context length, the system
prompt and what the model is told about its tools, saved per model. Where a phone has a
working GPU you can say which processor holds the layers.

**Multimodal.** Image and audio input through llama.cpp's `libmtmd` for models that ship an
`mmproj` projector; video arrives as sampled frames. Dictation and spoken replies use the
phone's own on-device services.

**An agent, within limits you set.** Tools run under a mode you choose per turn: ask first,
run automatically, plan only, or everything without prompts. `/goal` works through a task
on its own; `/deep-research` searches and writes up what it finds with sources. Every tool
call is a row in the reply naming what it was given, and tools that carry anything out of
the device sit behind their own switches.

**Private by construction.** No accounts, no analytics, no crash reporter, and backups off
the device are disabled. Your Hugging Face token is encrypted with a hardware-backed
Keystore key and goes only to `huggingface.co`.

Some things do reach the internet on your behalf and it is worth saying rather than
implying: searching and downloading models, and the assistant's `web_search`,
`show_pictures` and `fetch_url` tools. They ship switched on, they sit under a heading that
says they leave the device, each one can be switched off, and every call is a row in the
reply naming what it was given. [`docs/privacy-policy.md`](docs/privacy-policy.md) says
exactly what goes and when.

## Requirements

- Android 12 (API 31) or newer, `arm64-v8a`
- Enough memory for the model you pick, which the app tells you before you download

## Build

```sh
export JAVA_HOME=/path/to/jdk21
export ANDROID_HOME=/path/to/android-sdk

git clone --recurse-submodules https://github.com/alpharomercoma/openweights.git
cd openweights
./gradlew :app:assembleDebug
```

Four pinned submodules ride along — llama.cpp, the OpenCL headers and ICD loader, and
QuickJS for the script sandbox — so `--recurse-submodules` matters. Already cloned without
it: `git submodule update --init --depth 1`.

Toolchain: JDK 21, Android SDK 37, NDK r29+ (for 16 KB page alignment), CMake 4.1.2.
`./gradlew verify` runs the lot: lint, detekt, ktlint, every host test including the
multiplatform JVM and iOS-simulator tiers, and assembles both debug flavors.

Two product flavors: `standard` is llama.cpp only; `accelerated` adds the ExecuTorch
runtime (~8.6 MB) and is what `.pte` support means. `assembleDebug` builds both.

## Architecture

Multi-module Gradle project; each module has one job.

| Module | Responsibility |
|---|---|
| `:app` | Compose UI, navigation, view models, downloads, the watch scheduler |
| `:core:common` | Multiplatform domain models (Android, JVM, iOS) and the compiled-model chat templates |
| `:core:designsystem` | Theme, tokens, reusable composables |
| `:core:engine` | `InferenceEngine` contract, the llama.cpp JNI runtime, the ExecuTorch runtime, and the router between them |
| `:core:hub` | Hugging Face client, GGUF header parser, resumable downloader |
| `:core:data` | Room database, settings, encrypted token vault, usage ledger |
| `:core:device` | Device profiling, model fit estimation, thermal policy |
| `:core:tools` | The agent loop and the eighteen tools it may call |
| `:core:sandbox` | QuickJS in an isolated process, for the script tool |
| `:baselineprofile` | Records the startup profile the release APK carries |

Longer form in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md). Why llama.cpp is the primary
arbitrary-GGUF engine, and why ExecuTorch earned the second slot, is argued in
[`docs/research/inference-engines.md`](docs/research/inference-engines.md) and measured in
[`docs/research/executorch-families.md`](docs/research/executorch-families.md).

## Contributing

Issues and pull requests are welcome: see [`CONTRIBUTING.md`](CONTRIBUTING.md).

## License

[Apache License 2.0](LICENSE). llama.cpp is vendored as a submodule under its own MIT license.

---

<div align="center">

[![Four screens: a chat with tokens per second and context fill, the Hugging Face search with a fit verdict per model, a turn that searched the web with its steps expanded, and a plan with steps to tick off.](play/graphics/readme-screens.png)](play/graphics/readme-screens.png)

<sub>Telemetry as you chat · the Hub, filtered to what fits · a turn that used a tool · a plan you tick off<br>Tap for full size</sub>

</div>
