# The 2026-09-03 review: the whole codebase read adversarially by Gemini 3.8 Flash

The day after the six-review sweep, the same codebase was handed to a different model
with one brief: find real defects, ground every claim in a line, and be skeptical of
your own findings. What came back was 65 claims. Each was verified against the source
by a second reader before anything was changed; 16 were wrong, 6 were true and left as
they are with the reason, and the remaining 43 were fixed, each with a test where a
host test can express it. Tests and lint were green before, and are green after.

## Method

`tools/review/agy_review.py` splits the main sources into eleven bundles of at most
320 KB, one area at a time — the native engine, the engine's Kotlin, the tools, the
sandbox and Hub, the data layer, the chat loop in three parts, the rest of the app in
two — and sends each to `agy` (`gemini-3.8-flash-medium`) as one prompt over stdin.
The prompt carries the source, because the CLI cannot approve a tool call when nobody
is watching: a pass that tried to open a file produced one line and nothing else, twice,
until the prompt said not to. A pass reads in two to twelve minutes and returns three to
eight findings, each with a severity, a scenario, a confidence and a fix.

Every report was then verified by a reviewer with the repository open, one report per
reviewer, told to be rigorous in both directions: locate the claim by symbol (the line
numbers were unreliable), trace the callers, read the existing tests, and read the
platform contract where the claim rested on one. The verdicts below are theirs, and the
fixes were made only for what they confirmed.

## The ledger

| Area | Claims | Refuted | Fixed | Left |
|---|---|---|---|---|
| Engine, native | 5 | 2 | 2 | 1 |
| Engine, Kotlin | 6 | 2 | 3 | 1 |
| Tools | 13 | 2 | 10 | 1 |
| Sandbox and Hub | 8 | 3 | 4 | 1 |
| Data | 4 | 0 | 4 | 0 |
| Chat loop | 19 | 5 | 13 | 1 |
| App, the rest | 10 | 2 | 7 | 1 |
| **Total** | **65** | **16** | **43** | **6** |

## What was wrong, and is fixed

The commits are `000bc4b` (engine), `26c5eaa` (data), `7cea361` (sandbox and Hub),
`3ad64a6` (tools), `d531bd9` and `57cbf66` (app), and `b2bcf84` (chat loop).
The ones worth knowing about:

- **Taint did not cross turns from a failed step.** `AgentRunner` taints a turn on a
  tool having run, whether or not it succeeded, because a script that read a private
  file and then threw carries what it read in the exception text. The record that
  carries taint into the *next* turn kept only successful steps, so the next turn's
  `web_search` in Auto could carry the text off the device unasked. The rule is the same
  on both sides now.
- **The model's own files stayed "its own" across conversations.** `SessionArtifacts`
  is a singleton with no reset, and a file it created is replaceable and deletable
  without asking in Auto. A file made in one chat was therefore silently overwritable
  in the next, for the life of the process. It is cleared on every conversation switch.
- **Pictures went round the address boundary.** The image client had the resolver
  boundary and nothing else; a resolver never sees a host written as digits. It refuses
  a private literal now, the way `fetch_url` does.
- **The canvas server outlived the canvas.** The back arrow closed the screen and told
  the server nothing, so a tab opened from "open in browser" kept reading the folder for
  as long as the process lived. Leaving the screen stops and re-keys it. The WebView's
  own egress check also accepted the loopback on any port, which the page's policy does
  not cover for a navigation; it accepts the server's port alone.
- **A stop between prompt and tokens was lost on ExecuTorch.** The flag reset that
  preceded generation wiped a Stop that had landed during rendering, and the runtime's
  own stop is cleared when its loop starts. The flag is cleared at the start of the turn.
- **A drafted end token was committed to the cache.** Speculation accepted an
  end-of-generation verdict into `cached_` and the KV cache, where the plain path never
  puts one; the next turn diverged there and paid a rollback, or the whole cache on a
  memory that cannot roll back.
- **A reset resurrected other models' settings.** Removing the shared record made
  `observe()` read every model from its own full copy, the branch written for installs
  from before the shared set; the next save then wrote those values back as shared.
- **The sandbox ran a failing script again.** A runtime `SyntaxError` — `JSON.parse` on
  bad input throws one — read as the script not having parsed, and the retry ladder ran
  the program again on the next rung in the same context. It is compiled first now.
- **The Hub's compiled corner could never turn a page.** One cursor went to both
  runtimes' searches, and the cursor handed back was always llama.cpp's.
