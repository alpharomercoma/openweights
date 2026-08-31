# The slowdown of late August, found and fixed

The user's report: "almost all of our models were slowed down... even the LFM2.5 1.2B
QAD which I believe was our best model. This is a major regression." Confirmed, dated,
root-caused, fixed, and verified on both phones on 2026-08-31. It was never the
engines: it was the prompt being rewritten mid-conversation, and the KV cache paying
for it.

## The forensics

The usage ledger is the record that dated it. LFM2.5-1.2B QAD decode by day: 31.5
tok/s (Aug 28), 26.7 (Aug 29), 21.4 (Aug 30). Per-reply rows showed the sharper
signal: consecutive turns with `cachedTokens = 0` - full re-prefills of the whole
conversation - and time-to-first-token of 22 to 40 seconds on 2-3k prompts. Turns
with cache reuse showed suffix prefill as slow as 39 tok/s where a fresh prompt
prefills at 120-140: the "reused" turns were re-reading almost everything after an
early divergence point.

The engine already logged the answer. A live three-turn chat produced:

    kv: diverged at 312 of 1227 cached, prompt 440, re-reading 128
    kv: rollback to 312 refused (recurrent state), re-reading all 440

Token 312 is where the tool block begins. Two code paths rewrote the system region
mid-conversation by stripping the tool definitions out of the rendered prompt:

- the **withdrawal pass** (a model that asked for a tool with its budget spent),
  shipped Aug 15 in 4c08a2c;
- the **prose-only denial repair**, shipped **Aug 29** in a8398a7 - the exact day the
  ledger turns down - and firing on ordinary turns.

Each strip invalidated the cache from the tool block onward. On a transformer that is
a near-full re-read; on a hybrid like LFM2.5, whose recurrent cache refuses partial
rollback, it is a full reset - and the cache the stripped pass leaves behind lacks the
block, so the *next* turn pays a second full re-read to put it back. Two of three
turns in a trivial chat paid complete re-prefills. That is the whole regression:
prefill thrash experienced as "everything is slow", on every model, worst on the
hybrid the user liked most.

## The fix

The rendering is now byte-stable for the conversation: withdrawal and denial repair
gate the *parsing* (a call written on those passes is skipped - tests pin it) and say
their piece in the appended message at the tail, which costs fifteen tokens instead
of the cache. Re-measured on the same conversation shape, same device:

| | before | after |
|---|---|---|
| kv divergences in 3 turns (LFM, tools used) | 2 full re-reads | **0** |
| mid-conversation time-to-first-token | 12.2 s | **296 ms** |
| in-app session cache-hit banner | - | CH75-87% |

On the Snapdragon (Qwen3 Q8), the only remaining divergences are a few hundred tail
tokens where Qwen3's own template strips earlier turns' thinking - a model-template
property, with partial rollback working as designed.

Stop is also now instant-feeling: `llama_set_abort_callback` ends the graph mid-batch
(previously Stop waited out whatever 512-token prefill batch was in flight), aborted
decodes report CANCELLED rather than ERROR, and a mid-decode abort drops the
half-written position (or resets a cache that cannot drop it). Measured tap-to-ready:
about a second.

## What it was not

- Not the engines: engine-level parity decode rates were normal all along.
- Not temperature: the user said so, and the battery read 33.7 C mid-repro.
- Not contention: no watches existed, nothing else held the engine.
- Not the 32k auto-sized context per se, though a 32,768-token window on a 1.2B model
  deserves its own scrutiny for memory footprint.

## The rule it leaves behind

Nothing may rewrite bytes the KV cache has already read. Anything that must vary per
pass - instructions, refusals, notes - belongs at the tail. The engine's `kv:` log
line is the regression test: any change that reintroduces a mid-prompt divergence
shows up there on the first tool turn.
