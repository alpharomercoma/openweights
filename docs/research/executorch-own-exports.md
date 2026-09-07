# Exporting LFM2.5 for ExecuTorch ourselves

*2026-09-07. Four exports made on a Mac, measured on the Poco X8 Pro (Dimensity 9400)
through the app's own engine.*

## Why

Software Mansion's LFM2.5 exports carry a 2048-token window. The window is fixed at export
in ExecuTorch, so a longer one has to be exported, not configured. The question was whether
we could do that ourselves, what it costs, and whether the larger models and windows keep
the speed.

## How

ExecuTorch 1.4.0 ships a recipe for LFM2 and LFM2.5 (`examples/models/lfm2`). The exporter
was pinned to the same release as the app's runtime AAR, because a newer exporter can emit
method names the older runtime does not know. Weights come from Hugging Face, are converted
to the recipe's checkpoint format, then exported with:

- 8-bit dynamic activations, 4-bit weights in groups of 32 (the layout KleidiAI's kernels
  take in the XNNPACK runtime), 8-bit embedding table, XNNPACK with extended ops;
- prefill chunk 2048 tokens. The exporter bounds the token input at 2047 and the runner
  splits longer prompts at 2048, so the runner's own split fails on any prompt of 2048
  tokens or more; the app feeds long prompts in pieces itself (see
  [executorch.md](executorch.md), "Three things only the device said");
- `max_context_length` 16384 or 32768;
- the family's own BOS and EOS ids in the metadata (the 2.6B tokenizer differs from the
  1.2B's: 128k vocabulary, BOS 124894, EOS 124900).

The 2.6B has no entry in the recipe's model list, so it was exported under the 1.2B class
with its own parameter file written from the HF config (30 layers, 8 of them attention,
hidden 10752, rope theta 1e7). The class only chooses the example directory; the parameter
file defines the architecture.

| Export | File | Wall time on the Mac | Peak RAM |
|---|---|---|---|
| 1.2B, 16k | 810 MB | 2 min | 6.7 GB |
| 1.2B, 32k | 827 MB | 2 min | 6.7 GB |
| 2.6B, 16k | 1.80 GB | 9 min for both | 12.2 GB |
| 2.6B, 32k | 1.81 GB | | |

No GPU is involved anywhere.

## The matrix

Each cell is the median of six turns: two passes over the four files in interleaved order,
three turns per load from a cold cache, the SoC cooled below 42 C before each load, the
phone awake and unlocked. The prompt is 929 tokens; the reply is capped at 160 tokens.

| Model, window | Prefill tok/s | Decode tok/s | Time to first token, 929 tokens | Resident after load | Resident after a turn |
|---|---|---|---|---|---|
| 1.2B, 16k | 360 (337 to 378) | 42.8 (40.7 to 45.6) | 2.6 s | 1.38 GB | 1.46 GB |
| 1.2B, 32k | 341 (328 to 346) | 41.4 (40.5 to 41.7) | 2.7 s | 1.76 GB | 1.84 GB |
| 2.6B, 16k | 154 (142 to 155) | 19.8 (17.9 to 20.0) | 6.0 s | 2.40 GB | 2.51 GB |
| 2.6B, 32k | 151 (138 to 155) | 18.8 (16.4 to 20.0) | 6.2 s | 2.93 GB | 3.03 GB |

What it says:

- **The window costs memory, not speed.** Doubling the window from 16k to 32k took 5% off
  prefill and 3 to 5% off decode, within a turn's noise, and added 380 MB (1.2B) or
  530 MB (2.6B) of resident memory. The runtime's SDPA attends over filled positions only;
  the cache is allocated in full at load.
- **The 2.6B runs at 43% of the 1.2B's speed at 2.2x the parameters.** Prefill 154 against
  360 tok/s, decode 20 against 43. Both are steady across turns.
- **Resident memory is the file plus the cache plus ~0.5 GB.** 1.2B at 16k: 810 MB file,
  403 MB cache, 1.38 GB resident. 2.6B at 32k: 1.81 GB file, 1.07 GB cache, 2.93 GB.

## The measurement trap

The first attempts produced prefill rates from 62 to 307 tok/s for the same file within one
process. The phone was dozing: with the screen off, MIUI throttles background work, and an
instrumentation test is background work. Cores were observed at 339 MHz (little) and
798 MHz (prime) in that state. Awake, the same file measured 371, 378 and 370 tok/s. Every
number above was taken awake. The thermal service also reports two CPU rows, one a constant
57.9 that never moves; the runner reads the lowest live one.

## Where things are

Exports and environment: `~/ow-models/etexport/` (`export16k.sh`, `export26.sh`,
`convert26.py`, `lfm2_5_2_6b_config.json`, `matrix/run.sh`, `matrix/all.sh`,
`matrix/results.log`). On the phone: `/data/local/tmp/openweights/LFM2.5-*.pte` with their
tokenizers. `ExecuTorchOnDeviceTest#throughputReport` with `-e pte`, `-e tokenizer` and
`-e context` produces a row.
