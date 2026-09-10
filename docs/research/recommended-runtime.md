# Which runtime the shortlist recommends, read from the whole table

2026-09-10. The recommended shortlist led with our own ExecuTorch exports of LFM2.5 1.2B and
2.6B from 2026-09-07, on the sentence "the same weights at the same quality and up to twice
the speed". On 2026-09-10 the maintainer asked for the whole benchmark matrix to be read,
not the one row that had been quoted, and for a verdict. This note is that reading, the
verdict, what was reverted, and what was kept. The tables it reads are
[benchmark-matrix.md](benchmark-matrix.md), [public-benchmarks.md](public-benchmarks.md),
[executorch-window-matrix.md](executorch-window-matrix.md) and
[retrieve-or-answer.md](retrieve-or-answer.md); nothing here is a new measurement.

## What the matrix says

**Accuracy.** Of the fourteen family-and-set cells both runtimes attempted, one is an
integration failure on the llama.cpp side (SmolLM3's tool calls, where the GGUF template
never rendered the tools) and one is level (Llama 3.2 on IFEval); the GGUF stack scores
higher on the other twelve. For the one family the shortlist shipped compiled the gap is
not noise. Mean passes of 30 across the five phones with complete columns, publisher 8da4w
export against Q4_K_M GGUF, greedy, thinking off on both:

| LFM2.5 | ExecuTorch | llama.cpp |
|---|---|---|
| GSM8K | 13.0 | 19.6 |
| IFEval | 15.6 | 21.6 |
| BFCL tool calls | 22.4 | 26.0 |

Qwen3 loses about four of thirty on every set compiled. Gemma 3 compiled loops on GSM8K
(0.6 against 13.2). Llama 3.2 and SmolLM3 are level within the noise.

**Speed.** ExecuTorch prefills LFM2.5 two to three times faster on every phone in that
table (321 against 138 tok/s on the Dimensity 9400, 248 against 112 on the Tensor G5),
decodes faster on four of six (22 against 9 tok/s on the Tensor G5, 26 against 17 on the
Exynos 2400, 55 against 37 on the 8 Elite), and halves the wall time per IFEval and BFCL
prompt. That is real, it is what the 2026-09-07 reset was built on, and it is what this
reversal gives up on every chip but the one in hand.

**Consistency.** Across the five families the compiled runtime's grade differs between
phones on 31.5% of cases against 13.3% for llama.cpp; that aggregate carries Gemma 3's
looping, so for LFM2.5 alone read the five-phone spreads in the matrix instead: 4 against 1
on GSM8K, 2 against 3 on IFEval, 3 against 0 on BFCL. The window matrix later found the
same `.pte` swings by up to five of thirty between two runs on one phone. A compiled
recommendation does not do the same thing on the next phone, or on the next run.

**The behaviour this release was about.** The release goal of 2026-09-09 was small: every
tool off on a fresh install except web search, and see whether the models still perform.
The decision suite, run on our own 32k export and on the GGUF of the same weights, is that
question measured:

| LFM2.5 1.2B, search only, the model decides | searched when needed | claimed a search it never made | correct |
|---|---|---|---|
| compiled, four phones | 1 to 4 of 75 | 32 to 35 of 160 | 30 to 34% |
| GGUF Q4_K_M, four phones | 33 to 34 of 75 | 0 of 160 | 40 to 42% |

The compiled artifact does not call the one tool left on, and one reply in five narrates a
search that never happened. The GGUF calls it on 44% of the rows that needed it and
fabricated a search on none of 640 replies across four phones.

**Every grade above is the 1.2B's.** The 2.6B's compiled export was graded on its own in
the window matrix (GSM8K 23 to 28 and IFEval 18 to 21 of 30 at a 2048 cap) and never
against its GGUF, and it was not in the decision suite. It goes back with the 1.2B on the
maintainer's decision and on two facts about the runtime that hold for any model: no logits
for the confidence gate, and no budget on a thinking block, which llama.cpp closes at a
token count and the 2.6B, whose template opens one on every reply, needs. A paired 2.6B
grade is on the owed list below.

## Where the spiral came from

[public-benchmarks.md](public-benchmarks.md), written 2026-09-03, already contained the
decision rule: "not which engine is faster, but what the publisher's artifact for that
engine gives up". Four days later the shortlist was reset to compiled exports on speed,
against that rule. On 2026-09-09 every tool but search was switched off. On 2026-09-10 the
suite showed the compiled model cannot use the one tool left, and the loop then grew three
layers of text matching (the intent rule, the apostrophe normalisation, the narration cut)
and a token-probability gate to compensate. The note that shipped them records that the
intent rule moves the GGUF "within a point" of its model-driven arm: every one of those
layers was built for a deficit the recommended artifact has and the alternative file of the
same weights does not. The probability gate is the one piece that adds something the GGUF
did not have (45% correct against 41%, 24% against 12% on the rows that needed a search),
and it is the piece the compiled runtime cannot run, because the ExecuTorch Android AAR
returns tokens and no logits.

## Verdict

The compiled LFM2.5 is not the recommendation. It is faster, and it is measurably worse at
every task in the matrix, worse at the one tool the release keeps on, inconsistent across
phones, and closed to the only fix that reaches the confident-wrong tail. The priority the
maintainer set is stability, quality, then speed, and speed does not count when the answer
is wrong.

What the compiled runtime is still right for: the vision exports, and phones where
llama.cpp decodes at 4 to 9 tok/s. On the 2.6B the two runtimes are a wash for speed on the
Dimensity 9400 (154 against 139 tok/s prefill, 20 against 25 decode), so its case is the
thinnest of the two and rests on the runtime facts above. Any compiled export is a search
away in Discover, and the refusal-removed compiled pair stay on the shortlist under their
own heading, marked modified.

