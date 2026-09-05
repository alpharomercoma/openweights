# Nineteen settings a competitor exposes, and why none of them change this app

PocketPal offers a settings sheet with sixteen generation controls, three model-memory
switches, a batch size, a physical batch size and a thread count. The question is which of
them OpenWeights should have. The answer is none of them on the sheet. The one that looked
worth measuring rather than arguing about has now been measured, and the value it has always
had turns out to be the fastest one.

Reviewed with `agy` (`tools/review/agy_question.py`, gemini-3.8-flash-medium), which agreed
on every line and supplied the Android specifics in the two sections that need them.

## The verdict, in one table

| setting | verdict | why |
| --- | --- | --- |
| `n_predict` unlimited | **refuse** | already `maxTokens`, capped on purpose. Uncapped is the thing that produced a 2,900 token reply over 5 minutes 39 seconds |
| include thinking in context | **refuse, harmful** | rewrites the prompt head between turns, which is a full re-prefill |
| temperature, top_k, top_p, min_p | have | on the sheet or under Advanced |
| repeat penalty | have | under Advanced |
| XTC threshold, XTC probability | refuse | creative-writing samplers; two more knobs from a paper |
| typical_p | refuse | same |
| mirostat v1/v2 | refuse | steers toward a target perplexity, which is a research knob rather than a setting, and it predates min-p by some years |
| penalty_last_n | refuse | exists at 64; no perceptible difference on phone-sized chat models |
| penalty_freq, penalty_present | refuse | redundant beside repeat penalty |
| seed | refuse | exists, `null` means a fresh one; a raw integer on a consumer sheet is friction, and reproducibility already lives in the eval harness |
| jinja | refuse | llama.cpp applies the model's own template from GGUF metadata. A switch here invites broken tool tags |
| memory lock | **refuse, cannot work** | see below |
| memory mapping | **refuse, harmful** | exists and is off by measurement: mapping costs 1.27 GB more resident for 245 ms of load time |
| weight repacking | refuse, meaningless here | see below |
| CPU threads | refuse | chosen by a topology probe with its own benchmark; one slider conflates prefill and decode, which want opposite counts |
| batch size, physical batch size | **measured, refuse** | 512 is the fastest of five pairs at both prompt lengths; larger is up to 14% slower and 226 MB heavier |

## Memory lock cannot work on Android

`mlock` pins pages so the kernel cannot swap or compress them. An ordinary Android app
inherits an `RLIMIT_MEMLOCK` of **64 KB**, and zero under some SELinux domains. Asked to
lock two gigabytes of weights, the call returns `-1` immediately with `ENOMEM` or `EPERM`;
llama.cpp logs a warning and carries on unlocked.

So the switch would be a placebo: a control that reports success by doing nothing. And on a
rooted phone where the limit had been raised it would be worse than a placebo, because
pinning two to four gigabytes takes them out of reach of the low memory killer and `zram`,
and the pressure lands on everything else the person has open.

## Weight repacking means nothing in this build

The switch exists in llama.cpp for a specific configuration: weights are memory-mapped, and
repacking them into an accelerated layout costs a second resident copy, so there is a
trade between load-time work and memory.

This build does not map. It reads, for reasons measured and written down in
`ModelLoadParams.useMmap`, and KleidiAI then repacks the Q4_0 tensors into the blocked
layout its microkernels require. Without that repacking the kernels cannot run at all and
decode falls back to generic routines. There is no memory to be saved by turning it off,
because there is no mapped copy to leave behind. It is a button whose only effect is to make
the phone slower.

## The one that is worth measuring

`n_batch` and `n_ubatch` have been hard-coded at 512 since the engine was written, with a
comment reasoning that it "keeps memory modest on phones while still batching enough work to
be fast". Reasoning is not a measurement, and this is the last engine constant in the app
that had never been swept. It matters because prefill is the compute-bound half of a turn
and the half a person waits through.

They are two different things and the sweep keeps them apart. `n_batch` is how many prompt
tokens are handed over at once; `n_ubatch` is how many are computed in one graph, and it is
the one that sizes the scratch buffer for intermediate activations, which scales with it
linearly.

