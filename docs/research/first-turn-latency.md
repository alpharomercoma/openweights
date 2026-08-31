# The first turn, and why it no longer costs twenty seconds

Written 2026-08-31. The measurements are from the app's own usage ledger and engine logs on
the Poco (MediaTek Dimensity 7300, `192.168.100.171`) and the Qualcomm Device Cloud SM8650
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
| Poco (Dimensity 7300) | LFM2.5-1.2B Q4_K_M | snapshot restore | 1018 of 1036 | **184 ms** |
| Poco | Llama-3.2-3B Q4_K_M | rollback | 1044 | 453 ms |
| Poco | Qwen3-1.7B Q8_0 | rollback | 1052 | 218 ms |
| Poco | SmolLM3-3B Q4_K_M | rollback | 1054 | 455 ms |
| QDC SM8650 (8 Gen 3) | LFM2.5-1.2B Q4_K_M | snapshot restore | 1018 | **308 ms** |
| QDC SM8650 | Qwen3-1.7B Q8_0 | rollback | 1052 | 148 ms |

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

- **ExecuTorch.** The second runtime keeps its own prefix cache (27× reuse within its
  rules) but has no warm/snapshot path; a new conversation there still pays its prefill.
  The llama.cpp fleet — every GGUF — gets the full mechanism.
- **Media turns.** An attachment still re-evaluates the conversation, and a
  conversation carrying media is skipped by the conversation warm; unchanged.
- **A settings change mid-day** re-warms in the background (20 s of battery, once) —
  head and, now, the open conversation. A chat opened while a model is still loading is
  warmed by the load's own finishing warm instead.
- **Snapshot RAM.** ~12 MB held for the 1.2B hybrid at a 2k prefix; freed on unload,
  replaced on re-warm. Transformers hold nothing.
