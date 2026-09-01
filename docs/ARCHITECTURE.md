# Architecture

OpenWeights is a multi-module Gradle project. Every module has one job, a small public
surface, and no knowledge of the UI above it.

```
:app Compose UI, navigation, ViewModels, downloads, watch scheduler
 ├── :core:designsystem theme, tokens, telemetry components, markdown
 ├── :core:engine InferenceEngine + two runtimes and the router between them
 ├── :core:hub Hugging Face client, GGUF parser, resumable downloader
 ├── :core:data Room, DataStore, token vault, usage ledger
 ├── :core:device device profiling, model fit estimation, thermal policy
 ├── :core:tools the agent loop, eighteen tools, the boards they report to
 ├── :core:sandbox QuickJS in an isolated process, for the script tool
 └── :core:common multiplatform domain models and compiled-model templates
:baselineprofile records the startup profile the release APK carries
```

Build configuration lives in `build-logic/convention` as Gradle convention plugins
(`openweights.android.application`, `.library`, `.compose`, `.hilt`), so SDK levels, Java
and Kotlin targets, and the ABI filter are declared once. AGP 9 compiles Kotlin itself —
`org.jetbrains.kotlin.android` must not be applied.

`:core:common` is Kotlin Multiplatform (`android`, `jvm`, `iosArm64`,
`iosSimulatorArm64`): the domain models, the tool-call parser and the eight
hand-transcribed chat templates for compiled models are pure Kotlin, tested on JVM and the
iOS simulator by `./gradlew verify`, which is the first brick of the iOS plan in
`docs/research/ios-strategy.md`.

## The inference engine

`InferenceEngine` (in `:core:engine`) is the whole contract between the app and whatever
is doing the maths:

```kotlin
val loadedModel: LoadedModelInfo?
suspend fun load(modelFile: File, params: ModelLoadParams, projectorFile: File?)
fun chat(messages: List<ChatMessage>, params: SamplerParams,
         tools: List<ToolDefinition>): Flow<GenerationEvent>
suspend fun warm(messages: List<ChatMessage>, tools: List<ToolDefinition>,
                 params: SamplerParams, snapshot: Boolean, store: String?): WarmResult?
fun cancel()
suspend fun resetContext()
suspend fun setThreads(generateThreads: Int, batchThreads: Int)
suspend fun unload()
```

Two implementations ship, and a third class chooses between them. `LlamaCppEngine` runs
any GGUF the pinned llama.cpp reads — the supported architecture list is code-generated at
build time from llama.cpp's own table, so it tracks the submodule instead of a hand-kept
list. `ExecuTorchEngine` runs compiled `.pte` files on XNNPACK in the `accelerated`
flavor; a `.pte` carries no metadata, so the app supplies the chat template, the stop
tokens and the tool syntax per family, and refuses files whose family it cannot name
(eight families render today; parity against llama.cpp is measured case-for-case in
`docs/research/backend-parity.md`). `RoutingInferenceEngine` dispatches on the file
format and is what the app actually injects.

### Native layer

`src/main/cpp` holds the engine sources plus three pinned submodules (llama.cpp, the
OpenCL headers and ICD loader):

- `engine_session.{h,cpp}`. A `Session` is one loaded model, one context, one KV cache,
  and the token history that cache represents. It renders prompts with the model's own
  chat template, reuses the cached prefix across turns, decodes, samples, measures, and
  owns the warm machinery below.
- `llama_jni.cpp`. The JNI surface. Nothing but marshalling and error translation.

Three design points:

**Everything runs on one thread.** llama.cpp contexts are not thread-safe, and running
generation on a single dedicated thread also means the `JNIEnv` passed into
`nativeGenerate` stays valid for the per-token callbacks. No thread attachment needed.
`cancel()` is the deliberate exception: it flips an atomic that the generation loop checks.

**Prefix reuse is explicit.** Every turn renders the *entire* conversation and tokenizes it
identically, then compares against the tokens already in the KV cache and only decodes the
difference. Re-sending an identical conversation therefore decodes exactly one token.
This is why `add_special` is unconditionally true: making it conditional produced token
sequences that differed at position 0 and silently defeated all reuse.

