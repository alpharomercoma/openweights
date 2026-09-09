# Working in this repository

OpenWeights is a native Android app (Kotlin, Jetpack Compose) that runs open-weight LLMs
entirely on the phone: GGUF files through llama.cpp and compiled `.pte` exports through
ExecuTorch. No accounts, no telemetry, no cloud. It is on Google Play as
`io.github.alpharomercoma.openweights`, Apache-2.0, one maintainer. This file is the entry
point for any coding agent or contributor; it is short on purpose and links to the rest.

## Build and check

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21   # non-interactive shells find no JDK otherwise
./gradlew ktlintCheck detekt test jvmTest        # what CI runs, in this order
./gradlew :app:assembleDebug                     # debug APK, package io.github.alpharomercoma.openweights.debug
./gradlew :app:bundleRelease                     # signed Play bundle; needs keystore.properties and upload.jks
```

Never run two Gradle invocations at the same time. The second one waits on the first's
lock, both look hung, and a killed daemon leaves a stale lock behind. Start one, wait for it.
The pre-commit hook (`lefthook.yml`) runs ktlint on staged Kotlin only, without Gradle.

Setup, submodules, SDK and NDK versions: `CONTRIBUTING.md`. Module layout and how the two
engines sit behind one interface: `docs/ARCHITECTURE.md`. Everything measured, decided or
learned, by date: `docs/CONTEXT.md` (long; search it, do not read it top to bottom) and the
index of research notes at `docs/research/README.md`.

## Rules that are not visible in the code

- **Secrets.** `upload.jks`, `keystore.properties` and `local.properties` exist in the
  working tree and are ignored; never stage them, never copy their contents anywhere. The
  Hugging Face token lives only in the shell environment as `HF_TOKEN`: never write it to a
  file, a commit, a log or a note. A hook denies commands that try (`tools/hooks/guard.py`).
- **Measure, do not assume.** Every default in this codebase is justified by a number from a
  real phone, and the KDoc says which. A change meant to make inference faster comes with
  before and after figures from a device, written up under `docs/research/`.
- **Prompt bytes are cache keys.** The prompt head must be byte-identical between turns, or
  a hybrid model pays a full re-prefill measured at eleven to nineteen seconds. Anything that
  varies per turn goes at the tail, never the head. See `docs/research/date-in-the-prompt.md`.
- **Module boundaries.** `:core:*` never imports from `:app`. If a change needs that, the
  code is in the wrong module.
- **Comments say why, not what.** A comment explains the constraint the next line obeys.
- **Prose style.** No em dashes or en dashes anywhere: commit messages, docs, comments,
  strings, this file. Recast the sentence with a comma, a colon or a full stop.
- **Egress is named and switchable.** Model downloads go to Hugging Face. The assistant's
  own web tools are the only other thing that leaves the device, each behind its own switch.
  Do not add a network call anywhere else.

## Git

- Commit subjects follow the log: a scope, a colon, and a sentence that says what changed
  and why (`watch: a fast watch sleeps on an alarm, because ...`). The body says what was
  measured or found. Agents append their own attribution trailer.
- Push with the GitHub CLI's credentials on this machine:
  `git -c credential.helper='!gh auth git-credential' push https://github.com/alpharomercoma/openweights.git main`
- The version code is the commit count on `main`. Never rewrite published history; a
  version code that goes down cannot be uploaded. Force pushes to `main` are denied by hook.
- CI (`.github/workflows/ci.yml`) runs lint, unit tests and a debug assembly on every push.
  A commit is not done until `gh run list --branch main --limit 1` says `completed success`.

## The test phone

The primary device is a POCO X8 Pro Max (MediaTek MT6991, Android 16, HyperOS) on wireless
debugging; its port changes and its ROM blocks plain `adb install`. The exact procedure,
from finding the phone over mDNS to running an instrumented test, is the `phone-deploy`
skill under `.claude/skills/`. It is a Markdown file; any agent can read it.

Screen-off measurements on this phone are throttled by the ROM and vary wildly. Wake,
unlock and cool the phone before timing anything.

## What agents should not do

- Do not add telemetry, analytics, crash reporting or an account of any kind.
- Do not gate a feature on a SoC name; read the runtime capability instead.
- Do not "fix" a default without the measurement that set it; find the KDoc first.
- Do not run the instrumented tests through `connectedAndroidTest` on the phone; it cannot
  install. Use the skill.
