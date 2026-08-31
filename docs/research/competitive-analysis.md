# Competitive analysis: OpenWeights vs the field, August 2026

Researched 2026-08-31 across official release notes, GitHub repos and releases, app
store listings, vendor blogs and tech press. Two arenas: the seven mainstream cloud
assistants, and the on-device apps OpenWeights actually competes with. Claims that
could not be verified against a primary source are flagged; third-party tok/s figures
are treated as sentiment, not measurements.

## Against the cloud apps

ChatGPT ships Chat / Work / Codex modes, a Scheduled Tasks hub with change-monitoring
tasks (the watch pattern), and an ad-supported unlimited free tier. Claude has Cowork
on mobile (beta, Max-first; server sessions, scheduled tasks since February - but no
live artifacts on mobile) and Android system-app actions. Gemini replaces Google
Assistant OS-wide from September 4, 2026, with Nano Banana 2 image generation free
and scheduled actions on paid tiers. Mistral rebranded Le Chat to Vibe with Work Mode
agents. Qwen's app is a free everything-app; Kimi has OK Computer; DeepSeek stays
deliberately minimal.

Where OpenWeights is genuinely ahead: offline operation, zero-account privacy, model
choice with user-controlled quantisation and sampling, local file tools (no cloud app
has them on mobile), an on-device script sandbox, a live canvas on the phone itself
(Claude's mobile Cowork explicitly cannot show live artifacts), per-reply stats with
the prefill/decode split, and open source. Watches still run without any server,
account or paid tier - but the *lead* is gone: Claude shipped scheduled tasks in
February and ChatGPT's Tasks hub includes change-gated monitors.

Where it is genuinely behind: frontier model quality and everything downstream of it,
voice, image generation, cross-session memory, agents that run with the phone off
(structural - no server), and account connectors. "Free" is no longer a moat - ChatGPT
free is unlimited with ads, Qwen and DeepSeek are free outright. Only private/offline
is.

## Against the on-device apps

| | OpenWeights | PocketPal | ChatterUI | Layla | MLC Chat | SmolChat | AI Edge Gallery |
|---|---|---|---|---|---|---|---|
| Runtime | llama.cpp + ExecuTorch | llama.cpp | llama.cpp + remote APIs | llama.cpp + LiteRT + ExecuTorch | MLC/TVM | llama.cpp | LiteRT |
| GPU accel | CPU-only today | OpenCL | OpenCL | OpenCL/Metal | OpenCL/Vulkan | no | yes |
| NPU (Hexagon) | no | yes (v1.17) | yes (v0.9) | yes | yes | no | partner silicon |
| Any-GGUF catalog | yes (+ .pte) | yes | yes | yes | no | yes | LiteRT community |
| Tool calling / agents | broadest in category | Talents (3 tools + BYOK search) | none | partial | none | none | Agent Skills + Mobile Actions |
| Research / plan / goals | yes | no | no | partial | no | no | no |
| Scheduled watches | yes - no local analog exists | no | no | no | no | no | no |
| Live site/doc/slides canvas | yes | HTML-render talent | no | no | no | no | no |
| Voice | no | neural TTS | system TTS | yes | no | Moonshine ASR | Audio Scribe |
| License / price | open, free | MIT, free | AGPL, free | closed, paid | Apache, free | Apache, free | Apache, free |
| Activity | active | very active (2-4wk cadence) | active, solo | active, closed | app frozen since Oct 2025 | quarterly | very active, 24.6k stars |

Per-competitor reads, honest both ways:

- **PocketPal** is the closest rival and closing: Hexagon NPU with graceful fallback,
  an agent loop that gained web search in August, on-device TTS, a benchmark
  leaderboard, a funding model - and a 3.3-star Play rating from crashes on low-end
  devices, which is the cautionary half: feature leadership does not survive
  instability. OpenWeights holds the entire agentic superstructure it lacks.
- **ChatterUI** owns roleplay depth and remote-endpoint flexibility (its killer
  feature); it has no tools at all.
- **Layla** also runs llama.cpp and ExecuTorch side by side - the dual-runtime talking
  point is not unique any more - plus on-device Stable Diffusion and voice; closed,
  paid, companion-positioned.
- **MLC Chat** is effectively unmaintained as an app; its residual claim is raw NPU
  speed.
- **SmolChat** competes only on minimalism.
- **AI Edge Gallery** is the platform wearing an app costume: Agent Skills loadable
  from URLs, offline device control via a FunctionGemma finetune, Play and App Store
  distribution, 24.6k stars. Locked to LiteRT, still experimental, still crash-prone -
  but it is Google building an on-device agent harness in public.
- Watchlist: **MNN** (fastest engine claims, Hexagon, speculative decoding) and
  **LM Studio's** mobile entry (iOS-only so far).

## Verdict

Leading, verifiably: agentic depth (research + plan + goals + watches has no local
analog), the canvas (site, document and now slides - something even Claude mobile
cannot do live), runtime honesty (stats, hyperparameters, open source), tool breadth.

Behind at parity-feasible cost, ranked by impact over effort:
1. **GPU/NPU acceleration** - the axis every review measures, every serious peer ships,
   and llama.cpp upstream now documents Adreno-OpenCL and Hexagon backends officially.
   The NPU-prefill verdict measured one workload; reviewers measure decode on
   flagships. Qualcomm's GenieX (BSD-3, llama.cpp plugin) may be the cheap door.
2. **Local memory** - user-visible, editable, tool-written; every cloud app has it, no
   local app does it well, and it is cheap on this codebase.
3. **Voice I/O** - Moonshine/whisper.cpp in, Kokoro-class TTS out; PocketPal proved the
   parts run on phones.
4. **Remote-endpoint mode** - the same harness driving the user's own llama-server or
   Ollama box; removes the small-model ceiling for a transport's worth of code.
5. **Stability and first-run curation** - PocketPal's 3.3 stars despite feature
   leadership is the proof of what decides Play-store fate.

Structural, not worth chasing: frontier quality, phone-off agents, real-time voice
latency, image generation at acceptable quality, million-token contexts.

## Threats, next six months

1. **Gemini Nano 4 + agentic Prompt API** in fall flagships; Gemini replaces Assistant
   OS-wide September 4. A free, OS-updated, NPU-accelerated local model with a tool-
   calling API on every new flagship. Surviving moats: model choice, non-AICore and
   older devices, bigger-than-Nano models, no account, harness quality.
2. **AI Edge Gallery graduating** from experimental with Gemma-exclusive NPU paths.
3. **PocketPal's cadence** - six more months closes most of the visible gap except
   research/watches/canvas.
4. **Samsung Galaxy AI** on-device branding on the largest OEM.
5. **Speed-bar resets** from MNN's Hexagon numbers and GenieX adoption - both equally
   opportunities for whoever moves first.
6. Model supply is the good news: Qwen3.5 Small, Gemma 4 E-series and LFM2.5-2.6B are
   strong fits for agentic and watch workloads; MoE-on-phone (Apple 20B-A1-4B, Gemma 4
   26B-A3.8B) is the pattern to support well.

Bottom line, unflattered: OpenWeights is the most capable agentic harness in the
on-device category - and it is behind the category on the thing the category is judged
by, accelerated inference. The former scheduled-tasks lead over cloud apps has
evaporated, free is no longer a differentiator, and the platform is walking toward
this niche. The defensible position is harness quality + model freedom + verifiable
privacy, and holding it requires shipping GPU/NPU before the fall flagship cycle makes
CPU-only apps look obsolete.