- **Page cleaning cut on a lowercased copy.** `lowercase()` can lengthen a string
  (U+0130 becomes two characters), so indices found in the copy sliced the original at
  the wrong place. And every textual content type went through the same HTML cleaning,
  so JSON fetched for a script arrived with its newlines flattened and its `<` removed.
- **Editing an earlier question kept the notes of the turns it discarded.** `regenerate`
  rebuilt the tool notes after the delete; `editAndResend` did not. And the stored row an
  edit rewrote was found by transcript position, which a swallowed storage failure could
  leave one ahead of the table; entries carry their stored id now, and an entry with
  none refuses the edit rather than guessing.
- **The composer could send during a fold, and without its attachment.** `isCompacting`
  cleared before the fold's write and state update had landed, and `canSend` did not
  know a copy was still in flight.
- **Deleting a model ran on the main thread.** Unlinking gigabytes from a button's
  onClick, in a class whose `init` explains why the same work goes to IO.
- **A watch reply ending in the word CHANGED was read as a verdict of change**, with the
  word cut off the summary. The verdict is a line of its own.

## What was claimed and is not so

The refutations are worth as much as the fixes, because each is a place the next reader
will be tempted to "fix" again:

- **IPv4-mapped IPv6 does not bypass the address filter.** `InetAddress.getByName` and
  `getByAddress` both return an `Inet4Address` for `::ffff:a.b.c.d`, on OpenJDK and on
  Android, so the IPv4 predicates apply.
- **`llama_sampler_sample` accepts the token itself.** Penalties and grammar see every
  sampled token, including every verified draft, because verification samples each
  position through the same call.
- **A goal's steps cannot exceed the limit.** A halted goal has no path back to
  `advanced()`; only `start()` follows, with a fresh count.
- **`ExecuTorchEngine.close()` is not reachable during a turn.** Nothing in the app calls
  it; the unload path takes the turn mutex.
- **`RoutingInferenceEngine` is not raced.** Every caller is serialised through the
  view model's load mutex and runs on the main dispatcher.
- **The compaction path cannot wipe another conversation's cache.** `reopen()` joins the
  running job before the id changes.
- **`Build.SOC_MODEL` cannot crash**: `minSdk` is 31, the level that introduced it.
- **`settle()` does not eat an object with a `value` field**: QuickJS wraps a non-module
  async eval in exactly one `{value: …}` and `settle()` peels exactly one.
- **Truncated tool calls were never acted on.** `TurnRunner` ends a cancelled or
  truncated pass before `advance()`; the native side now withholds them too, so no new
  caller has to rediscover the rule, but nothing was running them.

## What is true and left alone

- **An early tick on a slow watch does nothing** (app, "unconditional early check"). The
  reviewer read this as losing a period for a WorkManager-only watch. It is the
  documented choice, with a test that says so for a fifteen-minute watch: a tick ahead
  of its deadline is the backstop, and a check run early spends budget early and
  rewrites the countdown under the screen. A slow watch's deadline is WorkManager's own
  schedule, read back after enqueue, so the case is timer jitter within the slack.
- **The `built` map in `RoutingInferenceEngine` is unsynchronised**, and every touch is
  on the main thread today. A `ConcurrentHashMap` would cost nothing; it was left so the
  change set stayed to what was reachable.
- **Cursor columns are read by projection order** in `Workspace`. Not guaranteed by the
  contract, honoured by every provider anyone could name.
- **A verified download's checksum is cached** on a `.source` marker rather than
  re-hashed on every open. That is the design, documented where it lives; the reviewer
  called it a bypass.
- **A cancelled summary skips `resetContext()`**, which costs one full prefill on the
  next turn and nothing else: the engine compares bytes and starts over on a mismatch.
- **`advance()` has a dead branch** for an empty result list that no caller can reach.

## Two things learnt about the tool

A response under two thousand characters is not a review; it is the model announcing a
tool call it cannot make. The runner retries those now and the prompt says not to try.
And the reviewer's line numbers cannot be trusted at all on a file over a thousand lines;
every verification located its claim by symbol.

On the device: the sandbox's own instrumentation class fails six of sixteen when run as a
class on the test phone, with "the sandbox stopped before the script finished" — the
isolated process dying between tests — and passes every one of them run alone. It did
the same on the code from before this review, so it is the phone's, not the change's,
and is recorded here rather than chased.
