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

## Speed, and two things not yet explained

Median prefill / decode, tokens per second, Qwen3-1.7B:

| Engine | D9400 | 8 Gen 3 | 8 Elite | Tensor G5 | Exynos 2400 |
|---|---|---|---|---|---|
| ExecuTorch | 134 / 19.5 | 134 / 28.8 | 123 / 23.0 | 121 / 17.1 | 93 / 12.2 |
| llama.cpp | 116 / 19.8 | 216 / 16.3 | **12** / 26.5 | 119 / 9.9 | 115 / 9.8 |

Two numbers in that table are questions rather than results, and they are recorded
here so nobody quotes them as measurements of the silicon:

- **The 8 Elite's llama.cpp prefill collapsed to 10 to 16 tok/s on every model** while
  its decode was the fastest of the five. Its logcat shows it loaded
  `libggml-cpu-android_armv8.6_1.so` (score 23) where the Tensor and the Exynos loaded
  the armv9.0 variant (score 55), which fits Oryon cores having no SVE, but a variant
  choice does not explain a prefill this slow on its own. The next step is a run with
  the variant forced and the thread count logged.
- **Tensor G5 and Exynos 2400 decode at half their ExecuTorch rate on llama.cpp**, 10
  tok/s against 17 and 12. Thread placement across their small cores is the first thing
  to look at; the Dimensity and the Snapdragons do not show it.

The ExecuTorch prefill advantage held on the new phones for the families it held on
before, and the ExecuTorch decode lead is real on all five.

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
