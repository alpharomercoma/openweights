# Bringing OpenWeights to iOS

Written 2026-08-10, revised the same day after an objective review found several claims in
the first draft too confident. The question: is a separate native Swift app worth it, or
should we move to React Native or Flutter so one codebase serves both?

Short answer: **native Swift on top of the existing C++ core.** Not because the other two
cannot reach the hardware, but because they would not remove any of the work that actually
matters and we would pay for the Android UI twice.

## What we actually have to move

Measured from the repository, not estimated. Line counts are a poor proxy for effort and
are given only to show where the code sits; the effort estimate is by work package further
down.

| Layer | Lines | Portability |
|---|---:|---|
| `cpp/engine_session.{h,cpp}` | 1,135 | ~900 lines platform-neutral, plus an Android-only backend bootstrap |
| `cpp/llama_jni.cpp` | 402 | Entirely JNI. Replaced, not ported |
| `core:common` | 923 | No platform imports. Genuinely portable |
| `core:hub` | 1,264 | No Android imports, but OkHttp, `java.io`, `java.security` and Hilt. Needs KMP work or translation |
| `core:device` | 371 | `ActivityManager` and thermal readings need iOS equivalents |
| `core:data` | 1,044 | Room, DataStore, Keystore need SQLite, preferences, Keychain |
| `core:designsystem` + `app` | 8,856 | SwiftUI rewrite |

**Correction to the first draft.** I wrote that `core:hub` was "pure Kotlin". It has no
`android.*` imports, which is not the same thing: it imports OkHttp, `java.io.File`,
`java.security.MessageDigest` and `javax.inject`. Sharing it through Kotlin Multiplatform
is a refactor, not a recompile.

## The C++ core is portable. Its bootstrap is not.

The first draft claimed `engine_session.cpp` "moves nearly unchanged once logging is a
function pointer". That is wrong, and the reason matters.

The generation loop, KV cache reuse, UTF-8 boundary handling, chat templating, tool
parsing and the libmtmd integration are all platform-neutral. That is the part worth
keeping and it does move.

The **backend bootstrap does not**. `load_best_cpu_backend()` at `engine_session.cpp:192`
`dlopen`s seven `libggml-cpu-android_*.so` files and scores them, and `load_gpu_backend()`
at `engine_session.cpp:230` `dlopen`s `libggml-opencl.so`. Both exist because the Android
build sets `GGML_BACKEND_DL=ON` and `GGML_CPU_ALL_VARIANTS=ON`. iOS does not permit that
model: backends have to be linked statically.

The iOS build is therefore a different configuration, not the same one:

```
GGML_BACKEND_DL=OFF   GGML_CPU_ALL_VARIANTS=OFF
GGML_METAL=ON         GGML_ACCELERATE=ON
```

with the runtime loaders deleted and only `llama_backend_init()` called. Statically
compiled backends register themselves. The honest description is "900 lines of session
logic behind a platform interface, plus a new bootstrap per platform", and extracting that
interface is the first refactor, useful on Android too.

## What "access Apple's entire hardware" can and cannot mean

- **CPU.** NEON and Accelerate, inside the C++ we already ship.
- **GPU.** ggml has a first-class **Metal** backend. This is the real acceleration story.
- **Neural Engine.** There is **no public API for running arbitrary GGUF on the ANE**, and
  no llama.cpp ANE backend. Core ML is the supported route to ANE execution, through
  `MLComputeUnits.all` or `.cpuAndNeuralEngine`, and even then Core ML decides placement
  per operation, so not even a converted model is guaranteed to run there. Conversion means
  a curated `.mlpackage` catalogue, which is the constraint this project exists to escape.
  Apple's Foundation Models framework exposes Apple's own system model, not arbitrary
  weights, so it is at most an optional extra feature, never our engine.

None of this is affected by the UI framework. Metal comes through ggml's C++; the ANE is
closed to all three options equally. **The framework choice is not a hardware-access
decision.**

## Do not assume Metal wins

The first draft said Metal was "expected to win at both prefill and decode". That was
wishful. Unified memory removes the copy cost that hurts a discrete GPU, but it does not
remove shared DRAM bandwidth, and decode is bandwidth-bound: the CPU and GPU are drawing on
the same memory and the same thermal budget. Our Adreno result is the cautionary example,
where the GPU was 4.8 times faster at prefill and 0.7 times as fast at decode.

