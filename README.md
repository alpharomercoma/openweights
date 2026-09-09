<div align="center">

![](play/graphics/readme-logo.png)

# OpenWeights

**Run open-weight AI models on your phone.**<br>
No account, no telemetry. Inference runs on your own hardware.

[![Google Play](https://img.shields.io/badge/Google%20Play-live-052B42?style=flat-square)](https://play.google.com/store/apps/details?id=io.github.alpharomercoma.openweights)
[![License](https://img.shields.io/badge/license-Apache--2.0-052B42?style=flat-square)](LICENSE)
[![Android](https://img.shields.io/badge/Android-12%2B%20·%20arm64--v8a-052B42?style=flat-square)](#requirements)
[![Engines](https://img.shields.io/badge/engines-llama.cpp%20·%20ExecuTorch-052B42?style=flat-square)](#two-runtimes-one-build)
[![Models](https://img.shields.io/badge/models-Hugging%20Face-052B42?style=flat-square)](https://huggingface.co/experimentalmachines)

</div>

OpenWeights is an Android app for running open-weight language models locally. Search
Hugging Face from inside the app, check whether a model fits your phone before you download
it, and chat on the device. Optional agent tools can use the web, work in a folder you share,
run sandboxed JavaScript, keep memory, schedule checks, and carry out a plan.

Get it on [Google Play](https://play.google.com/store/apps/details?id=io.github.alpharomercoma.openweights),
or build it from this repository.

<div align="center">

[![Four screens: a chat with tokens per second and context fill, the Hugging Face search with a fit verdict per model, a turn that searched the web with its steps expanded, and a plan with steps to tick off.](play/graphics/readme-screens.png)](play/graphics/readme-screens.png)

<sub>Chat telemetry, model discovery with fit estimates, visible tool calls, and task plans.</sub>

</div>

## Contents

- [What makes it different](#what-makes-it-different)
- [Features](#features)
- [Requirements](#requirements)
- [Build](#build)
- [Our models on Hugging Face](#our-models-on-hugging-face)
- [Measurements and reports](#measurements-and-reports)
- [Architecture](#architecture)
- [Documentation map](#documentation-map)
- [Contributing and contact](#contributing-and-contact)
- [License](#license)

## What makes it different

**The Hub is the model list.** Other on-device apps hand you a short catalogue somebody
else chose. OpenWeights searches Hugging Face for GGUF and ExecuTorch repositories, shows
what fits, and runs supported architectures locally. GGUF support follows the pinned
[llama.cpp](https://github.com/ggml-org/llama.cpp) build; ExecuTorch support covers the
model families whose prompt formats the app implements.

**Two runtimes, one build.** llama.cpp runs GGUF files;
[ExecuTorch](https://github.com/pytorch/executorch) runs compiled `.pte` files for eight
model families. The app routes on the file format, and both ship in the same 28.8 MB
release bundle. On the phones measured, the compiled LFM2.5 1.2B decoded a token in 46 ms
against 106 for its GGUF on the Tensor G5 and 38 against 59 on the Exynos 2400, and reached
its first token faster on every chip ([latency](https://alpharomercoma.github.io/openweights/latency.html)).

**Honest about your device, before the download.** The GGUF header is read over HTTP range
requests, so the app can say what a file needs at your context length and whether it will
run, before you spend a gigabyte. The same metadata sizes the window the model opens with.
Compiled models open at the window reported by their export, and Discover shows it.

**A fast first turn.** The instructions and tool definitions are prefilled into the cache
while you type, snapshotted, and kept on disk. On the reference phone, that reduced a fresh
chat's time to first token from about 18.5 seconds to under one second
([measured](docs/research/first-turn-latency.md)).

**An agent within limits you set.** Eighteen tools, four run modes, and individual switches
for the three tools that use the internet. Every tool call is a row in the reply naming what
it was given.

**Numbers you can check.** Speed, quality and backend parity were measured on real phones
and published with their methods, caveats and raw results. Each report states its own device
set: [latency on five chips](https://alpharomercoma.github.io/openweights/latency.html),
[the exported-window study on four](https://alpharomercoma.github.io/openweights/window.html),
[its reruns](https://alpharomercoma.github.io/openweights/reruns.html).

**Private by construction.** No accounts, no analytics, no crash reporter, backups off the
device disabled, and a Hugging Face token that lives in the hardware-backed Keystore and goes
only to `huggingface.co`. Some things do reach the internet on your behalf, and they are named:
model search and downloads, and the three network tools. The
[privacy policy](docs/privacy-policy.md) says exactly what leaves and when.

## Features

### Models

| | |
|---|---|
| Discover | Hugging Face search across both runtimes, with filters for task (chat, vision, audio), size band, publisher, gated repositories and runtime, and four sort orders. A Recommended chip shows a measured shortlist until you start typing. |
| Fit before download | Comfortable, tight, will not run, or no room to download, from the GGUF header read remotely and your phone's memory. Compiled files get a size-based estimate and show their exported window where the publisher states it. |
| Downloads | Resumable over range requests, verified with a rolling SHA-256, run in a foreground service so leaving the app does not end them, with a "Ready to use" notification that opens the model. |
| Vision projectors | A GGUF's `mmproj` projector is paired with the weights automatically. |
| Recommended | Four rows: our two ExecuTorch exports of LFM2.5 at a 32k window, Liquid AI's LFM2.5-VL 1.6B, and Qwen3 1.7B. Below them, under an "Experimental / modified" heading, the two refusal-removed LFM2.5 variants: findable, not recommended. The reasoning is in the code beside the list. |

### Two runtimes, one build

| | llama.cpp | ExecuTorch |
|---|---|---|
| Files | GGUF, any architecture the pinned build reads | `.pte` compiled for XNNPACK |
| Families | All of llama.cpp's | Qwen3, Qwen2.5, SmolLM2, SmolLM3, Llama 3.2, Phi-4-mini, Gemma 3, and LFM2.5 including its VL variant |
| Pictures | Any model with an `mmproj` projector | LFM2.5-VL and Gemma 3 exports |
| Audio | Models with an audio projector | Not yet |
| Tool use | Determined by the model's template | All families except SmolLM2 and Gemma 3 |
| Thinking switch | Determined by the model's template | Qwen3 and SmolLM3; LFM2.5 decides for itself |
| Compute | CPU with runtime-selected kernels (i8mm, SVE2, SME where present), KleidiAI, Adreno OpenCL where the driver works | CPU, KleidiAI kernels through XNNPACK |
| Context window | You choose; the app suggests one from the header and your memory | Fixed at export; the app opens at that window |

### Chat

- **Branch, edit, regenerate.** Any message can be edited and resent, branched into a new conversation, regenerated, copied as text or Markdown, read aloud, or reported.
- **Folding.** When the context passes a threshold you set, older turns fold into a summary between turns. The transcript on screen is untouched; only the prompt shrinks.
- **Telemetry.** Every reply carries its prefill and decode tokens per second. The stats panel shows cached tokens, tokens generated, total time, and the prefill breakdown.
- **Per-model parameters.** Answer length, temperature, context length, image detail, the folding threshold, system prompt, thinking and reasoning effort; under Advanced, top-p, top-k, repeat penalty, which processor prefills and which decodes, and the wording the model is given about its tools.
- **Thermal policy.** Thread counts step down as the phone reports heat, and generation pauses at the critical level.
- **Search and archive.** Search across conversation titles and bodies; file conversations to an archive grouped by last activity.
- **Voice.** Dictation uses the phone's on-device recogniser only. Read-aloud uses Android's own text to speech. Nothing is sent anywhere to be spoken.
- **Input.** Camera, photos, video and documents from the composer. Video arrives as sampled frames.

### The agent

The registry holds eighteen tools, sixteen of them user-facing. Three use the internet, sit
under a heading that says so, and have individual switches. A fresh install has one switch
on, `web_search`; the rest are off until you turn them on.

| Tool | What it does | Uses the internet |
|---|---|---|
| `web_search` | Searches the web (DuckDuckGo, Brave, Yahoo, tried in that order until one answers) | Yes |
| `show_pictures` | Shows pictures or short clips found on the web | Yes |
| `fetch_url` | Fetches a public page as readable text, optionally saving it to your folder | Yes |
| `find_files`, `read_file`, `write_file`, `delete_file` | Work inside a folder you share, revocable at any time | No |
| `show_website`, `show_document`, `show_slides` | Render a saved page, Markdown document or slide deck live on the Canvas | No |
| `run_script` | Runs JavaScript in an isolated-process sandbox with no network or file system | No |
| `watch` | Re-checks something on a schedule; always asks before it is created | No |
| `read_memory`, `save_memory`, `update_memory`, `forget_memory` | Short facts kept across conversations; one switch for reading, one for the three writers | No |
| `advance`, `ask_user` | Tick a plan step; ask you a question with options (not shown on the Tools screen) | No |

**Modes**, set per turn with a slash command:

| Command | Mode | What it means |
|---|---|---|
| `/ask` | Ask first | Approve each tool call before it runs |
| `/auto` | Auto (default) | Tools run without asking; the transcript records what ran |
| `/plan` | Plan | The assistant says what it would do and runs nothing |
| `/yolo` | Everything | Waives Auto's two network checks for this process only; memory writes and watch creation still ask |

Auto pauses for approval when a fetch to an address the model chose follows untrusted text
in the turn, or when data would leave the device after private files were read. `/yolo`
waives those two checks for the current process and is never saved.

**Other commands:** `/new`, `/compact`, `/retry`; `/goal <task>` works through a task on
its own and resumes if the app is killed; `/deep-research <question>` researches a
question through searches and page reads and writes up the findings with sources.

While a goal or research runs, the transcript stays the screen: one line above the composer
says the state and the current step (tap it for the full plan and notes), the plan and any
question from the model appear at the end of the transcript, and the composer, the only text
field on the screen, steers the next step or answers the question. The reasoning is in
[docs/design/goal-surface.md](docs/design/goal-surface.md).

### Canvas, Watch, Memory

- **Canvas** renders a website, an A4 document or a 16:9 deck from files in your shared folder, served from a loopback server that refuses every request off the device.
- **Watch** re-runs a check on a schedule between one minute and a day, with limits on runs and lifetime.
- **Memory** keeps short facts across conversations, capped in count and size, visible and editable on the Tools screen even when its switches are off.

### Settings and platform

- Settings: theme, Hugging Face token (verified and stored encrypted), the compute devices this build sees, your device's memory and storage, and About.
- Tools screen: the shared folder, search engines and results per search, a proxy for search only, memory facts, switches for the three network tools, and the two memory switches.
- Usage dashboard: tokens today, the week, by model.
- Five languages: English, Filipino, Spanish, Japanese, Arabic (right to left).
- Permissions: internet, notifications, microphone for dictation, and the two foreground service permissions. No storage permission; the shared folder is a revocable grant.

## Requirements

- Android 12 (API 31) or newer, on a 64-bit Arm phone (`arm64-v8a`).
- Memory for the model you pick. The app tells you before you download; measured on a
  Dimensity 9400, a 1.2B model at int4 sits near 1 GB resident and a 2.6B near 2 GB, plus
  the context window.
- On the 12 GB phones tested (MIUI and Samsung), a process near 6 GB was killed, so large
  context windows on full-attention models are not recommended there.

## Build

```sh
export JAVA_HOME=/path/to/jdk21
export ANDROID_HOME=/path/to/android-sdk

git clone --recurse-submodules https://github.com/alpharomercoma/openweights.git
cd openweights
./gradlew :app:assembleDebug
```

Four pinned submodules ride along: llama.cpp, the OpenCL headers and ICD loader, and
QuickJS for the script sandbox. Cloned without `--recurse-submodules`? Run
`git submodule update --init --depth 1`.

| | |
|---|---|
| Toolchain | JDK 21, Android SDK platform 37, NDK r29 (16 KB page alignment), CMake 4.1.2 |
| Targets | minSdk 31, targetSdk 36, compileSdk 37, `arm64-v8a` only |
| Stack | Kotlin 2.3.20, Jetpack Compose, Hilt, Room, WorkManager; `core:common` is Kotlin Multiplatform with JVM and iOS targets |
| Checks | `./gradlew verify` runs ktlint, detekt, lint, every host test tier and assembles the debug build; `verifyOnDevice` needs a phone with models pushed; `verifyJniSymbols` fails a release whose R8 pass renamed a name JNI resolves |
| Versioning | `versionName` is typed (2.0.0); `versionCode` is derived from the repository's commit count, with a floor that stops it going backwards |
| Release | A signed bundle from `./gradlew :app:bundleRelease` with the upload key on the release machine only; the checklist is [docs/play-store.md](docs/play-store.md) |

One build carries both runtimes.

## Our models on Hugging Face

Exports we made and measured, published under the Experimental Machines organisation:
<https://huggingface.co/experimentalmachines>.

| Repository | What it is |
|---|---|
| [LFM2.5-1.2B-Instruct-ExecuTorch-XNNPACK-32k](https://huggingface.co/experimentalmachines/LFM2.5-1.2B-Instruct-ExecuTorch-XNNPACK-32k) | LFM2.5 1.2B for ExecuTorch, 32k context, int4 weights for Arm CPUs. 827 MB. |
| [LFM2.5-2.6B-ExecuTorch-XNNPACK-32k](https://huggingface.co/experimentalmachines/LFM2.5-2.6B-ExecuTorch-XNNPACK-32k) | LFM2.5 2.6B for ExecuTorch, 32k context. 1.8 GB. Reasons before it answers; give it a 2048-token reply budget. |
| [LFM2.5-1.2B-Instruct-heretic](https://huggingface.co/experimentalmachines/LFM2.5-1.2B-Instruct-heretic) | The 1.2B with refusal behaviour removed, with an ExecuTorch 32k export beside the weights. |
| [LFM2.5-2.6B-heretic](https://huggingface.co/experimentalmachines/LFM2.5-2.6B-heretic) | The 2.6B with refusal behaviour removed, with an ExecuTorch 32k export beside the weights. |

Each card states the export recipe, the memory the window costs at load, the measured speed
on a Dimensity 9400, and the start-token rule the ExecuTorch runtime needs. How they were
made: [exporting LFM2.5 ourselves](docs/research/executorch-own-exports.md).

## Measurements and reports

These reports document speed, quality and backend parity measured on real phones, with
their methods and caveats. The pages are the readable form; the notes hold the method and
the raw tables.

| Report | What it asks |
|---|---|
| [Does the exported window matter?](docs/research/executorch-window-matrix.md) · [page](https://alpharomercoma.github.io/openweights/window.html) · [tables](docs/research/window-matrix.md) | The same weights exported at 2k to 32k on four chips: does the window change answers, speed or memory? |
| [What the reruns changed](https://alpharomercoma.github.io/openweights/reruns.html) | A second pass over that matrix: the 2.6B under two reply caps, same-file repeats, and why a 32k full-attention export dies on 12 GB phones |
| [Public benchmarks on six phones](docs/research/public-benchmarks.md) · [tables](docs/research/benchmark-matrix.md) | GSM8K, IFEval and BFCL on both runtimes, same prompts, same graders |
| [Five chips, two runtimes](https://alpharomercoma.github.io/openweights/latency.html) | Time to first token and time per output token, five models, five chips |
| [Parity on five SoCs](docs/research/parity-five-socs.md) · [tables](docs/research/backend-parity.md) | Do the two runtimes grade the same agentic prompts the same way across silicon? |
| [The first turn](docs/research/first-turn-latency.md) | Where a 25-second cold first turn went, 18.5 s of it before the first token, and how warming removed most of it |

Every other note, measured or decisional, is indexed with its date and finding in
[docs/research/README.md](docs/research/README.md).

## Architecture

A multi-module Gradle project; each module has one job.

| Module | Responsibility |
|---|---|
| `:app` | Compose UI, navigation, view models, downloads, the watch scheduler |
| `:core:common` | Multiplatform domain models and the compiled-model chat templates |
| `:core:designsystem` | Theme, tokens, reusable composables |
| `:core:engine` | The `InferenceEngine` contract, the llama.cpp JNI runtime, the ExecuTorch runtime, and the router between them |
| `:core:hub` | Hugging Face client, GGUF header parser, resumable downloader |
| `:core:data` | Room database, settings, encrypted token vault, usage ledger |
| `:core:device` | Device profiling, model fit estimation, thermal policy |
| `:core:tools` | The agent loop and the eighteen tools it may call |
| `:core:sandbox` | QuickJS in an isolated process, for the script tool |
| `:baselineprofile` | Records the startup profile the release build carries |

The longer form, including the engine contract, the native layer, the agent loop and the
test tiers, is [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Documentation map

| Document | Read it for |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | How the modules fit, the engine contract, what the ExecuTorch side learned |
| [docs/ROADMAP.md](docs/ROADMAP.md) | The nine product areas, what is done and why each exists |
| [docs/CONTEXT.md](docs/CONTEXT.md) | The working log: toolchain, device measurements, dated session notes |
| [docs/research/README.md](docs/research/README.md) | Every research note with its date, question and finding |
| [docs/design/visual-language.md](docs/design/visual-language.md) | The design rules every screen follows |
| [docs/design/goal-surface.md](docs/design/goal-surface.md) | What the chat shows while a goal or research runs, and why nothing but one strip is pinned |
| [docs/privacy-policy.md](docs/privacy-policy.md) | What stays on the device and what leaves; published at [the policy page](https://alpharomercoma.github.io/openweights/privacy.html) |
| [docs/play-store.md](docs/play-store.md) · [docs/store-listing.md](docs/store-listing.md) | The release checklist and the listing copy, data safety rows and rating answers |
| [play/site/README.md](play/site/README.md) | How the public pages are built and published |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Setting up, running the test tiers, what reviewers look for |

## Contributing and contact

Issues and pull requests are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md). Performance
claims come with before-and-after numbers from a real device, every network egress is named
in the app, and the three network tools each have a switch.

**Main contributors:** Alpha Romer Coma and Arjhine Ty.

**Organisation:** [Experimental Machines](http://experimentalmachines.org/), which also
publishes the models at <https://huggingface.co/experimentalmachines>.

**Contact:** for collaborations or inquiries, write to
[alpha@experimentalmachines.org](mailto:alpha@experimentalmachines.org). For bugs, open an
issue with the device, Android version, model file and quantisation, and the throughput
readout from the reply.

## License

[Apache License 2.0](LICENSE). llama.cpp is vendored as a submodule under its MIT license;
QuickJS under its MIT license; the OpenCL headers and ICD loader under Apache 2.0. Models
are published by third parties under their own licenses.