## What was reverted, and what was kept

Reverted, in `HuggingFaceClient.RECOMMENDED`: the two LFM2.5 rows point at Liquid AI's
GGUF repositories again. A GGUF opens at the window the fit estimator sizes for the phone
from its header and the device's memory, not at a fixed default, so a person who takes the
recommendation does not silently lose the 32k the compiled export carried; the top bar
shows the window either way. The KDoc keeps the 2026-09-07 reset and this reversal in order, so
the next reader sees both. Nothing else that the reset touched is reverted: a compiled model
still opens at its exported window, still loads off the main thread, still shows its window
on the card.

Kept, because each was measured on the GGUF rows or costs them nothing:

- **The fresh-install default of one switch, web search.** The GGUF calls it on 44% of
  needed rows under that default; the sixteen-tool catalogue moves it to 59% recall and
  down to 34% correct at 29.6 s a turn. The default stands on the GGUF's own numbers.
- **The tool descriptions.** No tool's `description` string changed between the 2026-09-06
  Play build and this note; the diff of `core:tools` is the search-result framing, the
  thin-snippet reorder, the apostrophe normalisation, the pronoun guards on the name note,
  and the canvas grader. The framing shipped at 04:42 on 2026-09-10 (c3b7ce44), before
  any 160-row run; every GGUF number in this note was produced under it, and the wording
  it replaced was only ever measured on sixteen hand-written questions. Neither has a
  paired run against the other on the suite; the one with the evidence is the one that
  ships.
- **The intent rule and the apostrophe normalisation.** On the GGUF the rule lands within
  a point of the model-driven arm on every column; the normalisation only changes what a
  matcher reads. They are guards, and they cost the GGUF nothing measured.
- **The narration cut, restricted.** Both reviewers (Codex and Gemini, the same night)
  refused it as shipped: it stops a generation, which no other guard does, it was built
  for a reply shape the GGUF produced zero times in 640, and its full-arm measurement was
  never finished. It now fires only on a stream that carries no token probabilities,
  which is the compiled runtime read as a capability rather than by name (`ReplyWatch`).
  The modified compiled rows still ship and keep it; the GGUF never sees it.
- **The confidence gate, kept and called provisional.** Codex's objection is fair: the
  0.2 cutoff was chosen offline on the same 160 rows the phone then graded, the paired
  result is +15/-6 at p = 0.08 on one phone, and it costs 15 unnecessary searches and
  3.8 s of median turn. It stays because it is the one measured mechanism that moves the
  rows the maintainer's priority names, the confidently wrong ones, and because the
  held-out check is cheap. It ran the same night: the same three sets drawn with seed 8
  (`pull_decisions.py --seed 8`, 160 rows, six in common with seed 7), Q4_K_M GGUF on the
  Poco, unplugged at 43 to 28% battery, both arms on the build of this note, results in
  `tools/eval/results/decisions/held-*`:

  | Seed 8, 160 rows | searched when needed | searched when not | correct | correct, needed | median s |
  |---|---|---|---|---|---|
  | intent-search | 57% (43/75) | 25% (15/60) | 45% (53/117) | 29% (12/42) | 8.3 |
  | doubt-search | 68% (51/75) | 40% (24/60) | 48% (56/117) | 33% (14/42) | 13.2 |

  Paired on the rows both answered, the gate is right where the rule alone was wrong on 5
  and wrong where it was right on 1. That is the same direction and the same shape as the
  seed-7 run (+15/-6): a small gain on the rows that needed a search, bought with fifteen
  more searches on rows that did not and five seconds of median turn. Two runs of 160 on
  one phone, both with fewer than ten discordant pairs in the second, is a replication of
  the direction and not yet a measurement of the size. The gate stays, and the caveat
  stays with it. On these rows the GGUF also narrated a search it had not made twice in
  160 under each arm, so "never" in the earlier paragraph reads "0 of 640 on seed 7".
- **The confidence gate.** It is the GGUF's measured gain and it runs only on llama.cpp.

## What the recommendation does not yet say on the screen

A shortlist row is a repository, and each of these lists eight GGUF files sorted by size.
Only the Q4_K_M carries the grades above; the QAD-Q4_0 is a distinct checkpoint (Liquid's
quantisation-aware one) that has only been timed, and the plain Q4_0 and the larger quants
have neither. A person who opens the row and takes the smallest file takes one the numbers
here do not cover, and nothing on the file row says so. Codex named this the principal
user-facing risk of the change, and it is the one product change this note asks for next:
mark the graded file on the fit card, or filter the recommended row to it.

## What is still owed

- A paired grade of the 2.6B's compiled export against its GGUF on the decision suite, the
  measurement this reversal of that row does not have.

- A third held-out draw, and a second phone, before the gate's size is quoted as a number.
- The llama.cpp columns of the matrix are the Q4_K_M and Q8_0 files of 2026-09-03. The
  QAD Q4_0 file with the vendor microkernels, the one that closes the prefill gap on the
  Dimensity 9400, has never been run through the matrix on the other five chips, nor through
  the decision suite anywhere. The recommendation names both files; the QAD file's grades
  are the next measurement.
- Artifact and runtime are confounded throughout: the compiled cells are the 8da4w exports.
  "ExecuTorch is worse" is not a claim the data makes; "the 8da4w exports of these models
  are worse" is.
- Each cell is thirty prompts. A gap of six of thirty on two sets is beyond the measured
  noise; nothing here is a leaderboard entry.
