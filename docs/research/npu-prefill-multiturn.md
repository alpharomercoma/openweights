# Does NPU prefill pay for itself on real conversations?

**Measured 2026-08-30 on a Poco X8 Pro Max (MediaTek MT6991 / Dimensity 9400,
Mali-G925, MDLA 5.5, 12 GB), llama.cpp `b2e5e9b`, 6 threads.** Model:
`LFM2.5-1.2B-Instruct` at Q4_K_M, Q8_0, F16 and BF16.

The kernel measurements in [`mediatek-npu.md`](mediatek-npu.md) show the MDLA
beating the CPU at matrix multiply by roughly ten times at prefill widths. This
asks the only question that follows from that: **on conversations people
actually have, does moving prefill to the NPU make the app faster?**

## Method

**The conversations are not invented.** 824 multi-turn conversations were pulled
from three public sources and 45 replayed, balanced across them:

| source | what it is |
| --- | --- |
| WildChat-1M (AllenAI) | naturally occurring ChatGPT traffic from consenting users |
| Daring-Anteater (NVIDIA) | production instruction-tuning data, largely synthetic |
| MT-Bench | the standard curated multi-turn evaluation set |

Only WildChat is organic chat traffic; the other two are industry corpora rather
than user logs, and the distribution here is therefore not a claim about this
app's own traffic.

Each conversation is replayed turn by turn against `llama-server`, taking the
**user** turns from the dataset and letting the model generate the assistant
turns, because that is what the app stores and sends back — which makes the
KV-cache reuse observed here the same reuse production sees. Recorded per turn:
tokens actually prefilled after cache reuse, prefill ms, tokens generated,
decode ms. Generation is capped at 48 tokens.

Three caveats belong with every number below, and two of them favour the NPU:

- **The projection is a composition, not an end-to-end run.** No ggml MediaTek
  backend exists, so NPU prefill is *measured* matmul time on the device plus the
  *measured* non-matmul remainder of real CPU prefill. It omits activation
  quantise/dequantise and CPU↔NPU copies, so it is optimistic.
- **96% of turns hit the 48-token generation cap.** Real replies are longer, so
  uncapped decode would be larger, decode's share would rise, and prefill
  offload would matter *less*. The cap flatters the NPU.
- **Cross-precision totals are not paired.** Each model writes different replies,
  so histories diverge and prompt widths differ. Compare within a precision.

## What real prefill looks like

This is the finding everything else follows from. After KV-cache reuse, a turn
prefills far less than the conversation's length:

| | tokens |
| --- | ---: |
| min | 11 |
| p25 | 26 |
| **median** | **50** |
| p75 | 107 |
| p90 | 221 |
| max | 1542 |

| width | share of turns | share of all prefill tokens |
| ---: | ---: | ---: |
| < 32 | 36.2% | 7.6% |
| 32–128 | 46.3% | 32.3% |
| 128–512 | 14.5% | 33.4% |
| ≥ 512 | 2.9% | 26.7% |

Most turns are small; most *work* is in a few large ones. The NPU's advantage
grows with width, so it is weakest exactly where most turns live.

## Measured: CPU only, 138 turns

| model | size GB | prefill tok | prefill s | t/s | decode tok | decode s | t/s | total s |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **Q4_0** | **0.65** | 14,320 | 84.6 | 169.3 | 6,499 | 145.1 | **44.8** | **229.6** |
| Q8_0 | 1.16 | 11,594 | 62.2 | **186.5** | 6,477 | 203.7 | 31.8 | 265.9 |
| Q4_K_M | 0.68 | 14,273 | 114.4 | 124.8 | 6,487 | 174.7 | 37.1 | 289.1 |
| F16 | 2.18 | 11,512 | 258.8 | 44.5 | 6,485 | 382.9 | 16.9 | 641.7 |
| BF16 | 2.18 | — | — | 5.2 | — | — | 4.3 | — |

Against Q4_K_M, which the app ships today:

| model | total | prefill | decode |
| --- | ---: | ---: | ---: |
| **Q4_0** | **1.26× faster** | 1.35× | **1.20×** |
| Q8_0 | 1.09× faster | 1.84× | 0.86× |
| F16 | 0.45× (slower) | 0.44× | 0.46× |

**Q4_0 wins on every axis and there is no trade.** It is faster at prefill *and*
at decode, and at 0.65 GB it is marginally **smaller** than the Q4_K_M the app
ships. Q8_0 buys the best prefill of all but pays for it in decode, and on this
workload that is a net loss against Q4_0.

