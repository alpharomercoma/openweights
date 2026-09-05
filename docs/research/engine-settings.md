# Nineteen settings a competitor exposes, and why eighteen of them stay out

PocketPal offers a settings sheet with sixteen generation controls, three model-memory
switches, a batch size, a physical batch size and a thread count. The question is which of
them OpenWeights should have. The answer is none of them on the sheet, and one of them
measured as an engine constant.

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
| batch size, physical batch size | **measure first** | the one open question. `BatchSizeBenchmark` |

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

It has not been run. The cloud device had its reservation lapse mid-session and the phone
was at 14% and unplugged. `ModelLoadParams.batchTokens` and `microBatchTokens` exist,
default to 512, reach libllama, and are exercised only by that benchmark, which is the same
footing `kvCacheQuantized` and `speculation` are on: present, measurable, and moved only by
a measurement.

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