Measure CPU, full Metal offload and layer splits, recording prefill and decode separately,
cold and sustained, with peak resident memory and thermal state. The default backend
should end up a measured per-model, per-device policy, not a slogan.

## The iOS constraints that actually bite

1. **Memory admission is harder than Android, not just different.** Android reads total RAM
   and applies a headroom fraction (`DeviceProfile.kt:38`) plus a fixed 450 MiB runtime
   allowance (`FitEstimator.kt:144`). iOS has **no public per-process memory limit API**.
   `com.apple.developer.kernel.increased-memory-limit` and `extended-virtual-addressing`
   raise the ceiling but promise no particular number, and memory mapping a model does not
   make it safely resident. The iOS fit report has to be built from calibrated envelopes
   (model + projector + KV at context + measured peak transient) keyed by device family,
   OS version and offload configuration, and must say "unknown" where we have not measured,
   rather than "comfortable".
2. **App Review 2.5.2 is a gate, not a formality.** Weights are plausibly data interpreted
   by a bundled engine, and apps doing exactly this ship today. But an existing app is not
   binding precedent and approval is discretionary. Submit an early minimal build with the
   catalogue and import flow, explain that models are parameter files read by fixed
   bundled code, give reviewers access, show per-model license and provenance, and keep a
   removal mechanism.
3. **Background execution has moved on, but not far enough for us.** iOS 26 added
   `BGContinuedProcessingTask` for user-initiated long-running work that survives
   backgrounding with visible progress. It is worth using for a finite, explicitly started
   job. It is **not** a foreground service: it is cancellable, resource-constrained, and
   its GPU resource is currently **iPad only**, with
   `BGTaskScheduler.shared.supportedResources.contains(.gpu)` reported false on iPhones.
   Model downloads should use background `URLSession`, which continues while suspended.
4. **Thermals.** Android already cuts thread counts and can pause (`ThermalPolicy.kt:60`).
   iOS needs the same driven by `ProcessInfo.thermalState` and its change notification,
   applied to threads, batch size and GPU layer count.
5. **Licensing.** Open-weight does not mean redistributable. Each model carries its own
   terms. Store and display upstream license, source, revision and gated status per file,
   require the user's own token for gated repositories, and do not mirror or cache anything
   without checking redistribution rights.
6. **Download size.** Over 200 MB needs the user to agree on cellular. Our Android release
   is 20.9 MB with no bundled model, so this is not our problem.

## The options

**A. Native Swift and SwiftUI over the shared C++ core.** Replace `llama_jni.cpp` with an
Objective-C++ bridge, build an XCFramework with Metal and Accelerate statically linked.
Keeps the file that took longest to get right; full access to entitlements, memory pressure
notifications and thermal state. Costs two UI codebases and a translated logic layer.

**B. A, plus Kotlin Multiplatform for the shared logic.** `core:common` and a refactored
`core:hub` become KMP modules. Removes the duplication of the GGUF parser, fit estimator
and Hub client. Costs a build system both platforms depend on, and storage still needs
`expect`/`actual` per platform.

**C. React Native or Flutter for both.** They **can** reach Metal, Core ML, thermal state
and memory pressure through native modules; the first draft's "no hardware access" was
imprecise. What they cannot do is remove the native engine, the Metal work, the memory
admission problem, the entitlements or App Review. They add a bridge and a plugin surface
to maintain. And adopting either now means rewriting 8,856 lines of working Compose, so the
one-codebase saving is paid for twice.

## Recommendation

**A now, B when the duplication starts to hurt. Not C.**

The first milestone is the engine, not the UI:

1. Extract the backend bootstrap behind a platform interface, on Android, where it is
   already tested.
2. Build llama.cpp for iOS with `GGML_BACKEND_DL=OFF`, `GGML_METAL=ON`,
   `GGML_ACCELERATE=ON` as an XCFramework.
3. Bridge one path end to end: load a model, stream tokens, cancel cleanly.
4. Benchmark CPU against Metal and layer splits, prefill and decode separately, cold and
   sustained, with peak RSS and thermal state.
5. Measure the real resident ceiling with and without the memory entitlements on an 8 GB
   iPhone.
6. Submit a minimal build to App Review with the download flow in it.

Steps 4, 5 and 6 are validation gates. If any of them fails, the shape of the iOS product
changes, and it is much cheaper to learn that before a SwiftUI app exists than after.
