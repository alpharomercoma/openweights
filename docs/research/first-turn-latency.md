# The first turn, and why it no longer costs twenty seconds

Written 2026-08-31. The measurements are from the app's own usage ledger and engine logs on
the Poco (MediaTek MT6991 / Dimensity 9400, `192.168.100.171`) and the Qualcomm Device Cloud SM8650
(`pineapple`), against real GGUF files.

## The bill, itemized

A first turn on LFM2.5-1.2B-Instruct-QAD asked "who is alpha romer coma" and took 25.6
seconds, 18.5 of them before the first token. The app's own database itemized it: the
first-pass prompt was **2,054 tokens**, of which the question was about eight. The rest was
the harness — roughly 1,700 tokens of tool definitions and call-format teaching, ~230 of
tool policy, ~80 of date and style — prefilled cold at the phone's steady ~100 tok/s.

Three facts turned that from a grievance into a design:

1. **~100 tok/s prefill is the device, not a bug.** The ledger shows it across days
   (22,212 prefill tokens in 222.7 s on 2026-08-31), and LFM2.5-1.2B is the *fastest*
   model in the fleet at it (Qwen3.5-2B ≈ 47, LFM2.5-2.6B ≈ 55).
2. **The prefix is byte-identical across conversations** for a given model, settings and
   day — the KV-stability rule this codebase already enforces for other reasons.
3. **Only the hybrid families actually had to pay it repeatedly.** A transformer's cache
   can be cut back to the shared prefix when a new conversation starts (`llama_memory_seq_rm`),
   so it reuses the prefix across chats. Recurrent and hybrid models (LFM2.5, granite-hybrid,
   Jamba) refuse partial rollback, and `newChat()` reset the context for everyone anyway —
   so in practice **every** new chat was a full cold read for **every** model.

## What shipped

Two mechanisms in the engine, one discipline in the app.

**Warm at load** (`Session::warm`). After a model loads, the app renders the head a fresh
conversation will start with — the same instructions builder and the same tool selection a
real turn uses, byte for byte — and the engine prefills it in the background while the user
is still reading the screen. The warm renders *without* the generation prompt, so the warmed
text is a strict byte prefix of any first real prompt. It is cancellable mid-batch (the
abort callback), commits progress per batch, and yields immediately to a real turn.

**A prefix snapshot for the families that cannot roll back.** When the loaded model is
hybrid or recurrent (`llama_model_is_hybrid` / `llama_model_is_recurrent`), the warmed
state is captured once with `llama_state_seq_get_data` — 12.4 MB for LFM2.5-1.2B's ~1k-token
test prefix — and restored whenever a prompt carries the whole warmed prefix but the cache
offers less: a fresh context, a new chat after an old conversation, a reopened conversation.
Restore is a memory copy, not a re-read. Transformers deliberately get no snapshot: rollback
already serves them, and their per-token KV is an order of magnitude larger.

**`newChat()` no longer resets the context.** The alignment logic (`align_cache`) makes the
reset redundant: it reuses the longest matching prefix, rolls back where the memory allows,
restores the snapshot where it refuses, and starts cold otherwise — and logs which, so a
regression is one `adb logcat -s` away. The old reset was a guaranteed 20-second re-read
per new chat, purchased for hygiene the alignment now guarantees byte-for-byte.

## Proof

`WarmPrefixEval` (core/engine androidTest) runs against every GGUF in
`/data/local/tmp/openweights/eval`, and passes on the Poco across LFM2.5-1.2B (hybrid),
Llama-3.2-3B, Qwen3-1.7B, SmolLM3-3B (transformers), and Gemma-3-1B (no system region —
correctly a no-op):

| device | model | path | fresh-chat cached | fresh-chat TTFT |
|---|---|---|---|---|
| Poco (Dimensity 9400) | LFM2.5-1.2B Q4_K_M | snapshot restore | 1018 of 1036 | **184 ms** |
| Poco | Llama-3.2-3B Q4_K_M | rollback | 1044 | 453 ms |
| Poco | Qwen3-1.7B Q8_0 | rollback | 1052 | 218 ms |
| Poco | SmolLM3-3B Q4_K_M | rollback | 1054 | 455 ms |
| QDC SM8650 (8 Gen 3) | LFM2.5-1.2B Q4_K_M | snapshot restore | 1018 | **308 ms** |
| QDC SM8650 | Qwen3-1.7B Q8_0 | rollback | 1052 | 148 ms |

