# Production readiness: the 2026-08-31 sweep

One session, every open question from the second-runtime work: the full two-phone
parity matrix, the Discover visibility bug, the search engines measured on both
devices, the Watch feature turned into a real monitor, the research loop unstarved,
tablet layout, drafts, and the questions that needed answers rather than code.
Everything measured here was measured on the POCO X8 Pro (Dimensity 9400) and a
Qualcomm Device Cloud Snapdragon 8 Gen 3 on this date.

## The full matrix

`docs/research/backend-parity.md` now holds 20 rows: five families x two engines x
two SoCs, rendered per device by `tools/eval/compare.py` (which previously let one
phone's report silently overwrite the other's - fixed). The headline:

- **Every llama.cpp grade is identical across the SoCs.** Ten rows, case for case,
  including the failures — and still true on five SoCs as of 2026-09-03
  ([parity-five-socs.md](parity-five-socs.md)).
- **ExecuTorch has three cross-silicon divergences on five SoCs** (two when there were
  two phones): on Qwen3-1.7B's format-constraint case and SmolLM3's tool-result case
  the Snapdragon 8 Gen 3 alone fails where four SoCs pass, and on Llama 3.2's
  tool-result case the 8 Elite and the Tensor G5 alone pass. Greedy decoding is
  deterministic per binary, but XNNPACK's quantised kernels differ per
  microarchitecture, and a knife-edge case can tip. llama.cpp's kernels evidently do
  not tip on these cases.
- Speeds: the Snapdragon 8 Gen 3 is faster on every ExecuTorch row (LFM2.5 1.2B
  decodes at 66.6 tok/s there against 36.6 on the Dimensity).

## The Discover visibility bug (critical)

`num_parameters` filters on metadata the Hub computes from safetensors files. A
compiled repository holds a `.pte` and a tokenizer - nothing the Hub can count - so
sending the size band with `filter=executorch` returned 16 stray repositories and
none of the official ones. "ExecuTorch + Official + under 10B" therefore showed
only SmolLM2. The band now rides only the llama.cpp half of the search
(`HubSearchUrlTest` pins it), and the ceiling the Hub cannot enforce is applied
from the parameter count in the repo name, keeping repos whose size is merely
unstated. Verified live on both phones: the combination now returns the official
compiled corner (meta-llama, pytorch, software-mansion, ISTA-DASLab...).

Two adjacent fixes: repositories fetched by id (the recommended shortlist) read
their runtime off the `executorch` tag instead of defaulting to GGUF, and a
declared-but-private companion on a `@Serializable` class turned out to throw
`IllegalAccessError` at runtime (the serialization plugin puts `serializer()` on
whatever companion exists), which is written down in `HubPayloads.kt` so nobody
does it twice.

## Search engines, measured

`SearchEnginesOnDeviceTest` runs every shipped engine plus candidates on the
device's own network. Both phones, same afternoon, three queries each:

| Engine | Dimensity | Snapdragon | Verdict |
|---|---|---|---|
| DuckDuckGo | 8/8/8 hits, all relevant | same | keep, default |
| Brave | 8/8/0 | 8/8/0 | keep |
| Bing | 0/0/0 | 0/0/0 | **removed** |
| Google | 0/0/0 | 0/0/0 | **removed** |
| Yahoo (new) | 14/25/15, all relevant | 14/25/11 | **added** |
| Mojeek | 0 (parser) | 10/10/0 | not added |

`ddgs` 9.16 reached the same verdict from the other side: its Bing engine is
disabled in source, and its Google engine survives only by impersonating a 2006
Nokia feature phone. Yahoo is Bing's index behind a door that opens: organic
anchors carry an `h3.title`, destinations decode out of the `RU=` tracking
segment, ads resolve through `bing.com/aclick` and are dropped. `YahooParseTest`
pins the parse against a captured page.

Media search was probed end to end on device: six relevant hits for images and for
clips, thumbnails resolving to `image/jpeg`, video targets landing on real watch
pages. The proxy setting now honours `user:pass@` on HTTP proxies and its
placeholder shows the real format (`http://user:pass@host:3128 or
socks5h://127.0.0.1:9150`, the same convention ddgs documents).

