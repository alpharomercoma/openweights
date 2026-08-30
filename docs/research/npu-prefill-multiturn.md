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

| model | prefill tok | prefill s | t/s | decode tok | decode s | t/s | total s | prefill share |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Q4_K_M | 14,273 | 114.4 | 124.8 | 6,487 | 174.7 | 37.1 | **289.1** | 39.6% |
| Q8_0 | 11,594 | 62.2 | **186.5** | 6,477 | 203.7 | 31.8 | **265.9** | 23.4% |
| F16 | 11,512 | 258.8 | 44.5 | 6,485 | 382.9 | 16.9 | **641.7** | 40.3% |
| BF16 | — | — | **5.1** | — | — | **4.2** | — | — |

BF16 was run on a reduced sample because it is unusably slow; its rates are the
mean of 25 turns. There is no accelerated BF16 kernel on this CPU — the
microbenchmark puts BF16 matmul at 25,873 ms against F16's 2,325 ms for one
128-token pass — so **BF16 is not a viable option on this hardware at all**.

Two results here are worth more than the NPU question:

- **Q8_0 beats Q4_K_M end to end** (265.9 s against 289.1 s) despite being 1.8×
  the bytes, because KleidiAI has an accelerated Q8_0 kernel and none for Q4_K:
  prefill runs at 186.5 t/s against 124.8. Decode is worse (31.8 against 37.1),
  as bandwidth predicts, but on this workload prefill wins the trade.
- **F16 is 2.4× slower than Q8_0 overall.** Unquantised is not a neutral choice.

## Measured: matmul time for the model's real shape mix

Per forward pass, summing the 92 matmuls LFM2.5-1.2B actually performs
(32×[2048→8192], 16×[8192→2048], 10×[2048→6144], 22×[2048→2048], 12×[2048→512]):

| width | NPU int8 | CPU Q8_0 | CPU F16 | CPU BF16 | CPU Q4_K | NPU × vs Q8_0 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 16 | 123.5 | 351.7 | 434.3 | 3,399.5 | 319.8 | 2.85 |
| 32 | 138.8 | 551.4 | 713.1 | 6,618.9 | 518.8 | 3.97 |
| 64 | 153.8 | 923.1 | 1,256.3 | 13,037.3 | 847.5 | 6.00 |
| 128 | 218.7 | 1,669.0 | 2,325.0 | 25,872.7 | 1,565.9 | 7.63 |
| 256 | 446.6 | 3,165.3 | 4,500.0 | 51,557.9 | 2,995.0 | 7.09 |
| 512 | 667.1 | 6,122.6 | 8,766.2 | 102,922.9 | 5,892.9 | 9.18 |

At the median real width of 50 tokens the NPU is worth about 5×, not the 10–19×
the wide-batch figures suggest.

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

## Conclusion

On this device, model, runtime and workload, prefill-only NPU offload with CPU
decode is **projected — not measured — to improve end-to-end latency by 1.07× to
1.47×**, depending on precision and on an unmeasured parameter. Decode accounts
for 60–77% of wall time, which caps an ideal free-prefill implementation at
1.31× for Q8_0, 1.65× for Q4_K_M and 1.68× for F16.

**That does not justify building the backend now**, given that the projection
omits conversion and copy costs, that the strongest case (F16) is also the
slowest configuration overall, and that the whole margin depends on a long tail
of a few turns. It is not the same as saying the NPU cannot pay for itself: a
17–32% latency reduction is not nothing, and a workload with longer uncached
prompts — RAG documents, large tool results, frequent cache invalidation — would
move these numbers.

**The cheaper win is already on the table and needs no NPU at all: shipping Q8_0
instead of Q4_K_M was 8% faster end to end on this workload**, for 1.8× the
download. That is worth its own decision.

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