**The first turn is prepaid.** `warm()` reads the instructions and tool definitions into
the cache while nobody is waiting — at model load, after a fold, a branch or a reopen —
snapshots the fresh-chat head for the model families that refuse rollback, and persists
that snapshot to disk, one file per model, restored in tens of milliseconds on the next
launch. A turn arriving mid-warm interrupts it and keeps the batches that finished. The
measurements are `docs/research/first-turn-latency.md`; the fresh-chat first token went
from ~18.5 s to under a second on the reference phone.

### Choosing a CPU backend at runtime

The native build enables `GGML_CPU_ALL_VARIANTS`, which produces seven Android CPU
backends from armv8.0 up to armv9.2+SME. Each exports `ggml_backend_score()`, which
inspects the running CPU and returns 0 if its instructions are unavailable.

ggml's own loader finds these by scanning a directory, which does not work on Android:
with modern packaging the `.so` files are never extracted from the APK. They are reachable
by soname through the app's linker namespace, so `load_best_cpu_backend()` dlopens each
candidate, asks for its score, and hands the winner to `ggml_backend_load`.

The result is one APK that uses i8mm matmuls on a 2025 flagship and still starts on a 2018
phone. OpenCL is built in with the Adreno kernels for the GPUs that accept it; Vulkan is
deliberately not built, against measurement (`docs/research/gpu-backends.md`).

## The agent

`:core:tools` owns everything between "the model asked for a tool" and "the result went
back in": eighteen tools, sixteen of them user-facing, three of which leave the device
(`web_search`, `show_pictures`, `fetch_url`) and say so in the UI. `AgentRunner` decides
one round — what was requested, what may run, what was skipped and why — and the turn
loop in `:app` (`TurnRunner`) owns cancellation and the pass-to-pass conversation. Files
live behind a user-granted folder (`Workspace`), scripts run in a QuickJS interpreter in
an `isolatedProcess` service with no filesystem, no sockets and no libc to reach
(`:core:sandbox`), and pages the assistant builds are served to a WebView by a
loopback-only server that resolves every path through the workspace so `../` is inert.

Memory is four verbs behind two switches: reading back is one decision, and the three
writing verbs — save, update, forget — share the other, because "may the model write to
what the app keeps about you" is one question however many tools answer it. Every writing
verb shows its exact arguments and asks first in every mode, since its effect outlives the
conversation, and the saved facts themselves are listed, editable and deletable on the
Tools screen.

The boards are how the model and the user share state without sharing a prompt: a plan
the user ticks (`PlanBoard`), a goal that survives process death (`GoalBoard`), a canvas
naming what is on screen (`CanvasBoard`), a question waiting for an answer (`AskBoard`).
Watches are the one tool whose effect outlives the conversation, so they always ask
first, and a WorkManager scheduler runs them within battery and thermal limits.

Routing a 1B model to the right tool — and to no tool — is measured work, not prompt
folklore: the suites and their verdicts live in `docs/research/tool-calling.md`, and the
offline harness that replays the app's exact prompt bytes is `eval/routing_matrix.py`.

## Design system

`:core:designsystem` holds the palette, type scale, and the components that make
measurements visible. The palette's accent is a **signal scale** rather than a fixed
colour: `signalColor(fraction)` maps a normalised measurement onto a teal-to-grey-to-red
ramp, and both `SpeedRail` (beside each reply, coloured by that reply's throughput) and
`ContextMeter` (the hairline above the composer) read from it. Colour carries data here,
which is why dynamic colour is off by default. A wallpaper-derived accent would compete
with it.

Type is three families with three jobs: Schibsted Grotesk for display, Hanken Grotesk for
the interface, and Geist Mono for every number, model id and quantization tag, so
measurements are visually separable from prose. Markdown replies render through the
design system too, including tables that measure their widest row and scroll horizontally
rather than truncate.

## Testing

- Unit tests (`src/test`) cover pure logic: parsing, estimation, prompt assembly, the
  agent loop against a fake engine, and the store screenshots (`PlayScreenshots`
  renders the real screens under Robolectric and writes the PNGs the listing uses).
- The multiplatform tiers (`jvmTest`, `iosSimulatorArm64Test`) keep `:core:common`
  honest on every platform it claims.
- Instrumented tests (`src/androidTest`) exercise the real engines against real weights
  on a real device — parity suites, canvas build evals, long-conversation evals. They
  skip rather than fail when no model is present, so a checkout without one still runs
  green. `docs/CONTEXT.md` has the commands.
- Release assembly runs `verifyJniSymbols`, which fails the build if R8 renamed anything
  the JNI callbacks reach for by name.
