# OpenWeights

**Run open-weight models from Hugging Face on your Android phone. No account, no cloud,
no telemetry.**

OpenWeights is a native Android app for local LLM inference. Search Hugging Face, see
whether a model will actually fit *your* device before you download it, pull it, and chat
 with histories, per-model hyperparameters, image and voice input, and a dashboard of
your own usage. Everything runs on-device via [llama.cpp](https://github.com/ggml-org/llama.cpp).

> Status: **early development.** See [`docs/CONTEXT.md`](docs/CONTEXT.md) for what works today.

## Why

Existing on-device apps hand you a curated catalog. OpenWeights hands you Hugging Face.
If someone published a GGUF an hour ago, you can run it. No vendor pipeline, no
per-model export step, no waiting for a first-party blessing.

## What makes it different

- **Any GGUF, not a catalog.** Search the Hub directly; ~100k+ quantized repos work.
- **Honest device fit.** Before downloading, OpenWeights reads the GGUF header over HTTP
  range requests and tells you the RAM it needs at your chosen context length, the
  throughput to expect, and whether it will run at all on your phone.
- **Real numbers, surfaced.** Tokens/sec, time-to-first-token, and context fill are shown
  while you chat, not hidden the way consumer assistants hide them.
- **Per-model hyperparameters.** Temperature, top-k/top-p/min-p, repeat penalty, context
  length, system prompt, threads: saved per model, with named presets.
- **Multimodal.** Image and audio input through llama.cpp's `libmtmd` for models that
  ship an `mmproj` projector.
- **Private by construction.** No accounts, no analytics, no backups off the device. Your
  Hugging Face token is encrypted with a hardware-backed Android Keystore key and is only
  ever sent to `huggingface.co`.

## Requirements

- Android 12 (API 31) or newer, `arm64-v8a`
- Enough RAM for the model you pick. The app tells you before you download

## Build

```sh
export JAVA_HOME=/path/to/jdk21
export ANDROID_HOME=/path/to/android-sdk

git clone --recurse-submodules https://github.com/alpharomercoma/openweights.git
cd openweights
./gradlew :app:assembleDebug
```

The llama.cpp source is a pinned git submodule, so `--recurse-submodules` matters. If you
already cloned without it: `git submodule update --init --depth 1`.

Toolchain: JDK 21, Android SDK 37, NDK r29+ (for 16 KB page alignment), CMake 3.22+.

## Architecture

Multi-module Gradle project; each module has one job.

| Module | Responsibility |
|---|---|
| `:app` | Compose UI, navigation, ViewModels |
| `:core:common` | Shared domain models and utilities |
| `:core:designsystem` | Theme, tokens, reusable composables |
| `:core:engine` | `InferenceEngine` API + llama.cpp JNI implementation |
| `:core:hub` | Hugging Face client, GGUF header parser, downloader |
| `:core:data` | Room database, settings, encrypted token vault |
| `:core:device` | Device profiling, model fit estimation, benchmarks |

Longer form in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md). The reasoning behind
choosing llama.cpp over ExecuTorch, MLC-LLM, MNN, and MediaPipe is written up in
[`docs/research/inference-engines.md`](docs/research/inference-engines.md).

## Contributing

Issues and pull requests are welcome: see [`CONTRIBUTING.md`](CONTRIBUTING.md).

## License

[Apache License 2.0](LICENSE). llama.cpp is vendored as a submodule under its own MIT
license.
