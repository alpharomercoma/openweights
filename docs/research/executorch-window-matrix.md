# Does the exported context window matter? ExecuTorch on four chips

*2026-09-07. The question, the design as it was actually run, what was found, and what it
cost. The rendered tables are in [window-matrix.md](window-matrix.md); this note reads
them. Raw and graded reports: `tools/eval/results/*8da4w-*k*.json`; quarantined cells:
`tools/eval/results/invalid-nobos/`.*

## The question

ExecuTorch fixes the context window at export. We exported the same weights at 2k, 4k, 8k,
16k and 32k and asked whether the window changes answer quality, tool calling, time to first
token or time per token, across the Dimensity 9400 in hand and the Snapdragon 8 Elite,
Tensor G5 and Exynos 2400 in Firebase Test Lab. And whether the full 5 windows x 5 models x
4 chips sweep, about 50 device-hours, was worth running.

## The short answer

- **No window effect on answers or tool calls was found that stands out from the runtime's
  own run-to-run variation.** Between any two windows of LFM2.5-1.2B on the same phone, the
  raw token streams agree on 12 to 19 of 90 prompts. Running the *same file twice* on the
  same phone agrees on 13 to 15 of 90. Parsed BFCL tool calls agree on 24 to 28 of 30 between
  windows and 23 to 25 of 30 between repeats. Grades swing by up to five prompts per set
  between runs of the *same* file (Qwen3 2k on the Poco: GSM8K 20 then 17, IFEval 15 then
  18) and by similar amounts between windows in both directions (1.2B GSM8K on the Poco: 17,
  16, 17, 13, 12 from 2k to 32k; 2.6B GSM8K on the Exynos: 10 at 4k, 18 at 32k). The
  ExecuTorch runtime is not deterministic run to run on these phones. With one repeat per
  control cell this is a screen, not a proof: a directional window effect smaller than that
  variation cannot be excluded, and a stronger test would be three to five counterbalanced
  runs per export with a predeclared equivalence margin.
- **Speed is unchanged by the window, on the Poco, where it was measured in a paired way.**
  The fixed-prompt probe (929-token prompt, 160-token cap, same turn against same turn,
  files interleaved) puts every LFM2.5-1.2B window within 0.3 to 1.3% of the 2k export on
  prefill and within 1.1% on decode; the 2.6B within 0.5% on prefill and 2.4% on decode;
  Qwen3 32k 5.9% slower on prefill and 4.7% on decode than 2k, the one file whose cache
  (about 7 GB) is large enough to plausibly cost something in itself. Ranges of the per-turn
  ratios reach 18% for the 1.2B because one pass-one turn of the 2k reference was slow, so
  the medians are the figures to read. On the cloud phones no paired probe was run; their
  suite timings differ by up to 19% between windows (Tensor G5 1.2B decode 15.6 at 4k, 12.6
  at 32k) and are unpaired, over different replies, on phones that throttle within a
  minute, so they do not support a speed claim either way.
- **The window costs memory, allocated at load, and for a full-attention model it is
  decisive.** LFM2.5 (8 of 30 or 6 of 16 layers attend) pays 25 to 33 KB per token of
  window: resident after load 1.05 GB at 2k and 1.77 GB at 32k for the 1.2B, 1.98 and 2.94 GB
  for the 2.6B. Qwen3-1.7B (all 28 layers attend, 8 KV heads of 128) pays 224 KB per token:
  the 32k export sat at 6.1 to 8.5 GB resident after load. On the Galaxy S25 Ultra and S24+
  Samsung's Heimdall memory guard killed the test process ("Trigger Global kill before GC,
  Usage 8.67 GB, Threshold 6 GB", in the pulled logcats) after 7 to 12 prompts; on the
  12 GB Poco it ended after 6 prompts with the cause not captured; only the 16 GB Pixel 10
  Pro XL finished all 90. The 2k export ran everywhere. Those cells are marked incomplete in
  the table and carry no grade or timing.
- **The 2.6B rows measure a deployed configuration, not the model.** With the start token
  in place it reads prompts correctly, but its own template makes it reason first and 16 of
  30 GSM8K and 26 of 30 IFEval replies ran to the 640-token cap without answering. Its
  GSM8K and IFEval grades and raw identity are cap-censored; its BFCL calls (22 to 25 of
  30 at every window on every phone), load success, memory and probe speed are usable.
- **On the original question:** the evidence does not justify launching the full 5 x 5 x 4
  sweep. What it supports is an adaptive policy: compute the KV cost per window from the
  architecture, screen the endpoints (smallest and largest window) per model and chip, and
  add intermediate windows only where the endpoints diverge or fail. That is what this run
  did, and the intermediate LFM2.5 windows on the Poco added nothing the endpoints had not
  shown.

## What was actually run

| | Poco (Dimensity 9400) | 8 Elite, Tensor G5, Exynos 2400 (Test Lab) |
|---|---|---|
| LFM2.5-1.2B | 2k, 4k, 8k, 16k, 32k, full 90 prompts; 16k and 32k run twice | 4k and 32k, full 90 |
| LFM2.5-2.6B | 2k and 32k, full 90 (8k, 16k dropped: 55 min per file once the BOS fix made it reason to the cap) | 4k and 32k, full 90 |
| Qwen3-1.7B | 2k and 32k, full 90; 2k run twice | 2k and 32k, full 90 |
| Speed probe | all twelve files, two interleaved passes, three turns each, cooled below 42 C and awake before each load | not run |