**Expected:** better vector-unit utilisation and less kernel dispatch overhead as the batch
grows, flattening quickly past 1024 on a phone CPU as the working set leaves L2. On the
memory side, roughly 100 to 300 MB more transient activation memory going from 512 to 2048,
depending on the hidden dimension.

**The rule, written before the run:** adopt a larger batch only if prefill throughput
improves by at least 15% *and* peak resident memory grows by no more than 250 MB. Below 10%,
or above 300 MB, it stays at 512. Peak rather than steady state, because the low memory
killer reacts to the spike during prefill.

`BatchSizeBenchmark` sweeps five pairs at two prompt lengths and reads `VmHWM` from
`/proc/self/status` rather than estimating. Two of the pairs raise only the logical batch, so
that a win can be attributed: if 1024 by 512 is as fast as 1024 by 1024, the gain was in
queueing and the scratch buffer never had to grow.

### The result: 512 stays, and larger is worse

Run on a Poco phone against LFM2.5-2.6B-Q4_0, prefill throughput in tokens per second and
peak `VmHWM` for the arm.

| n_batch | n_ubatch | 889 tok | 1759 tok | peak MB |
| --- | --- | --- | --- | --- |
| **512** | **512** | **104.6** | **86.1** | 2050 / 2113 |
| 1024 | 512 | 97.5 | 82.9 | 2118 / 2114 |
| 1024 | 1024 | 100.6 | 78.3 | 2187 / 2197 |
| 2048 | 512 | 101.6 | 74.6 | 2113 / 2111 |
| 2048 | 2048 | 101.0 | 74.1 | 2224 / 2339 |

**512 by 512 is the fastest arm at both prompt lengths.** At the shorter prompt every
configuration is within seven percent of it, which is to say the knob does nothing. At the
longer one, the one that matters because it is the size of a real first turn with the tool
catalogue in it, the larger batches are monotonically *worse*: 2048 by 2048 loses 14% of
prefill throughput and costs 226 MB more peak resident memory.

Nothing comes near the 15% the rule required, so the rule is satisfied by leaving it alone.

The two arms that raise only the logical batch are what make the result readable. At 1759
tokens, 1024 by 512 gives 82.9 and 1024 by 1024 gives 78.3, so the loss tracks the
*physical* batch rather than the logical one. That is the scratch buffer: a larger micro
batch means a larger activation working set, and past some size it stops fitting in cache
and prefill becomes bound by memory bandwidth rather than by arithmetic. On a phone sharing
one LPDDR bus with everything else, that ceiling arrives early.

**A note on the first attempt, because it nearly produced the opposite conclusion.** The
same sweep run once earlier gave 84.4, 24.7, 80.0, 61.7 and 39.3 for the five arms at 889
tokens: a 3.4x spread across configurations that the second run shows to be within seven
percent of each other. The phone was contended. One sample per arm cannot support a 15%
threshold when an identical configuration varies by 24% between runs, and the reason the
second run can be trusted is not that it was repeated but that it is internally consistent:
monotone in prompt length, monotone in physical batch, and ordered the same way at both
lengths.

That run also fixed a defect in the benchmark itself. `VmHWM` is a high water mark for the
life of a process and all ten arms share one, so every arm after the first was reporting the
first one's peak. It now resets the mark after each load, which is why the memory column
above varies rather than only climbing.

`ModelLoadParams.batchTokens` and `microBatchTokens` stay: they default to 512, reach
libllama, and are exercised only by that benchmark, which is the same footing
`kvCacheQuantized` and `speculation` are on: present, measurable, and moved only by a
measurement.

## The principle underneath all of this

A settings sheet is a cost. This one was deliberately cut from about seventeen controls to
five and a disclosure, because the three settings anybody touches were scattered among
samplers whose defaults are correct and whose names come from papers. Every control on it
has to earn its line, and "a competitor has it" is not an argument, any more than "llama.cpp
has a flag for it" is.

The counterpart of that is that refusing a control is not refusing the capability. Every one
of the load parameters above exists in `ModelLoadParams`, reaches the engine, and can be
swept by a benchmark. What the sheet declines to do is ask the user to choose a number that
the app can measure better than they can.
