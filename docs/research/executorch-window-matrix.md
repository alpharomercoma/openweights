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

- **The window does not change what the model says, beyond what the runtime changes on its
  own.** Between any two windows of LFM2.5-1.2B on the same phone, the raw token streams
  agree on 12 to 19 of 90 prompts and the parsed tool calls on 84 to 88 of 90. Running the
  *same file twice* on the same phone gives the same figures: 13 to 15 of 90 raw, 83 to 85
  of 90 calls. Grades move by one to five prompts per set in both directions between runs of
  the same file (Qwen3 2k twice on the Poco: GSM8K 20 then 17, IFEval 15 then 18). The
  ExecuTorch runtime is not deterministic run to run on these phones, and the between-window
  differences sit inside that band. For the 2.6B, which reasons to the cap, no two runs of
  anything agree on the raw stream (0 of 90), while the parsed tool calls agree on 84 to 87
  of 90 and the shown content on 29 to 30.
- **The window does not change speed.** In the suite's own timings, prefill and decode
  rates at 32k sit within a few percent of 2k or 4k on every phone for both LFM2.5 sizes
  (D9400 1.2B: 254 to 262 tok/s prefill and 37.5 to 38.7 tok/s decode across all five
  windows). The fixed-prompt probe, which is the paired measurement, is in the probe table.
- **The window costs memory, allocated at load, and for a full-attention model it is
  decisive.** LFM2.5 (8 of 30 or 6 of 16 layers attend) pays 25 to 33 KB per token of
  window: 400 to 800 MB at 16k, 800 MB to 1.07 GB at 32k. Qwen3-1.7B (all 28 layers attend,
  8 KV heads of 128) pays 224 KB per token: about 7 GB at 32k. The Qwen3 32k export loaded
  to 5.7 to 8.5 GB resident and died after three to six prompts on the 12 GB Galaxy S24+,
  Galaxy S25 Ultra and Poco X8 Pro (the report on disk stops mid-set with no error and no
  time-box, which is what a process killed for memory leaves behind); only the 16 GB Pixel
  10 Pro XL finished all 90. Its 2k export ran everywhere. Qwen3 at 32k is not a shippable
  file on a 12 GB phone; at 2k it is.
- **So the full sweep was not worth running,** and the reduced design below was enough to
  show it, with two controls the first design lacked (same-file repeats and a fixed-prompt
  speed probe) that a methodology review demanded.

## What was actually run

| | Poco (Dimensity 9400) | 8 Elite, Tensor G5, Exynos 2400 (Test Lab) |
|---|---|---|
| LFM2.5-1.2B | 2k, 4k, 8k, 16k, 32k, full 90 prompts; 16k and 32k run twice | 4k and 32k, full 90 |
| LFM2.5-2.6B | 2k and 32k, full 90 (8k, 16k dropped: 55 min per file once the BOS fix made it reason to the cap) | 4k and 32k, full 90 |
| Qwen3-1.7B | 2k and 32k, full 90; 2k run twice | 2k and 32k, full 90 |
| Speed probe | all twelve files, two interleaved passes, three turns each, cooled and awake | not run |

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
  `<think>`), so at a 640-token cap many of its replies never reach an answer. Its grades are
  therefore low at every window alike and say nothing about the window and little about the
  model; they are in the table because the byte-identity and speed columns still count.

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