### Re-measured 2026-09-03 on the current build

The table above is from 2026-08-31. `WarmPrefixEval` was rerun on the Poco on 2026-09-03,
after the two `CpuTopology` fixes of that morning, on the same synthetic 1,036-token prefix:

| model | cold prefill of the head | fresh-chat TTFT | path | snapshot |
|---|---|---|---|---|
| LFM2.5-1.2B Q4_K_M | 9,326 ms (1,018 tok, 109 tok/s) | **235 ms** | snapshot restore | 12,388 KB |
| Qwen3-1.7B Q8_0 | 23,299 ms (1,047 tok, 45 tok/s) | 455 ms | rollback | none |
| Llama-3.2-3B Q4_K_M | 38,119 ms (1,038 tok, 27 tok/s) | 2,212 ms | rollback | none |
| SmolLM3-3B Q4_K_M | 45,183 ms (1,049 tok, 23 tok/s) | 414 ms | rollback | none |
| gemma-3-1b-it Q4_K_M | 43 ms (1 tok) | n/a | no system region | 26 KB |

Three things this settled, and one it opened:

1. **The cold side and the warm side now come from one measurement.** The 18.5 s in this
   note's opening is a real 2,054-token agent prompt from the usage ledger; the 184 ms was
   a restore of the eval's synthetic 1,036-token prefix. Pairing them was never apples to
   apples. The pair to quote is 9,326 ms to 235 ms: same prefix, model, phone and build.
2. **The snapshot is 12 MB, not 26 MB.** 12,388 KB for LFM2.5-1.2B Q4_K_M. The 26 that had
   been carried around is gemma's 26 **KB** no-op snapshot.
3. **The thread fix did not inflate the August baseline.** LFM2.5 prefills the 1,036-token
   head at 109 tok/s today against the ~100 tok/s this note recorded on 2026-08-31, so
   2,054 tokens still costs about 19 s and the ledger's 18.5 s stands as measured.
   What the day's benchmark medians (138 tok/s for the same model) show is **prompt
   length**, not the thread rule: prefill falls off with length, hard on some models
   (Qwen3 Q8_0 does 124 tok/s under 200 tokens and 45 at 1,047), so a fixed-prefix cost
   must never be computed by dividing tokens by a short-prompt rate.
4. **Open: Llama-3.2-3B's fresh chat came back at 2,212 ms against 453 ms in August.**
   One run, on a phone that had been benchmarking all day, so it is a lead rather than a
   regression; the rollback families are the ones to rerun cold.

The correctness claim is scoped honestly. A snapshot restore replaces the state wholesale,
so its decode schedule is identical to a computed prefix's — and the eval asserts the reply
is **byte-for-byte identical** to the computed baseline, greedy, on the hybrid. The rollback
families keep a few user-turn header tokens whose values were accumulated in a different
batch, which can flip one near-tie token deep in a long thinking chain; that epsilon is a
property of batch-boundary floating point that every cached multi-turn reply in production
already carries (this suite is where it was first caught in the act: SmolLM3 flipped
"is straightforward" to "should be straightforward" 150 tokens into a think block). For
them the eval asserts the answer, not the bytes.

## The second pass: prompts rewritten from the root (2026-08-31)

Multi-turn was never the problem — decoding writes its own KV entries, and the
engine-history record replays the engine's exact bytes, so turn N+1 prefills only its own
text (measured in `TurnRunner.advance`: 48 tokens instead of 1,222 for the same round).
What still cost a foreground re-read was every event that *rewrites the prompt from the
root*: a fold (whose summarization pass also leaves the cache holding the summary
conversation), a branch (which reset the context outright), a reopened chat (same), and a
settings change. After any of these, the next question paid for the whole rewritten
conversation.

Now `warmEngine()` warms in two stages — the fresh head first, then the conversation on
screen via the same `engineMessages()` builder the next send uses — and fires after a
post-turn or forced fold, after `branchFrom`, after `reopen`, and on the existing load /
new-chat / settings triggers. Both resets are gone: alignment only ever reuses positions
whose bytes match the new prompt, so a stale cache cannot leak into a reply; what it can
do is spare the re-read. Three rules make this safe:

- **The conversation warm never takes the snapshot slot** (`warm(snapshot = false)`).
  The fresh-head snapshot is what every future new chat restores from; a conversation in
  that slot would trade the floor for one chat's convenience. Head-then-conversation
  ordering exists for the same reason.