Parallelism: `web_search` is `parallelSafe`, so two models on two devices - or two
tools in one turn - can search concurrently; each provider keeps its cookies in
memory per instance, so nothing is shared that should not be.

## The Watch feature is now a monitor

The audit's verdict was that the infrastructure was excellent and the product was
not a monitor: every completed check notified, because no tick knew what the last
one found. Now:

- The tick prompt carries the previous finding and asks for a closing
  CHANGED/UNCHANGED verdict; only news notifies (`WatchVerdict`, failing open - a
  model that forgets the word counts as changed, a byte-identical answer does not).
- The third straight failure announces itself ("Stopped: three checks in a row
  failed") instead of dying silently.
- A skip no longer overwrites the last real finding on screen.
- A tick ahead of its deadline does nothing, which stops the WorkManager backstop
  double-running fast watches; the in-process ticker stamps its ticks with the
  deadline it slept toward, so a coarse timer cannot make its own tick look early.

Known remaining gaps, deliberate: no manual watch creation (the model's `watch`
tool is the only door), no per-watch model choice, no quiet hours, and the
72-hour/60-check lifetime is fixed. Those are product decisions worth their own
pass, not bugs.

## Plan mode and deep research

The audit answered the "is it the model or us?" question precisely:

- **Early asking in deep research was already structurally impossible** - the
  planning turn withholds `ask_user` and instructs "searching to find out is the
  research itself". If a build still asks early there, it is the model asking in
  prose, which the loop ignores.
- **Loop breakage was ours.** Research steps had exactly two rounds (search, then a
  forced answer) while the step prompt ordered "change the query and search again"
  - behaviour the budget could not afford. The retry then spent the fetch round,
  failed the evidence check again, and two failures halted the goal. Fixes:
  `web_search` and `fetch_url` now chain (a step earns up to four rounds once it
  searches), the planning turn is told which tools execution will have (the
  Meta-Harness environment-snapshot finding, in its cheapest form), and a failed
  evidence check names the missing half ("you searched but never opened a result")
  instead of repeating one generic sentence - the verification-spiral shape from
  the same paper.

## The Stanford paper (arXiv 2603.28052, Meta-Harness)

Read in full. What transferred, beyond the two fixes above: its strongest ablation
says raw traces beat model-written summaries as input to harness improvement
(56.7 vs 38.7) - which endorses this repo's habit of keeping verbatim tool
excerpts over summaries; its TerminalBench win was 80 lines of environment
bootstrapping, adopted here as the planning-turn tool snapshot; its
completion-flag finding maps to the step-retry spiral, fixed. Its "native tool
calling beat ICL parsing" result endorses keeping prose salvage strictly as a
fallback tier. The paper is silent on ask-vs-act policy. Its headline method - an
outer loop where a coding agent reads all traces and rewrites the harness - is
exactly what this project already does by hand with its eval suites.

## Tablet

Emulated (Pixel Tablet profile, 1280x800dp, API 36) and screenshotted in both
orientations, every screen. The one real bug: the drawer's 360dp cap was dead
because `fillMaxWidth(0.85f)` ran before `widthIn(max = 360.dp)` and its min-width
constraint overrode the cap - a 1280dp window drew a 1088dp drawer. Fixed by
ordering, and verified. Every reading surface (Settings, Tools, Usage, Watching,
Discover, Models, the chat list, the composer) now runs through `readableColumn()`
- a 720dp centred cap that is a no-op on phones. Deliberately not done: a
permanent drawer / two-pane layout at expanded width. That is the right eventual
design and a real project of its own.

## Generation quality, seen rather than asserted

- **Website**: `WebsiteBuildEval` on the Poco, post-changes: Qwen3-1.7B built a
  1,315-character styled page in one round, called `show_website` unprompted, and
  the page parsed into 14 real DOM elements in a live WebView.
- **Document**: the new `DocumentBuildEval`, same discipline: a 1,084-character
  Markdown reference with a title and per-planet sections, factual content
  asserted (Mercury/Venus/Mars present), `show_document` called unprompted, one
  round.
- **Presentation**: does not exist as a kind. The canvas knows SITE and DOCUMENT;
  "slides" today would be an HTML page. If presentations matter, the honest
  path is a third canvas kind rendering one `<section>` per slide with swipe
  navigation - a bounded feature, recorded here as the gap it is rather than
  claimed.

## Answers

**Does llama.cpp need per-model templates like ExecuTorch?** No - it already has
them. Every GGUF carries its model's own Jinja chat template in its metadata, and
llama.cpp renders it (minja) per model. The app hand-transcribes templates only
for ExecuTorch because a `.pte` carries nothing. One template per model is the
state of both engines; they differ only in who wrote it down.

**Is auto using Qualcomm's CPU/GPU/NPU (QNN)?** No, and knowingly. Both engines
run CPU-only today: llama.cpp's OpenCL path measured slower than KleidiAI CPU
kernels on these devices (`gpu-backends.md`), and the ExecuTorch builds are
XNNPACK. QNN-compiled `.pte`s exist on the Hub (l3utterfly publishes Qwen3/Gemma3
QNN exports) and ExecuTorch's QNN delegate is on Maven ungated, so a QNN column in
the matrix is feasible follow-up work; it was not reachable in this pass.

**Can the MediaTek NPU be used?** Measured and declined
(`npu-prefill-multiturn.md`): decode is 60-77% of wall time so even free prefill
caps the gain at ~1.3-1.7x, KV-cache reuse means a median turn prefills ~50
tokens where the NPU's advantage is smallest, and the NeuroPilot SDK is
portal-gated. The actionable finding needed no NPU: Q8_0 beats Q4_K_M end to end
on this CPU because KleidiAI has no q4_K kernel.

**Did more tools regress the model?** Bounded, and paid consciously. The catalogue
is gated by `isAvailable` (file/canvas tools are not described until a folder is
shared), its token cost is asserted in `ToolCatalogueTest` (576/1024 ceilings,
each move argued), and the choice-accuracy cost of additions has been measured at
each step (the show_pictures rename data being the sharpest example). No new
regression was introduced this session; the routing suite remains the tool for
re-checking after any default-model change.

**Total output tokens (the Apollo screenshot)?** Already ours, more prominently
than Apollo's: the Usage hero is the lifetime generated-token count, per-model
totals sit under it, and the new prefill/decode tiles give the split Apollo does
not. What Apollo has that we lack is per-conversation totals; the natural home
would be the conversation actions sheet, one line, if wanted.

**Git for projects?** Not yet, and the honest design is written down: JGit cannot
operate over the SAF workspace (no real file paths under scoped storage), so
Claude-Code-style git means moving projects to app-private storage exposed
through a custom DocumentsProvider, with JGit for plumbing, a `git` tool
(init/status/commit/push) for the model, and the existing TokenVault holding the
push credential. Feasible, bounded, not started.

**Pasted links?** `fetch_url` exists for exactly "an address you were given", and
the build evals show models call tools they are told about; whether a given small
model routes a bare pasted URL is a routing question the 14-case suite should gain
a case for before it is promised.

## Competitive position (August 2026)

Against the seven mainstream mobile AI apps (Claude, ChatGPT, Gemini, Le Chat,
Qwen, Kimi, DeepSeek): OpenWeights already has on-device counterparts for chat,
tool use, deep research, scheduled tasks (ahead of Claude's mobile app, which has
none), canvas/artifacts, code execution, vision input, and usage telemetry - and
offline capability is uncontested: every one of the seven is cloud-bound (Gemini
Nano powers OS features, not the Gemini app). The gaps that are feasible fully
on-device and worth taking next: projects/folders, persistent memory, custom
instructions per model (exists) vs per user (missing), voice input, and widgets/
assistant integration. Feasible only degraded: conversational voice, live camera.
Infeasible at phone scale: video generation, cloud connectors/MCP fleets,
computer use. Skills at mobile scale are plausible (they are prompt files);
MCP-to-cloud is a server-trust story that contradicts the no-server constraint.
