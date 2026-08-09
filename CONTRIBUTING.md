# Contributing to OpenWeights

Thanks for looking. This is an app for running open-weight models locally, and it is built
in the open for the same reason those models are.

## Getting set up

```sh
git clone --recurse-submodules https://github.com/alpharomercoma/openweights.git
cd openweights
./gradlew :app:assembleDebug
```

You need JDK 21, the Android SDK with platform 37, NDK r29 or newer (older NDKs do not
align native segments to 16 KB, which Google Play requires), and CMake 3.22+. The
llama.cpp source is a pinned submodule: if you cloned without `--recurse-submodules`, run
`git submodule update --init --depth 1`.

`docs/ARCHITECTURE.md` explains how the modules fit together;
`docs/research/inference-engines.md` explains why the engine is llama.cpp.

## Running the tests

Unit tests need nothing special:

```sh
./gradlew test
```

The engine's instrumented tests need a device and a GGUF. They skip themselves when no
model is present, so they will not fail a machine without one. `docs/CONTEXT.md` has the
exact commands, including the workarounds for devices that block `adb install`.

## Before you open a pull request

```sh
./gradlew ktlintCheck detekt test
```

A few things reviewers will look for:

- **Keep modules honest.** `:core:*` modules do not know about the UI. If a change makes a
  core module import something from `:app`, the boundary is in the wrong place.
- **Measure performance claims.** This project has already been surprised twice by
  build configuration mattering more than algorithms. If a change is meant to make
  inference faster, include before/after numbers from a real device and add them to
  `docs/CONTEXT.md`.
- **Comments explain constraints, not mechanics.** Say why something must be this way, not
  what the next line does.
- **No telemetry, no accounts, no network calls to anywhere but Hugging Face.** This is a
  hard product constraint, not a preference.

## Reporting bugs

Include the device model, Android version, the model file and quantization you were
running, and the throughput readout from the reply if you have it. Logs from
`adb logcat -s OpenWeights` are more useful than a description of the symptom.