- **The warm list is the next prompt minus the question, byte for byte.** It is built by
  `engineMessages()` itself, and the tool-notes decoration only ever lands on a *final
  user* message — a warm list ends with an assistant reply, so its bytes are untouched.
  `ChatWarmTest` pins this equality on the host.
- **A branch carries the engine-history record when it covers exactly the carried turns**
  (a branch from the last reply), and rebuilds otherwise — the record cannot be cut at an
  earlier point because tool rounds mean its messages do not map one-to-one onto
  transcript entries. Media conversations are not warmed: the warm path renders text only.

Measured on the Poco (`WarmPrefixEval#conversationWarmExtendsWithoutTakingTheFreshSnapshot`),
where "reused" shows the conversation warm extending the head rather than re-reading it,
and the fresh-chat column proves the snapshot survived:

| model | conversation warm | follow-up | fresh chat after |
|---|---|---|---|
| LFM2.5-1.2B (hybrid) | reused 1018, read 25, 221 ms | cached 1043, TTFT 140 ms | restored 1018, TTFT 172 ms |
| Llama-3.2-3B | reused 1038, read 24, 686 ms | cached 1062, TTFT 504 ms | rolled back 1044, TTFT 411 ms |
| Qwen3-1.7B | reused 1047, read 28, 328 ms | cached 1068, TTFT 223 ms | rolled back 1052, TTFT 201 ms |
| SmolLM3-3B | reused 1049, read 24, 679 ms | cached 1073, TTFT 467 ms | rolled back 1054, TTFT 401 ms |

The follow-up column is also the proof, per real template, that a conversation rendered
*without* the generation prompt is a byte prefix of the same conversation rendered with
one — the property the whole mechanism leans on.

## The incident that hardened it (2026-08-31, evening)

"Hi" took sixty-nine seconds on the Poco X8 Pro Max. The ledger of that minute, from
logcat: the app opened under heavy memory pressure (3.5 GB of swap in use — partly the
afternoon's five-model eval — and this device's Mali-G925 is not yet supported by the
OpenCL backend, so prefill ran CPU-only at ~31 tok/s instead of ~100); the load-time warm
therefore took 70 s instead of 20; and the user's turn queued behind the whole of it.
Not a leak, not a held model — a swallowed interrupt, magnified by a slow warm.

Three holes, each now closed and each carrying a regression test:

1. **The one-shot cancel.** `run()` cancelled a warm once, then waited. A cancel that
   lands while a warm is still entering the engine is erased by the warm's own
   entry-reset of the flag, and a warm that starts a moment later was never cancelled at
   all. Now a turn declares itself waiting (no warm starts while one is), and repeats the
   cancel until it holds the engine.
2. **The pre-turn engine hops.** The turn path touches the engine's single thread before
   `run()` — the thermal thread re-plan, a pre-turn fold — and those hops queued behind
   the warm's native call, upstream of any interrupt. Verified live: with fix 1 installed,
   "hi" still waited 26.8 s at `setThreads`. Now `yieldWarms()` clears the engine before
   anything in the turn path reaches it.
3. **The hybrid's forfeited progress.** A mid-batch abort leaves cells a recurrent memory
   cannot cut away, so an interrupted hybrid warm reset and kept *nothing* — 16 s of read,
   gone, and the turn paid the whole prompt again in the foreground. Now each committed
   batch is checkpointed (`llama_state_seq_get_data`, ~26 MB, milliseconds against a batch
   that costs seconds) and an aborted batch restores the last checkpoint:
   `kv: interrupted warm kept 1024 committed tokens`.

Replayed on the same phone, same scenario, fixed build: send lands mid-warm, the warm
dies in **349 ms keeping 1,024 of 2,197 tokens**, the turn starts **470 ms** after the
tap (was 26,800), and reads only the remainder. A turn's wait is now bounded by one abort
landing plus the un-warmed tail, never by the warm's length.

## The thinking model and midnight (2026-09-01, small hours)

"Prefill is taking too long still", on LFM2.5-1.2B-Thinking — and the kv log named two
separate causes in two lines.

**Midnight.** `kv: diverged at 10 of 2787` — the `Today is …` line, ten tokens into the
prefix, rendered live. The process outlived the date; every prompt after 00:00 diverged
at the head; the hybrid paid a full foreground re-read (2,197 tokens for "hi"), and the
snapshot and the disk file were both yesterday's bytes. Now the day is *pinned*: a
conversation keeps the day it started with — one stale day being the cheaper wrong, the
same trade the ExecuTorch template already made — and a fresh chat's warm refreshes the
pin and reads the new head in the background before anybody types.