Every file was loaded at its own exported window (`context=0`), greedy, thinking off,
640-token cap (384 for BFCL), the same 90 prompts (30 GSM8K, 30 IFEval, 30 BFCL) and the
same graders as the earlier five-chip run. Exports: ExecuTorch 1.4.0's own recipes,
8-bit dynamic activations, 4-bit weights in groups of 32, 8-bit embeddings, prefill chunk
2048, fp32 KV cache. Sizes are in the tables.

The other three families of the earlier matrix (Llama 3.2, SmolLM3, Gemma 3) were not
exported: two need another toolchain (optimum-executorch), one a gated checkpoint, and
the claim under test is about the runtime's static cache, which Qwen3 (full attention, a
different tokenizer and template, the same exporter) covers as a second architecture.

## Two things the matrix found that it was not looking for

**Every compiled model this app ever ran was fed no BOS token.** The 2.6B scored 0/30 on
GSM8K on all four chips, with replies that paraphrased the prompt wrongly and fell into
repetition. The same export on the Mac, with a literal `<|startoftext|>` in the prompt,
answered correctly, and the Hugging Face reference was sane. The runtime's own C++
tokenizer (`CppHFTokenizer`, the library in the AAR) shows why: it adds BOS only when
asked, the 1.4.0 JNI layer asks for zero with the constructor the app uses, and a
`TemplateProcessing` post-processor in tokenizer.json changes nothing. The app's templates
had rendered without BOS on the assumption that the runtime armed it. LFM2.5-1.2B tolerates
a missing BOS (same prompt, Mac, sane); LFM2.5-2.6B does not. Fixed in the engine: the
family's BOS is written into the prompt text, which the tokenizer encodes to the BOS id.
All 2.6B cells were re-measured with that build; the 1.2B cells stand as measured, BOS-less
like every earlier 1.2B ExecuTorch figure, and consistent among themselves. Whether Gemma
3's GSM8K looping in the earlier matrix was the same bug is an open question worth a rerun.

**A dozing phone throttles instrumentation two to five times, with wild variance.** The
first 1.2B file measured 62, 76 and 307 tok/s prefill on three consecutive turns with the
screen off. Awake and unlocked the spread collapsed to 370 to 378. The local runner now
wakes the phone and holds it awake; the thermal service's first "CPU" row is a constant
57.9 that never moves, so the probe reads the lowest live one.

## Reading the numbers with the right caveats

- The suite's prefill ms and ms/token are per-cell figures over the replies that cell
  produced; replies differ between runs, so they are not a paired speed comparison. The
  fixed-prompt probe is.
- "Prefill ms" is the runtime's prefill of the whole prompt, the engine-side part of time
  to first token, not a first-token timestamp.
- Cloud cells are an endpoint screen (4k and 32k, or 2k and 32k). They cannot rule out a
  non-monotonic effect at 8k or 16k; the Poco's five windows can, for LFM2.5.
- Test Lab phones throttle within about 40 s of load; nothing was done about it beyond
  medians. The Poco was cooled below 42 C before every probe load but not between suite
  files.
- The 2.6B reasons before answering whatever the eval asks (its chat template pre-opens
  `<think>`), so at a 640-token cap most of its GSM8K and IFEval replies never reach an
  answer: those grades and its raw identity are cap-censored (the Capped column counts the
  replies that hit the cap). Its BFCL calls, load success, memory and probe speed are not.
- Parsed-call identity is counted over the 30 BFCL prompts only; the other 60 prompts have
  an empty call list in every run and would inflate it.
- Cells marked INCOMPLETE are runs whose process ended before its set did, with no error and
  no time box recorded; they show the last observed resident memory and nothing else.

## Review

Codex reviewed the method twice, before and after the data. Round one asked for same-file
repeats, a paired speed probe separated from the task eval, honest labels for prefill and
decode figures, a three-way identity metric, memory after load, and a second architecture;
all were added. Round two asked that the conclusions be bounded by the observed run
variability rather than stated as invariance, that parsed-call identity be counted over BFCL
only, that the Qwen3 32k cells be shown as incomplete with their termination cause qualified,
that the 2.6B grades be labelled cap-censored, that the two RSS figures be separated, and
that the verdict on the full sweep be phrased as an adaptive policy rather than a nil
effect; this note and the renderer follow that. The termination cause was then confirmed
from the logcats for the two Samsung phones.

## What is where

- Exports: Hugging Face `alpharomercoma/LFM2.5-1.2B-Instruct-ExecuTorch-XNNPACK` (five
  windows), `alpharomercoma/LFM2.5-2.6B-ExecuTorch-XNNPACK` (five windows, tokenizer with a
  BOS post-processor), `alpharomercoma/Qwen3-1.7B-ExecuTorch-XNNPACK` (2k and 32k). Each
  carries a `config.json` in the variants form Discover reads, and a README with sizes and
  the KV cost per window.
- Numbers: `docs/research/window-matrix.md` (rendered), `tools/eval/results/` (raw and
  graded JSON, `repeat-` prefix for the second runs, `invalid-nobos/` for the quarantined
  cells), `~/ow-models/etexport/matrix/results-windows.log` (probe).
- Harness: `tools/eval/bench/run_cloud_windows.sh`, `window_report.py`, `run_local.sh`
  (`CONTEXT=0`, `CLASSES`, `PREFIX`), `ExecuTorchBenchmarkEval` (`-e context`).
- Cloud copies of the variants were deleted from the Test Lab bucket when the runs
  finished; Test Lab itself provisions nothing persistent.
