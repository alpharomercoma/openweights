# The parity matrix on five SoCs: what three cloud phones added

On 2026-09-03 the backend-parity matrix — seven agentic cases, five model families, both
engines, greedy — was rerun on three more phones through Firebase Test Lab: a Pixel 10
Pro XL (Tensor G5), a Galaxy S24+ (Exynos 2400) and a Galaxy S25 Ultra (Snapdragon 8
Elite). With the Dimensity 9400 in hand and the Snapdragon 8 Gen 3 on Qualcomm Device
Cloud, that is five SoCs from four vendors, and the tables in
[backend-parity.md](backend-parity.md) are now ten columns wide. This note is what the
three new columns changed, and how the runs were done so they can be done again.

## What changed in the verdicts

**llama.cpp graded identically on all five, every row.** Thirty-five grades per phone,
five phones, no disagreement. The two-phone claim survived a much stronger test.

**ExecuTorch has three cross-silicon divergences now, and the reading of them changed.**
With two phones, "passes on the Dimensity, fails on the Snapdragon" was a coin with two
faces. With five:

- Qwen3, format-constraint: four SoCs answer `["mercury", "venus", "earth"]`; the
  8 Gen 3 alone answers `["mercur", …]`.
- SmolLM3, tool-result: four pass; the 8 Gen 3 alone fails, and its output opens with a
  thinking block the others do not produce.
- Llama 3.2, tool-result: the 8 Elite and the Tensor G5 pass; the Dimensity, the 8 Gen 3
  and the Exynos fail, each with a different paraphrase of "this is a response from the
  get_weather function".

So on two of the three the Snapdragon 8 Gen 3 is the outlier, one device out of five,
and on the third the split is two against three on one `.pte`. Nothing here attributes
any of it to a kernel: every run is the same XNNPACK path, and an attribution needs an
operator-level trace that a cloud device does not give.

**Text differs even where grades agree.** Gemma 3's memory case fails on every
ExecuTorch device, but the Tensor G5 answers "Whiskers" where the other four say
"Bagis", and each phone's failing answer to the format case is a different sentence.
Greedy makes a run reproducible on a phone; it says nothing about the phone next to it.

## Speed, and two things that were not explained until the probe ran

Median prefill / decode, tokens per second, Qwen3-1.7B:

| Engine | D9400 | 8 Gen 3 | 8 Elite | Tensor G5 | Exynos 2400 |
|---|---|---|---|---|---|
| ExecuTorch | 134 / 19.5 | 134 / 28.8 | 123 / 23.0 | 121 / 17.1 | 93 / 12.2 |
| llama.cpp | 116 / 19.8 | 216 / 16.3 | **12** / 26.5, then 236 / 24.8 | 119 / 9.9 | 115 / 9.8 |

Two numbers in that table were questions rather than results when it was first written,
and the thread sweep below answered one of them the same day; both are kept so nobody
quotes the first reading as a measurement of the silicon:

- **The 8 Elite's llama.cpp prefill collapsed to 10 to 16 tok/s on every model** while
  its decode was the fastest of the five. Its logcat shows it loaded
  `libggml-cpu-android_armv8.6_1.so` (score 23) where the Tensor and the Exynos loaded
  the armv9.0 variant (score 55), which fits Oryon cores having no SVE, but the variant
  was not the cause: it was the batch thread count, answered below.
- **Tensor G5 and Exynos 2400 decode at half their ExecuTorch rate on llama.cpp**, 10
  tok/s against 17 and 12. Thread placement across their small cores is the first thing
  to look at; the Dimensity and the Snapdragons do not show it.

The ExecuTorch prefill advantage held on the new phones for the families it held on
before, and the ExecuTorch decode lead is real on all five.

### What the thread sweep found

`SpeedProbe` (`tools/eval/run_matrix_ftl.sh <device> 36 <prefix> probe`) loads
Qwen3-1.7B Q8_0 and moves one thread count at a time, a 406-token prompt for prefill
and a 24-token one for decode, on the same three cloud phones, 2026-09-03:

| Prefill, tok/s | 2 threads | 4 | 6 | 8 |
|---|---|---|---|---|
| Snapdragon 8 Elite | 163 | 318 | **345** | 92 |
| Tensor G5 | 69 | **130** | 97 | 89 |
| Exynos 2400 | 67 | **115** | 88 | 92 |
| Dimensity 9400 (in hand) | 46 | 73 | 84 | **96** |