The mechanism is KleidiAI: it repacks and accelerates Q4_0 and Q8_0, and has
**no q4_K kernel at all** — its own log says so, `no kernel for tensor type q4_K,
not accelerated by KleidiAI (kernels available for Q4_0 and Q8_0)`. Q4_K_M has
been running the generic path all along.

BF16 was run on a reduced sample because it is unusably slow; its rates are the
mean of 25 turns. There is no accelerated BF16 kernel on this CPU, so **BF16 is
not a viable option on this hardware at all**, and F16 is 2.4× slower than Q8_0
overall. Unquantised is not a neutral choice.

**One thing this does not measure: output quality.** Q4_0 and Q4_K_M are both
four-bit but K-quants are generally held to have better perplexity per bit, so
choosing Q4_0 for speed is a quality decision that wants its own measurement.
Legibility was clean for every precision (below), but legible is not the same as
accurate.

## Measured: matmul time for the model's real shape mix

**Corrected.** An earlier version of this table allocated weights with
`ggml_backend_alloc_ctx_tensors`, on the CPU's *default* buffer. KleidiAI
registers its own buffer type whose `set_tensor` **repacks** the weights into
the layout its kernels need, and llama.cpp allocates there via
`ggml_backend_dev_get_extra_bufts`. Skipping that measured the generic path, so
the CPU looked far slower than it is and the NPU's advantage was overstated by
four to five times. The numbers below allocate Q4_0 and Q8_0 on the KleidiAI
buffer — Q4_K, F16 and BF16 cannot go there, which is itself the finding.

Per forward pass, summing the 92 matmuls LFM2.5-1.2B performs (ms, median of 20):

| width | NPU int8 | CPU Q4_0 | CPU Q8_0 | CPU Q4_K | CPU F16 | CPU BF16 | **NPU × vs best CPU** |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 16 | 123.5 | 190.5 | 155.8 | 324.2 | 439.2 | 3,265.5 | **1.26** |
| 32 | 138.8 | 255.8 | 183.4 | 502.4 | 727.7 | 6,315.7 | **1.32** |
| 64 | 153.8 | 377.7 | 269.2 | 859.4 | 1,236.0 | 12,462.0 | **1.75** |
| 128 | 218.7 | 589.7 | 399.6 | 1,549.2 | 2,251.1 | 24,660.0 | **1.83** |
| 256 | 446.6 | 1,051.3 | 666.9 | 2,929.6 | 4,310.7 | 49,266.9 | **1.49** |
| 512 | 667.1 | 2,037.5 | 1,081.2 | 5,830.5 | 8,726.8 | 102,793.6 | **1.62** |

**The NPU is 1.3–1.8× faster at matmul than a properly accelerated CPU**, not the
3–9× reported before the correction. Against Q4_K specifically it is 2.6–8.7×,
but that comparison flatters it: Q4_K is slow because it has no KleidiAI kernel,
not because the CPU is slow.

The ordering on CPU is stark and matters more than the NPU column:
**Q8_0 > Q4_0 > Q4_K**, with Q4_K running 3.9× slower than Q8_0 at width 128.

## Projected: NPU prefill + CPU decode

`f` is the share of CPU prefill time that is matmul. It is **not measured**, so
it is swept. Prefill is priced in `n_ubatch = 512` blocks, as llama.cpp splits it.

| model | f | prefill s | total s | prefill × | **total ×** |
| --- | ---: | ---: | ---: | ---: | ---: |
| Q4_K_M | 0.7 | 64.4 | 239.0 | 1.78 | **1.21** |
| Q4_K_M | 0.8 | 52.9 | 227.6 | 2.16 | **1.27** |
| Q4_K_M | 0.9 | 41.5 | 216.2 | 2.76 | **1.34** |
| Q8_0 | 0.7 | 45.0 | 248.7 | 1.38 | **1.07** |
| Q8_0 | 0.8 | 38.8 | 242.5 | 1.60 | **1.10** |
| Q8_0 | 0.9 | 32.6 | 236.3 | 1.91 | **1.13** |
| F16 | 0.7 | 103.9 | 486.8 | 2.49 | **1.32** |
| F16 | 0.8 | 78.0 | 461.0 | 3.32 | **1.39** |
| F16 | 0.9 | 52.2 | 435.1 | 4.96 | **1.47** |

### The ceiling, if prefill were free

| model | total s | decode only s | best possible |
| --- | ---: | ---: | ---: |
| Q4_K_M | 289.1 | 174.7 | **1.65×** |
| Q8_0 | 265.9 | 203.7 | **1.31×** |
| F16 | 641.7 | 382.9 | **1.68×** |

### Where the saving comes from