**The thinking replay.** `kv: diverged at 2196 … re-reading 25` on every turn: the
Thinking variant's chat template reads think blocks *from the content* and strips them
from every past reply unless its own `keep_past_thinking` kwarg is set — it never reads
`reasoning_content`, and it carries none of the `<|tool_list_*|>` markers that would route
it to llama.cpp's LFM2 handler. Our render did exactly the wrong two things for it: moved
the thinking out of content, and passed the instruct-family kwarg. The snapshot restore
absorbed each miss (99% reuse), but the re-read grew with the conversation.

The fix probes instead of guessing: at load, a known assistant turn is rendered through
the production path both ways — reasoning split out, and left inline with both kwargs
sent — and whichever keeps the thought wins. Instruct, Qwen3 and SmolLM3 keep the split
path; this template selects `kv: this template keeps past thinking inline; replaying it
verbatim`; a template that drops thinking either way is named in the log. Verified live
on the phone: two turns on the Thinking model with **no divergence line at all**, and the
new post-turn warm — which re-renders the conversation after every settled turn, so any
residual divergence is absorbed between turns rather than in front of the next question —
reporting `warmed 2 tokens (2395 reused)`: the per-turn byte-stability monitor, reading
healthy.

## The warm that outlives the process (2026-08-31, late)

The question that prompted it: can the warm just *be there* at startup? In RAM it cannot
— Android kills cached processes at will, and pinning a 700 MB model resident forever
would fight the OS and the battery. On disk it can: after the one computed warm of the
day, the engine writes the state file (`kv: warm state saved: 2188 tokens, 26442 KB`,
36 ms), and every later launch of the same model, settings and day restores it instead
of re-reading — `kv: warm restored from disk: 2188 tokens in 23 ms`. On the day this was
built, the computed warm cost 64.7 s on a swap-crawling phone; the restore cost 23–55 ms.
Cold-start-to-answered-"hi", measured live: **1.3 s**, cache reused 100%.