**The Dimensity had been losing a quarter of its prompt speed since 23 August.** The
thread rule of that day dropped a chip's slowest cluster, and described the Dimensity
as eight cores at one speed. Its kernel says four at 2.4 GHz, three at 3.3 and one at
3.73, so the rule handed it four threads, and four is the 73 in the table. The four
slow cores are Cortex-A720s, big cores by design; by frequency alone they look like
the Snapdragon 8 Gen 3's two A520s, which are the cores the rule exists to drop, and
which do half the work per step where an A720 at 2.4 GHz does two thirds of an X4's.
The rule now reads the part number from `/proc/cpuinfo` and drops a cluster only when
its cores are in-order little cores; the frequency shape is the fallback for a kernel
that will not say. The Dimensity is back to eight, measured.

**The 8 Elite's prefill was a thread count, and the app's own.** With eight batch
threads a 24-token prompt costs a fixed 3.5 seconds per call, whatever its length, and a
406-token one runs at 92 tok/s; with six the same phone prefills at 345 tok/s, the
fastest of the five. The rule in `CpuTopology` had answered "every core" for a chip
whose fast cluster is a minority, on the SM8650 evidence that the little cores are
worth their place in the barrier; the Elite's six other cores are not little, and with
all eight busy the step waits on whichever thread the scheduler put behind the caller.
The rule now holds two cores back on that shape (`SPARE_CORES`), which is what the
measurement supports and no more: one spare was not measured, and the two-plus-six
budget chips get the same answer unmeasured. The Elite's llama.cpp column was rerun
with the fix: median prefill went from 12 to 236 tok/s on Qwen3 Q8_0 and from 6 to 16
up to 43 to 111 on the four Q4_K_M models, every grade unchanged, and the class took
432 seconds against 501. That column in [backend-parity.md](backend-parity.md) is
the rerun.

**The Tensor G5 and Exynos 2400 decode rate is not a thread count.** Decode falls off a
cliff with more threads on both (Tensor: 5.2 tok/s at two threads, 0.5 at eight;
Exynos: 9.1 and 2.1), but the app's default of half the cores decodes no differently
from two threads, so the halved rate against ExecuTorch has another cause. Both chips
report 128-bit SVE with KleidiAI engaged, the path on which llama.cpp's SVE kernels
are known to trail plain NEON, and the next probe is the armv8.6 variant forced on
them. Their prefill also peaks at four threads where the rule gives six, a 25 to 30
percent gap that stands against the SM8650's measured six; a rule that drops two
clusters would fix these two and break that one, so it stays a recorded number rather
than a change.

**Racked phones throttle within a minute.** On the Tensor the last short-prompt runs
decoded at half the first ones, forty seconds apart. A cloud number is a number under
that condition.

## How the cloud runs work

`tools/eval/run_matrix_ftl.sh <model-id> <os-version> <prefix> <engine>` runs one
engine's parity class on one Test Lab device as one matrix. Three rules, each learnt by a
failed run:

1. **One engine per matrix.** Test Lab caps a physical-device run at 45 minutes, and a
   class took 8 to 16 minutes depending on the phone.
2. **The models come from a bucket, and the bucket is also the results bucket.** Test
   Lab copies every pushed file into the results bucket before the run, and a copy
   between storage locations gives up after thirty seconds on a gigabyte file. Every run
   against the default US multi-region results bucket failed at validation from a
   regional models bucket; `--results-bucket` pointed at the models bucket makes the copy
   stay in one location. Fifteen gigabytes uploaded once serve every run.
3. **Reports are written where a path can pull them.** The evals write to the test
   package's external files directory, and `--directories-to-pull` brings them home. That
   also removed the `run-as` dependency that had made Qualcomm Device Cloud awkward:
   `run_matrix.sh` pulls the same directory now.

Devices were picked from `gcloud firebase test android models list`, where a Samsung
codename ending in `s` is Exynos and one ending in `q` is Snapdragon, and
`directAccessSupported` in `models describe` says whether the unit is also reachable
interactively from Android Studio. The catalog had no MediaTek flagship, so the
Dimensity stays the phone in hand.