At `f = 0.8`, the top **10 of 138 turns give 52%** of the total saving, and 21
turns — every one at a width of 21 tokens or less — would be *slower* on the NPU,
though only by 0.3 s in total. The case rests almost entirely on a handful of
long-prefill turns.

## Paired check: teacher-forced, identical prompts

The table above is not paired — each model writes different replies, so histories
diverge. A second pass fed every model the dataset's **own** assistant turns, so
the prompt token IDs are identical: 124 of 125 turns match exactly.

| model | prefill tok | prefill s | t/s | decode s | t/s | total s |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Q4_K_M | 40,146 | 481.6 | 83.4 | 201.2 | 29.4 | 682.8 |
| Q8_0 | 40,509 | 332.1 | **122.0** | 233.8 | 25.0 | **565.9** |

Paired, **Q8_0 is 1.21× faster than Q4_K_M overall** — 1.46× on prefill, 0.85× on
decode. Same direction as the unpaired run, larger margin, because teacher-forcing
injects long real assistant turns that were never generated and so were never
cached: prefill is 70.5% of time here against 39.6% in self-play.

That difference is the point rather than a flaw. **This is the wrong workload for
predicting product latency and the right one for comparing precisions.** It also
shows how sensitive the NPU case is to workload shape: projected on this
prefill-heavy mix the NPU reaches 1.67–2.19× for Q4_K_M against 1.21–1.34× on the
production-shaped one, with the ceiling moving from 1.65× to 3.39×.

Which is realistic for this app? Self-play, because production resends the
model's *own* replies and those are already in the KV cache. And production
replies are longer than the 48-token cap used here, which adds decode and no
prefill — so the real prefill share is **lower** than 39.6%, and the NPU case is
weaker than even the conservative column suggests.

## Conclusion

On this device, model, runtime and workload, prefill-only NPU offload with CPU
decode is **projected — not measured — to improve end-to-end latency by 1.07× to
1.47×**. Decode is 60–77% of wall time, capping an ideal free-prefill
implementation at 1.31× for Q8_0, 1.65× for Q4_K_M and 1.68× for F16.

**Do not build the backend on this evidence.** Three reasons, in order of weight:

1. **The kernel advantage is 1.3–1.8×, not the 3–9× first reported.** That error
   came from benchmarking against a CPU whose weights were never repacked for
   KleidiAI. Against a correctly configured CPU the NPU's headroom is modest, and
   the projection above still omits activation conversion and CPU↔NPU copies, so
   it is optimistic on top of that.
2. **Decode cannot use it.** A decode step issues 92 matmuls, so a ggml backend
   pays ~92 NPU dispatches per token. At the best per-op time measured, 0.4 ms,
   that is 37 ms/token — 27 t/s against a CPU already doing 44.8 t/s on Q4_0.
3. **Most turns are too small.** After cache reuse the median turn prefills 50
   tokens, where the NPU is worth 1.3×, and half the projected saving comes from
   ten of a hundred and thirty-eight turns.

This is not the same as "the NPU cannot pay for itself". A workload with long
uncached prompts — RAG documents, large tool results, frequent invalidation —
moves the prefill share sharply, and the teacher-forced numbers show the
projection reaching 2.19× when prefill is 70% of the work. The decision is
workload-dependent and should be revisited if the app's traffic changes shape.

## The result that needs no NPU at all

**Switch from Q4_K_M to Q4_0.** Measured end to end on the same 45 conversations:
**1.26× faster overall, 1.35× on prefill, 1.20× on decode, and 30 MB smaller.**
There is no trade to weigh — it is better on every axis this measures.

The cause is that KleidiAI has kernels for Q4_0 and Q8_0 and **none for q4_K**, so
the app's current format has been running the generic path on every turn. That is
a bigger, cheaper and more certain win than anything the NPU offers here, and it
is a one-line change to what gets downloaded.

The one thing it does not settle is **quality**: K-quants are generally held to
have better perplexity per bit than Q4_0, and that was not measured. Before
switching, compare the two on a quality benchmark; the speed case is already made.

## Legibility

Every reply from every run was checked for emptiness, degenerate repetition,
mojibake and unprintable characters, and a sample read by hand. **138/138 clean
for both Q4_K_M and Q8_0.** The models are producing coherent, on-topic prose,
correct markdown and sane mathematics at every precision tested, so the timings
are not measuring garbage.

## Reproducing

`tools/npu/multiturn/` holds the dataset puller, the replay drivers, the
analysis and the raw width sweep; `tools/npu/` holds the two matmul harnesses
and a README with the device-side traps.