The mechanics: the store lives inside `Session::warm` itself, so byte-parity is
structural — the same render, the same tokens, and the file is used only when its token
array equals the freshly rendered prefix exactly. Staleness self-resolves: a new day's
date line, changed settings or tools, replaced weights (the file is keyed to the model
file's name, size and mtime), or another llama state version all miss the compare or
llama's own validation, delete the file, and the warm computes once and rewrites it. On
hybrids the restore also arms the in-RAM new-chat snapshot from the same bytes, so every
restore mechanism works from the first second. Conversation warms are never persisted —
their bytes change every turn. Files live in the app's cache dir, one per model, pruned
beyond three; the OS may clear them, which costs exactly one recompute.

## What the research said, and what was deliberately not built

Three surveys were run before building (arXiv; Cursor/Copilot/Claude-Code/Manus mechanics;
the OpenClaw codebase). They agreed with each other and with this design:

- **Byte-stable prefix + persistent KV is the whole game on-device.** PromptCache measured
  60× TTFT on CPU from reusing attention states (2311.04934); persistent-KV work reports
  11–136× vs re-prefill (2603.04428, AttentionStore 2403.19708); llama.cpp's own
  slot-save practice lands 45–111×. Our measured 18.5 s → 184 ms is 100×, in family.
- **Tool-search indirection does not transfer at 15 tools.** Anthropic's own docs say
  upfront loading is faster under ~10 tools; BFCL multi-turn collapses at small scale
  (Qwen3-1.7B 16.9%), and every retrieve-then-call step is a hop a 1–3B model fails.
  Deferred/virtual tools solve a 50–10,000-tool problem this app does not have.
- **Per-query tool filtering breaks the cache it would pay for.** Manus ("mask, don't
  remove"), GitHub's embedding router assumptions, and our own KV rule all point the same
  way: the catalogue must be stable within a conversation. Ours is; plan-mode's tool
  stripping — measured here to beat instructions — happens per conversation-mode, which is
  still a stable prefix per mode.
- **Consolidating tools into action-enums solves a failure we measurably do not have.**
  The 2026-08-15 benchmark's wrong-tool column was 0 for most models; the observed failure
  modes are under- and over-calling. Fewer-but-wider tools would trade a solved problem
  for argument complexity small models fumble.
- **Description wording is already evidence-shaped.** Four system-message wordings scored
  identically on three of six models; several current descriptions carry comments recording
  measured rewrites (`run_script`'s sandbox-absences clause, `search_files`' "ask rather
  than guess"). The remaining trim was ~170 characters across two tools. EasyTool-style
  uniform terseness (2401.06201) was already the house style.
- **Worked examples per tool (+21.5% on a 3B in 2604.20148) are the one untested lever.**
  With the prefix warmed, their token cost moves off the first-token path; the benchmark
  has room for an arm. Not built this round; recorded as the next measurement.

## What this does not cover

- **ExecuTorch** now has the warm's first half (2026-08-31): the head is fed through the
  runner's prefill-only entry — in pieces, since a prefill cannot be stopped mid-call, so
  the piece is the interrupt latency — with the warm target computed as the common prefix
  of the conversation rendered with and without a probe user turn (this runtime's
  equivalent of rendering without the generation prompt). Judged by the runtime's own
  accounting on the real runtime: the first question after a warm paid **14 prompt tokens
  and 297 ms** against a cold control's **915 tokens and 13,963 ms**. What it still cannot
  have: rollback (a cache longer than the target resets and refeeds, in the background),
  snapshots, and the disk store — the runner serializes no state, so a cold start pays one
  background head read. The llama.cpp fleet — every GGUF — keeps the full mechanism.
- **Media turns.** An attachment still re-evaluates the conversation, and a
  conversation carrying media is skipped by the conversation warm; unchanged.
- **A settings change mid-day** re-warms in the background (20 s of battery, once) —
  head and, now, the open conversation. A chat opened while a model is still loading is
  warmed by the load's own finishing warm instead.
- **Snapshot RAM.** ~12 MB held for the 1.2B hybrid at a 2k prefix; freed on unload,
  replaced on re-warm. Transformers hold nothing.

## The snapshot had no repair path (2026-09-01)

A plus-button "hi" took 23 s on a session that had earlier been fast. Logcat told the
whole story: a model reload made a fresh Session (empty snapshot, stale disk file — the
day had changed), the first head warm was interrupted by a turn at 1536/2197, and from
then on every head warm found the head already cached, prefilled 0, and **skipped the
capture** — `maybe_snapshot` can only take a head from a cache that holds exactly the
head, and after any turn the cache always holds head + conversation. Every new chat then
diverged by ~1 token, the hybrid refused rollback, and re-read all 2,197 (`snapshot=0KB`
on every warm line is the tell).

The fix makes the head warm self-healing: when a snapshot-taking warm on a
hybrid/recurrent model finds the head cached but the snapshot missing or describing
another day's head, it first tries to arm the RAM snapshot straight from the warm file
(`arm_warm_file` — a verified read into `prefix_state_`, no touch of the live cache;
llama validates the blob at restore time, where a refusal already falls back cold), and
failing that resets and re-reads the head in the background — capture + save at the end,
paid once instead of on every new chat. Replayed live on the X8 Pro Max: interrupt at
1024/2188 → turn starts 90 ms later → post-turn warm logs `kv: warm snapshot missing;
re-reading 2188 tokens to rebuild it` → recapture + `warm state saved` → the open
conversation re-warms on the restored head (42 tokens, 364 ms) → the next two
plus-button "hi" turns complete in ~1.4 s each (were 23 s).

## The date left the head (2026-09-01)

The one line in the instructions that changed daily — `Today is …` — made every warmed
byte stale at midnight: snapshot, disk store, and a ~2,200-token background re-read,
bought back every day per model. Moving it to the *end of the system message* would not
have helped: the template renders the ~1,700-token tool block after the system content,
so any in-head position leaves most of the prefix behind the divergence. A `get_date`
tool was considered and rejected — the date is mostly needed implicitly (recency
judgments, ages, "this week"), a 1–2B model won't reliably think to call for it, and a
tool round-trip is seconds of foreground latency to save seconds of background compute.

So the date now rides on the conversation's first user turn (`withConversationDay`),
prepended before the question — after a fold, that is the recap turn. The head contains
no date at all: the warm file is valid for as long as the settings and weights are, and
a new day costs the dozen tokens of a turn that was being read anyway. The engine record
replays the first question byte-for-byte, so an open conversation keeps the day it was
sent with; `PromptDay`'s per-conversation pin is unchanged. Verified on the X8 Pro Max:
new head 2,177 tokens (was 2,188), computed once for the byte change, and "What is
today's date?" answered "September 1, 2026" at CH99%.

## The first model of all (2026-09-04)

Everything above makes a first turn fast by *having warmed already*. On a model that has
just been downloaded there is nothing to have warmed: the state file is keyed to the
weights, so a new model always costs one computed warm. Until this, whoever sent the
first message paid for it.

The report: a freshly rebooted phone, a model downloaded from the Hub, and a first
prompt at **31 tok/s prefill and 1 tok/s decode, 80 seconds**. This device's own norms
are ~100–112 and ~16–24. The prefill figure is not a new number — it is exactly the
`~31 tok/s` recorded in the swap incident above — which places the whole report in the
memory-pressure regime rather than the thermal one.

**What was ruled out first.** That decode figure could have been a measurement artefact:
a turn containing a tool call is several `generate()` passes, and `GenerationStats.through`
sums them. It is honest — `decode_ms` is `decode_end - first_token_ms` *inside* one
native call, so tool execution happens between passes and is never counted as decode.
1 tok/s is real decode, a twentyfold collapse, and only memory or clock explains that.

**Two things changed, and they are different in kind.**

**1. A finished download now warms.** `ModelArrivals` carries the one moment the app
knows a model is complete and nobody is waiting on the engine, and `ChatViewModel`
answers it by running the same two lines `loadDefaultModel` runs — same selection, same
prompt composition, so there is no second path to keep in step with the first, which is
the standing hazard for anything that renders a prefix. The load ends in `warmEngine`,
which writes the state file, so the first message is a restore. It defers in five cases,
each of them one where early is worse than waiting: the user unloaded on purpose, a model
is already loaded, a download is still running (`ModelStore.downloadsInFlight`, read from
`.part` files — the projector half of a multimodal model is a second download, and
opening the weights without it would leave pictures broken for the life of the process),
the phone is already hot, or the app is not on screen. That last one is the common case
rather than the exception, since nobody watches a multi-gigabyte transfer: taking two
gigabytes into a process Android has already filed as cached is how that process gets
killed, and the user would come back to an app that had restarted for a warm they never
asked for. None is retried, because none needs to be: the chat tab still opens the model
the moment somebody asks. Pinned by `PrewarmAfterDownloadTest`, whose foreground and
background cases pass together, so the guard is doing work rather than blocking
everything.

**2. A page-cache fix that did not work, and is gone.** With `useMmap` off, llama.cpp
reads the GGUF through buffered stdio, so the kernel holds its own copy of every byte on
top of the anonymous backend buffers. Nothing reads it again, and it never appears in RSS
or PSS, **which is why the mapped-vs-read table in `ModelLoadParams` could not see it**:
that table compares process accounting and this copy sits outside it. `Session::load` was
changed to drop it with `posix_fadvise(POSIX_FADV_DONTNEED)` after loading.

Measured on the phone, it does nothing. With the file known cached and 5.6 GB available, a
load moved system `Cached` from 3,660 MB to 3,664 MB. Up four, not down six hundred and
sixty-three. `useMmap` was confirmed at its default of false, so the call did run; models
live under `/storage/emulated/0/Android/data/...`, which is FUSE-backed, and the advice
does not reach the page cache holding the data. Timing could not have caught this: this
phone reads that file at ~3.5 GB/s, which is page-cache speed, so cached and cold are
indistinguishable by throughput and only the growth of `Cached` separates them.

The code is removed rather than left as a no-op with a confident comment on it. The
reasoning behind it is weaker than it first looked, too: a clean file copy is the first
thing the kernel reclaims under pressure, so it is the *least* harmful memory in the
system, and blaming it for the anonymous weights reaching zram was a guess. What survives
is the caveat on the table, recorded on `ModelLoadParams.useMmap`.

**What the device run showed (2026-09-04, evening).** Fresh install, LFM2.5-1.2B-Q4_0
(663 MB) downloaded from the Hub with the app on screen. The download notification cleared
at 17:37:44.903 and `loaded:` landed at 17:37:46.259 - 1.356 s against a measured cold load
of ~1.39 s, so the load began the moment the download finished. The first touch was at
17:37:46.101, 158 ms before the load completed, and no load finishes in 158 ms. The
pre-warm fired. Then `warmed 1102 tokens in 8101 ms`, `warm state saved: 13397 KB`, and on
the next load `warm restored from disk: 1102 tokens in 22 ms`.

It also caught a regression this change introduces. That tap queued behind the warm and the
model was ready at 17:37:55.980: **9.9 s of waiting, 8.4 of it a warm nobody asked for.**
`generate()` interrupts a running warm and `performLoad` never did, because before this
nothing warmed unless a model was already loaded. `performLoad` now calls
`turns.yieldWarms()` as well. Nothing is lost by killing the warm, since the load ends in
one. `FakeInferenceEngine.load` did not take the mutex that models the single native
thread, which is why no host test could have caught it; it does now, and the new test fails
with the yield removed.

**The memory hypothesis remains untested.** That run was on a healthy phone: swap moved
19 MB to 22 MB, 126 major faults across the whole session, and 28,840 ms of CPU over
4,917 ms of wall on the heavy pass, which is about six threads busy and so compute-bound
rather than starved. Prefill ran at 136 tok/s against the 31 tok/s of the report. Nothing
about zram was exercised. The counters that would settle it are logged around every load,
warm and turn:

    mem: before turn rss 1530 MB, pss 1357 MB, swap 19 MB, majflt 337, minflt 1013221, cpu 88890 ms

Every field is a running total and the difference across a turn says which failure it is. A
swap-in is a major fault, so `swap` and `majflt` both climbing means the weights are in
zram; `majflt` climbing with `swap` flat means file pages being re-read; both flat with CPU
time tracking wall time means throttled or misplaced cores, which is a scheduling problem
and none of the above. `tools/eval/cold_start_probe.sh` reads the same counters from
outside.

**What the pre-warm does not fix.** The reported 80 s had two halves. At 31 tok/s the
1,102-token prefix is ~35 s of prefill, and the pre-warm removes all of it. The rest was
decode at 1 tok/s, and **nothing here touches decode**: a warm cache does not make token
generation faster. If that state recurs the first turn should lose its prefill wait and
still decode slowly. That half is unexplained.

**What was considered and not done.** `LLAMA_LOAD_MODE_DIRECT_IO` exists in the pinned
build and would keep the page cache empty during the load rather than trying to empty it
after, which is the only version of that idea that could survive the FUSE finding above.
It falls back to buffered silently and is unmeasured on this storage stack, so it is a
measurement, not a change to make blind — and it is worth making only once the counters
say the duplicate copy costs anything. A file-backed cache of KleidiAI's repacked weights is the only design that
makes the *hot* weights droppable rather than swappable — the repack destination is
anonymous whether or not the source was mapped, so mmap cannot deliver that — but it is a
new on-disk format with a cache key covering the packing version and CPU features, which
is its own project. Warming *during* a download, which is what was originally asked for,
cannot work: every token traverses every layer, so a prefix needs practically the whole
file, and the bytes are not there yet.

## What actually collapses, measured on the phone (2026-09-04, night)

The report was decode at **1 token a second** on a freshly installed app. Warming cannot
touch that: a warm cache means the prompt is not re-read, and decode still has to generate
every token. So this went after decode directly, on the device, with a harness rather than
a theory.

**Healthy baseline, LFM2.5-1.2B-Q4_0 (663 MB), MT6991:** decode 28-33 tok/s across six
turns; prefill 44-96. Decode reads every weight once per token, so 30 tok/s on 663 MB is
**19.9 GB/s** of useful weight traffic.

**What the phone can actually do.** A native streaming read, same thread counts:

| threads | 1 | 2 | 4 | 8 |
| --- | --- | --- | --- | --- |
| GB/s | 16.0 | 35.3 | 31.9-38.4 | 45.1-45.8 |

So decode achieves about half of a raw streaming loop, and `0.52 x 38.4 / 0.663 = 30.1`
reproduces the measured rate exactly. **Decode speed is bandwidth over model size and
almost nothing else**, which is what makes the rest of this tractable.

### The defect that was real: a threadpool per token

`ggml_backend_cpu` starts with `threadpool = NULL` (ggml-cpu.cpp:227) and nothing here ever
called `llama_attach_threadpool`, so `ggml_graph_compute` took its `disposable_threadpool`
path: **a pool created and freed inside every graph.** Prefill runs one graph per 512-token
batch and pays it once for hundreds of tokens; decode runs one graph per token and pays it
for every one. Measured: the process thread count sat at a flat **61 while idle and
oscillated between 62 and 70** throughout a reply, and settled to the pool's own eleven
once a persistent pool was attached.

Worth only ~4% on an idle phone (29.8 -> 30.9 tok/s mean). It is fixed because it is wrong,
not because it was the reported bug.

**It also nearly shipped a use-after-free.** The first version rebuilt the pools whenever
the thermal policy re-planned thread counts. `llama_context::graph_compute` hands the CPU
backend a pool before every graph and `ggml_backend_cpu_set_threadpool` *pauses the
previous one* when the pointer changes, so a freed pool is still reachable. Under a
`thermalservice override-status 3` a turn ran **over 310 s** and never finished. One pool,
sized once to the wider count and never rebuilt, takes the same turn to **21 s**. `kickoff`
carries the active thread count per graph, so the extra workers simply sit out.

### Replicating the bad state, deterministically

`memhog` (a static arm64 binary that mmaps anonymous memory and keeps touching it) run
against the app until `MemAvailable` falls under the working set:

| hogs | MemAvailable | app RSS | app Swap |
| --- | --- | --- | --- |
| none | 4043 MB | 1513 MB | 23 MB |
| 3 GB | 2025 MB | 1513 MB | 23 MB |
| 5.5 GB | 1558 MB | **770 MB** | **693 MB** |
| 7.5 GB | 2482 MB | 917 MB | 539 MB |

At 693 MB swapped, essentially the whole weight arena is in zram. The next turn logged
**majflt 1,426 -> 129,256**: 128,000 major faults, 512 MB faulted back, matching the swap
drop exactly.

### And what that did, which is not what everyone predicted

**Prefill collapsed to 23.4 tok/s. Decode did not move: 32.6.** Under sustained pressure
(three hogs, 516 MB still swapped, 13,589 major faults in the turn) decode was **31.2**.

That falsifies the zram-decode theory on this hardware, and codex's arithmetic said so
before the measurement did: sustained full-weight refaulting at one token a second needs
roughly **53 GiB of swap-in across 80 s**, and the worst turn measured 54 MB. Three orders
of magnitude short. Memory pressure hits the pass that faults the weights back — prefill —
and decode runs at full speed behind it.

**31 tok/s prefill under pressure is the number in the original report.** That half
reproduces exactly.

Also ruled out by measurement, not argument:

- **cpuset demotion.** Backgrounding mid-generation moves the process to `cpuset:/foreground`
  (cpus 0-7), not `/background` (cpus 0-3), because the generation service holds it there.
  Decode unaffected.
- **Our own thermal policy.** `THERMAL_STATUS_SEVERE` drops both counts to `MIN_THREADS`
  = 2, and that costs 24.9 tok/s against ~31: a fifth, not a collapse.
- **`posix_fadvise` on the model file.** A no-op on FUSE storage; see above.

### The product gap this exposed

`FitEstimator` predicts speed from a `ThroughputCalibration`, and `DiscoverViewModel` built
one only from `usageRepository.decodeSpeedByModel()` — models this device **has already
run**. A fresh install has none, so Discover could say a model *fits* and never that it
would be usable. Someone spends twenty minutes of their connection on four gigabytes and
finds out afterwards.

`MemoryBandwidth` closes it: a 48 MB array streamed at the decode thread count, best of
three passes, cached per build. It seeds the decode calibration when there is no measured
one, per runtime, and the real measurement replaces it as soon as one exists.

Getting the probe right took three attempts on the device, and the unit tests would not
have caught any of them:

| written as | reads | reality |
| --- | --- | --- |
| direct `LongBuffer.get(i)` | 1.0 GB/s | bounds-checked call, not a load |
| `LongArray`, one accumulator | 6.8 GB/s | measures the dependency chain |
| `LongArray`, unrolled x8, four accumulators | **22.6-27.5 GB/s** | loads overlap |

Hence `DECODE_EFFICIENCY = 0.60`, fitted against the probe the app actually runs rather
than against the native loop, and deliberately about twenty percent under what this phone
really decodes. An estimate that flatters the phone never shows the warning it exists for.
A floor rejects an implausible sample — on read as well as on write, since the first bad
value was cached and served back.

### What is still not explained

Nothing reproduced 1 tok/s **for this model**. At 663 MB that needs 0.66 GB/s of effective
traffic, a thirtyfold collapse, and neither zram, thermal, backgrounding nor thread churn
produced it. The arithmetic points elsewhere: decode is bandwidth over size, so the same
0.66 GB/s is far less exotic on a larger file. A 4 GB model on this phone predicts ~4 tok/s
before anything goes wrong, and the reported 31 tok/s prefill is roughly a third of this
model's healthy 96 — both consistent with a model several times larger than the one tested
here. **Which model produced the report is the missing input**, and it is the difference
between a bug and a phone being asked for more than it has.
