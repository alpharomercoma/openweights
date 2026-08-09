# Architecture

OpenWeights is a multi-module Gradle project. Every module has one job, a small public
surface, and no knowledge of the UI above it.

```
:app            Compose UI, navigation, ViewModels
 ├── :core:designsystem   theme, tokens, telemetry components
 ├── :core:engine         InferenceEngine + llama.cpp JNI
 ├── :core:hub            Hugging Face client, GGUF parser, downloader   (P2)
 ├── :core:data           Room, DataStore, token vault, usage ledger     (P3)
 ├── :core:device         device profiling, model fit estimation         (P2)
 └── :core:common         shared domain models
```

Build configuration lives in `build-logic/convention` as Gradle convention plugins
(`openweights.android.application`, `.library`, `.compose`, `.hilt`), so SDK levels, Java
and Kotlin targets, and the ABI filter are declared once. AGP 9 compiles Kotlin itself —
`org.jetbrains.kotlin.android` must not be applied.

## The inference engine

`InferenceEngine` (in `:core:engine`) is the whole contract between the app and whatever
is doing the maths:

```kotlin
suspend fun load(modelFile: File, params: ModelLoadParams)
fun chat(messages: List<ChatMessage>, params: SamplerParams): Flow<GenerationEvent>
fun cancel()
suspend fun unload()
```

`LlamaCppEngine` is the only implementation. The interface exists because a second backend
is a live possibility — ExecuTorch has a MediaTek NPU delegate — and because it keeps the
UI testable without a 1.7 GB model.

### Native layer

`src/main/cpp` holds two files plus a pinned llama.cpp submodule:

- `engine_session.{h,cpp}` — a `Session` is one loaded model, one context, one KV cache,
  and the token history that cache represents. It renders prompts with the model's own
  chat template, reuses the cached prefix across turns, decodes, samples, and measures.
- `llama_jni.cpp` — the JNI surface. Nothing but marshalling and error translation.

Two design points worth knowing:

**Everything runs on one thread.** llama.cpp contexts are not thread-safe, and running
generation on a single dedicated thread also means the `JNIEnv` passed into
`nativeGenerate` stays valid for the per-token callbacks — no thread attachment needed.
`cancel()` is the deliberate exception: it flips an atomic that the generation loop checks.

**Prefix reuse is explicit.** Every turn renders the *entire* conversation and tokenizes it
identically, then compares against the tokens already in the KV cache and only decodes the
difference. Re-sending an identical conversation therefore decodes exactly one token.
This is why `add_special` is unconditionally true — making it conditional produced token
sequences that differed at position 0 and silently defeated all reuse.

### Choosing a CPU backend at runtime

The native build enables `GGML_CPU_ALL_VARIANTS`, which produces seven Android CPU
backends from armv8.0 up to armv9.2+SME. Each exports `ggml_backend_score()`, which
inspects the running CPU and returns 0 if its instructions are unavailable.

ggml's own loader finds these by scanning a directory, which does not work on Android:
with modern packaging the `.so` files are never extracted from the APK. They are reachable
by soname through the app's linker namespace, so `load_best_cpu_backend()` dlopens each
candidate, asks for its score, and hands the winner to `ggml_backend_load`.

The result is one APK that uses i8mm matmuls on a 2025 flagship and still starts on a 2018
phone. See `docs/research/inference-engines.md` for what this is worth in tokens/second.

## Design system

`:core:designsystem` holds the palette, type scale, and the components that make
measurements visible. The palette's accent is a **signal scale** rather than a fixed
colour: `signalColor(fraction)` maps a normalised measurement onto a hot-to-cool ramp, and
both `SpeedRail` (beside each reply, coloured by that reply's throughput) and
`ContextMeter` (the hairline above the composer) read from it. Colour carries data here,
which is why dynamic colour is off by default — a wallpaper-derived accent would compete
with it.

Typography is IBM Plex: Sans for the interface, Mono for every number, model id, and
quantization tag, so measurements are visually separable from prose.

## Testing

- Unit tests (`src/test`) cover pure logic: parsing, estimation, prompt assembly.
- Instrumented tests (`src/androidTest`) exercise the real native engine against a real
  GGUF on a real device. They skip rather than fail when no model is present, so a
  checkout without one still runs green. `docs/CONTEXT.md` has the commands.
