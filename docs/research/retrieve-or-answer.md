# Retrieve or answer: the decision measured on public rows, and the rule that came out of it

2026-09-10. The day after a hard-coded "search the name first" route shipped and was
rejected (`who-is-questions.md`), the question it had been written around was put to
public rows through the app's own loop on four phones: does a small model on a phone search
when it must, and only then, and what in the loop moves that without taking the decision
from the model. This note is the harness, the numbers, the rule that shipped, and what
the four reviewers (Codex gpt-5.6-sol, Gemini 3.8 Flash, GitHub Copilot, Mistral Vibe)
changed about it. The literature it stands on is `routing-literature.md`.

## The harness

`tools/eval/bench/decisions.json`: 160 rows drawn with seed 7 by `pull_decisions.py` from
RetrievalQA (80: the set's own `param_knowledge_answerable` label, 40 answerable and 40
not, across PopQA, TriviaQA, RealTimeQA, FreshQA and ToolQA), PopQA (40: ten per
popularity quartile, cuts at 236, 977 and 5,765 monthly views) and FreshQA (40: fifteen
fast-changing, ten slow, ten never, five false premises). Each row carries the set's label
for whether the answer lives in the weights (`need`) and the set's answer aliases. When2Call
and SimpleQA were read and left out on the reviewers' advice: the first carries its own
function catalogues, the second is built for frontier models.

`DecisionSuiteOnDeviceTest` (app androidTest) runs every row through the real
`TurnRunner`, the real DuckDuckGo search, the shipped instructions and the app's date
exchange, greedy, thinking off, 4k window, and records per row the passes, calls, prompt
tokens, time to first token, PSS, thermal state, device and quant. Arms are only what the
app can vary:

| Arm | Tools in the prompt | The loop |
|---|---|---|
| `bare` | none | what the weights answer alone |
| `driven-search` | web_search | the model decides; note on a "who is" question; the pushes |
| `driven-full` | web_search and the fifteen other definitions from the prompt dump, as stubs | the same, over the catalogue every measurement before 2026-09-08 carried |
| `first-search` | web_search | the rejected route, for the record |
| `intent-search`, `intent-full` | as above | the model decides, and what it decides in words is carried out (below) |
| `doubt-search` | web_search | the intent rule, and the search the app makes when the model's own token probabilities put its answer in doubt (`honoursDoubt`) |

`grade_decisions.py` on the host: recall (searched when the row needed it), unnecessary
searches, correctness by the sets' own containment rule widened to a token F1 of 0.5 and
narrowed by a fifteen-character floor, ToolFailBench's four failure labels (skip,
unnecessary, fabricated search talk, ignored result), a quoted-instructions rate, medians,
and a paired right-where-the-other-was-wrong count against a reference arm. Runs:
`run_decisions.sh` on the Poco over adb (attached, because a detached `am instrument`
died with its adb session), `run_decisions_cloud.sh` on Test Lab in chunks that fit a
forty-five minute matrix (Tensor G5, Exynos 2400, Snapdragon 8 Elite).

## What the first arm showed, before anything was changed

Compiled LFM2.5 1.2B (ExecuTorch 8da4w), `driven-search`, 160 rows, POCO X8 Pro Max:

| Searched when needed | Searched when not | Correct | Correct, needed | Correct, known | Fabricated search talk | Median s |
|---|---|---|---|---|---|---|
| 3 of 75 | 0 of 60 | 30% (35/117) | 7% (3/42) | 53% (32/60) | 32 of 160 | 3.7 |

Three things in that row. The model does not call: three times in seventy-five. It
writes that it did: "Based on my search, the screenwriter for Three Loves in Rio is Miguel
Aznar", "The search results indicate that the director of The Last Word is not widely
documented", "I found this information through a web search", one reply in five, none of
them after a search, and some of them decorating a right answer ("Rome is the capital of
Italy. Here's a quick summary based on web search results"). And when it announces ("I'll
perform a web search to find the latest information...") and the loop hands the tool names
back, it announces again, and the second announcement is what the user reads.

The full catalogue (`driven-full`) was read too early. Its first 34 rows, which are the
sample's answerable rows, showed five calls and none needed, at a median of 15.8 s a row
against 3.7, and all four reviewers read that as the fresh-install change of 2026-09-08
not being the cause. The full 160 rows say otherwise on the calling and the same on the
price: with sixteen definitions in the prompt the compiled model calls on 47 of 75 rows
that need it, and on 27% of those that do not, at 14.9 s a turn with the first token at
9.9 s, and scores 29% against 30 with the search alone. The catalogue does move the
decision; what it moves it with is 2,400 tokens of prompt every turn. The table below has
the rest.

Two side measurements. The echo probe (six public-domain recitations, three models, the
shipped instructions and none, no tools) found no reply quoting the instructions in
thirty-six, so "The complete answer must be provided directly..." needs the tool block in
the prompt to reproduce; the probe reran with the search on offer (below). And whether a
subject's token rarity under the LFM tokenizer predicts PopQA popularity, which one
reviewer proposed as a tail detector: on 3,000 rows the AUROC is 0.52. It does not; there
is no cheap rarity heuristic to build.

## The rule

`TurnRunner.honourIntent`. When a pass makes no call and, in its own words, announces a
search ("Let me look that up", "I'll perform a web search", naming web_search alone or
nothing, as the whole of a short reply or its last sentence), claims one happened
("based on my search", "the search results indicate", "I looked it up", with no search
made this turn), laments missing knowledge, or denies a capability whose fitting tool is
the search: the app runs web_search on the question as asked, through the same agent step
a model call takes (the switch, the approval rules, the step shown), drops the pass from
the prompt, appends "Searching the web for: <query>" and the result, and the model
answers next pass with the evidence in front of it. Once a turn; it spends the single
repair allowance only when the search ran; a failed or declined search falls through to
the pushes that were there before. The model decides; a model that writes the call never
reaches this.

What the reviewers added, each of which is a test in `TurnRepairsTest`:

- Not after any search already ran this turn: "Based on the search results" after a real
  search is the model reading them (Codex).
- Not when the question is about the user's own things ("what is my wifi password"
  answered with "I don't have that information" is a lament by shape and private by any
  reading); the model may still call (Codex).
- Not a question to the user ("Should I look that up?") and not an announcement withdrawn
  in the same breath ("Don't let me look that up"), for the push as well (Codex, Gemini).
- "research" is not "search"; a summary of pasted material is not a claim; an answer about
  search engines that says "the search results are ordered by score" is an answer
  (Codex, Gemini, Copilot).
- The query is the question, not a name pulled out of it ("who directed The Last Word"
  loses its verb as "The Last Word"), with its wrapping off ("could you please check ...
  for me"), a pronoun-only follow-up prefixed with the previous question, and a long
  message cut to its last question (Codex, Gemini, Copilot).

Checked offline against the phone's rows before it ran: of the 32 fabricated replies, 31
match; of the 157 replies with no call, 38 would trigger, and every one of them is a
fabrication or an announcement.

## The numbers

Four phones, three models, 160 rows an arm, one build. Every row that had a search in it
had a real DuckDuckGo search from that phone. "Searched when needed" is recall on the 75
rows whose label says the weights cannot answer; "when not" is the rate on the 60 whose
label says they can; "correct" is on the 117 rows whose answer is time-stable; "answer in
results" is how often the gold alias was in the snippets a search brought back, and
"correct when findable" what the model did when it was. The Dimensity rows ran the
build of each arm's day; the lab rows for the compiled model's intent arm were rerun on
the final matchers after Test Lab's fifty-executions-a-day quota reset, and the lab was
stopped there by decision: the full-catalogue and doubt arms are Dimensity-only. One Dimensity `intent-full` row carries
a latency of 6,818 s, the two hours the phone spent suspended (below); it is kept, and the
medians do not feel it. Regenerate with `grade_decisions.py` then `report_decisions.py`.

| Model | Arm | Phone | Searched when needed | Searched when not | Correct | Correct, needed | Correct, known | Fabricated search | Answer in results | Correct when findable | Median s | TTFT ms | Chars |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| LFM2.5 1.2B compiled (8da4w) | bare | Dimensity 9400 | 0% (0/75) | 0% (0/60) | 29% (34/117) | 5% (2/42) | 53% (32/60) | 0% (0/160) | n/a | n/a | 2.3 | 1465.5 | 141.5 |
| LFM2.5 1.2B compiled (8da4w) | bare | 8 Elite | 0% (0/75) | 0% (0/60) | 32% (37/117) | 5% (2/42) | 57% (34/60) | 0% (0/160) | n/a | n/a | 2.5 | 1826.0 | 140.5 |
| LFM2.5 1.2B compiled (8da4w) | driven-search | Dimensity 9400 | 4% (3/75) | 0% (0/60) | 30% (35/117) | 7% (3/42) | 53% (32/60) | 20% (32/160) | 0% (0/3) | n/a | 3.7 | 2419.0 | 160.5 |
| LFM2.5 1.2B compiled (8da4w) | driven-search | Tensor G5 | 1% (1/75) | 0% (0/60) | 32% (38/117) | 7% (3/42) | 57% (34/60) | 22% (35/160) | 0% (0/1) | n/a | 4.4 | 2187.5 | 170.0 |
| LFM2.5 1.2B compiled (8da4w) | driven-search | Exynos 2400 | 1% (1/75) | 0% (0/60) | 34% (40/117) | 14% (6/42) | 57% (34/60) | 21% (33/160) | 0% (0/1) | n/a | 4.3 | 2927.0 | 176.5 |
| LFM2.5 1.2B compiled (8da4w) | driven-search | 8 Elite | 4% (3/75) | 0% (0/60) | 30% (35/117) | 7% (3/42) | 53% (32/60) | 20% (32/160) | 33% (1/3) | 100% (1/1) | 3.4 | 2535.5 | 164.5 |
| LFM2.5 1.2B compiled (8da4w) | intent-search | Tensor G5 | 33% (25/75) | 13% (8/60) | 37% (43/117) | 5% (2/42) | 67% (40/60) | 1% (1/160) | 29% (12/41) | 75% (9/12) | 5.7 | 3170.5 | 192.0 |
| LFM2.5 1.2B compiled (8da4w) | intent-search | Exynos 2400 | 29% (22/75) | 12% (7/60) | 38% (45/117) | 12% (5/42) | 65% (39/60) | 2% (4/160) | 37% (13/35) | 69% (9/13) | 4.4 | 2938.0 | 205.5 |
| LFM2.5 1.2B compiled (8da4w) | intent-search | 8 Elite | 37% (28/75) | 13% (8/60) | 38% (44/117) | 10% (4/42) | 65% (39/60) | 2% (4/160) | 35% (15/43) | 73% (11/15) | 3.5 | 2496.0 | 183.5 |
| LFM2.5 1.2B compiled (8da4w) | intent-search | Dimensity 9400, five results | 36% (27/75) | 17% (10/60) | 38% (45/117) | 12% (5/42) | 65% (39/60) | 1% (2/160) | 25% (11/44) | 91% (10/11) | 3.2 | 1983.0 | 200.0 |
| LFM2.5 1.2B compiled (8da4w) | first-search | Dimensity 9400 | 31% (23/75) | 20% (12/60) | 41% (48/117) | 10% (4/42) | 72% (43/60) | 2% (4/160) | 32% (14/44) | 79% (11/14) | 3.4 | 2032.0 | 148.5 |
| LFM2.5 1.2B compiled (8da4w) | driven-full | Dimensity 9400 | 47% (35/75) | 27% (16/60) | 29% (34/117) | 5% (2/42) | 52% (31/60) | 6% (10/160) | 35% (23/66) | 65% (15/23) | 14.9 | 9918.5 | 138.5 |
| LFM2.5 1.2B compiled (8da4w) | intent-full | Dimensity 9400 | 60% (45/75) | 40% (24/60) | 40% (47/117) | 19% (8/42) | 62% (37/60) | 3% (5/160) | 43% (36/83) | 89% (32/36) | 16.2 | 11136.0 | 143.0 |
| LFM2.5 1.2B Q4_K_M | bare | Dimensity 9400 | 0% (0/75) | 0% (0/60) | 32% (38/117) | 7% (3/42) | 58% (35/60) | 0% (0/160) | n/a | n/a | 4.0 | 2520.0 | 196.0 |
| LFM2.5 1.2B Q4_K_M | driven-search | Dimensity 9400 | 44% (33/75) | 25% (15/60) | 42% (49/117) | 12% (5/42) | 70% (42/60) | 0% (0/160) | 30% (18/60) | 83% (15/18) | 5.5 | 3498.0 | 151.0 |
| LFM2.5 1.2B Q4_K_M | driven-search | Tensor G5 | 44% (33/75) | 23% (14/60) | 41% (48/117) | 12% (5/42) | 70% (42/60) | 0% (0/160) | 31% (18/59) | 83% (15/18) | 8.1 | 4464.0 | 146.5 |
| LFM2.5 1.2B Q4_K_M | driven-search | Exynos 2400 | 44% (33/75) | 23% (14/60) | 40% (47/117) | 10% (4/42) | 70% (42/60) | 0% (0/160) | 27% (16/59) | 88% (14/16) | 8.4 | 6206.5 | 150.5 |
| LFM2.5 1.2B Q4_K_M | driven-search | 8 Elite | 45% (34/75) | 23% (14/60) | 41% (48/117) | 12% (5/42) | 70% (42/60) | 0% (0/160) | 30% (18/60) | 83% (15/18) | 6.1 | 3975.5 | 149.5 |
| LFM2.5 1.2B Q4_K_M | intent-search | Dimensity 9400 | 47% (35/75) | 25% (15/60) | 41% (48/117) | 12% (5/42) | 70% (42/60) | 0% (0/160) | 27% (17/62) | 88% (15/17) | 5.3 | 3429.0 | 159.0 |
| LFM2.5 1.2B Q4_K_M | intent-search | Tensor G5 | 47% (35/75) | 25% (15/60) | 41% (48/117) | 10% (4/42) | 72% (43/60) | 0% (0/160) | 29% (18/63) | 83% (15/18) | 8.3 | 4435.0 | 152.5 |
| LFM2.5 1.2B Q4_K_M | intent-search | Exynos 2400 | 45% (34/75) | 25% (15/60) | 41% (48/117) | 10% (4/42) | 72% (43/60) | 0% (0/160) | 30% (18/61) | 83% (15/18) | 8.3 | 6076.5 | 151.0 |
| LFM2.5 1.2B Q4_K_M | intent-search | 8 Elite | 45% (34/75) | 25% (15/60) | 42% (49/117) | 10% (4/42) | 73% (44/60) | 0% (0/160) | 30% (18/61) | 89% (16/18) | 5.4 | 3611.0 | 150.0 |
| LFM2.5 1.2B Q4_K_M | doubt-search | Dimensity 9400 | 61% (46/75) | 40% (24/60) | 45% (53/117) | 24% (10/42) | 70% (42/60) | 0% (0/160) | 35% (31/88) | 81% (25/31) | 9.1 | 3646.5 | 167.5 |
| LFM2.5 1.2B Q4_K_M | doubt-search | Dimensity 9400, three-token gate, not shipped | 55% (41/75) | 40% (24/60) | 44% (51/117) | 12% (5/42) | 75% (45/60) | 0% (0/160) | 38% (31/81) | 81% (25/31) | 14.1 | 5467.0 | 171.0 |
| LFM2.5 1.2B Q4_K_M | first-search | Dimensity 9400 | 44% (33/75) | 25% (15/60) | 41% (48/117) | 12% (5/42) | 70% (42/60) | 0% (0/160) | 30% (18/60) | 83% (15/18) | 5.5 | 3547.0 | 118.0 |
| LFM2.5 1.2B Q4_K_M | driven-full | Dimensity 9400 | 59% (44/75) | 25% (15/60) | 34% (40/117) | 7% (3/42) | 60% (36/60) | 2% (4/160) | 32% (24/75) | 88% (21/24) | 29.6 | 24044.0 | 138.0 |
| LFM2.5 1.2B Q4_K_M | intent-full | Dimensity 9400 | 72% (54/75) | 33% (20/60) | 39% (46/117) | 17% (7/42) | 63% (38/60) | 2% (3/160) | 40% (37/92) | 81% (30/37) | 32.9 | 25085.5 | 99.0 |
| Qwen3 1.7B Q8_0 | bare | Dimensity 9400 | 0% (0/75) | 0% (0/60) | 35% (41/117) | 12% (5/42) | 60% (36/60) | 0% (0/160) | n/a | n/a | 5.4 | 3521.5 | 168.5 |
| Qwen3 1.7B Q8_0 | bare | 8 Elite | 0% (0/75) | 0% (0/60) | 36% (42/117) | 10% (4/42) | 63% (38/60) | 0% (0/160) | n/a | n/a | 3.8 | 1794.0 | 183.5 |
| Qwen3 1.7B Q8_0 | driven-search | Dimensity 9400 | 35% (26/75) | 20% (12/60) | 39% (46/117) | 12% (5/42) | 67% (40/60) | 0% (0/160) | 28% (13/47) | 69% (9/13) | 8.9 | 5743.0 | 186.5 |
| Qwen3 1.7B Q8_0 | driven-search | Tensor G5 | 37% (28/75) | 20% (12/60) | 41% (48/117) | 12% (5/42) | 70% (42/60) | 1% (1/160) | 29% (14/49) | 79% (11/14) | 18.0 | 9445.0 | 188.5 |
| Qwen3 1.7B Q8_0 | driven-search | Exynos 2400 | 36% (27/75) | 20% (12/60) | 39% (46/117) | 10% (4/42) | 68% (41/60) | 1% (2/160) | 29% (14/48) | 71% (10/14) | 16.2 | 10988.0 | 197.0 |
| Qwen3 1.7B Q8_0 | driven-search | 8 Elite | 37% (28/75) | 20% (12/60) | 39% (46/117) | 12% (5/42) | 67% (40/60) | 2% (3/160) | 35% (17/49) | 71% (12/17) | 5.8 | 2832.0 | 195.0 |
| Qwen3 1.7B Q8_0 | intent-search | Dimensity 9400 | 35% (26/75) | 20% (12/60) | 39% (46/117) | 12% (5/42) | 67% (40/60) | 0% (0/160) | 30% (14/47) | 71% (10/14) | 9.2 | 6006.0 | 188.0 |
| Qwen3 1.7B Q8_0 | intent-search | Tensor G5 | 37% (28/75) | 20% (12/60) | 41% (48/117) | 12% (5/42) | 70% (42/60) | 1% (1/160) | 31% (15/49) | 73% (11/15) | 19.1 | 10099.0 | 188.0 |
| Qwen3 1.7B Q8_0 | intent-search | Exynos 2400 | 36% (27/75) | 20% (12/60) | 39% (46/117) | 10% (4/42) | 68% (41/60) | 1% (2/160) | 31% (15/48) | 67% (10/15) | 16.6 | 10920.0 | 186.5 |
| Qwen3 1.7B Q8_0 | intent-search | 8 Elite | 37% (28/75) | 20% (12/60) | 39% (46/117) | 12% (5/42) | 67% (40/60) | 2% (3/160) | 31% (15/49) | 73% (11/15) | 6.5 | 3214.5 | 195.0 |
| Qwen3 1.7B Q8_0 | doubt-search | Dimensity 9400 | 39% (29/75) | 22% (13/60) | 39% (46/117) | 12% (5/42) | 65% (39/60) | 0% (0/160) | 21% (11/52) | 82% (9/11) | 10.8 | 6894.0 | 212.0 |
| Qwen3 1.7B Q8_0 | first-search | Dimensity 9400 | 35% (26/75) | 20% (12/60) | 40% (47/117) | 12% (5/42) | 67% (40/60) | 0% (0/160) | 30% (14/47) | 79% (11/14) | 9.0 | 5935.0 | 189.5 |

What the table says, in the order the questions were asked:

- **The compiled model does not search on its own, on any chip.** One to four rows in
  seventy-five, on four phones, and one reply in five cites a search it never made. With
  the loop carrying out what it says (`intent-search`), it searches 27 to 32 of 75, the
  fabrications drop to one to four, correctness goes from 30 to 34% to 37 to 38%, and the
  median turn does not get slower. Paired on the same rows, the intent arm is right where
  the model-driven arm was wrong on 10, 10 and 13 rows against 3, 5 and 5 the other way,
  on the Tensor, the Exynos and the 8 Elite. When the search found the answer, the model
  used it 69 to 89% of the time.
- **The full catalogue does change what the compiled model does, and not for the better.**
  With sixteen definitions in the prompt it calls on 47 of 75 needed rows, and on 27% of
  the rows that needed nothing; correctness is 29% against 30 with search alone and 38
  with the intent rule; the median turn is 14.9 s against 3.7 and the first token comes
  at 9.9 s. So the fresh-install change of 2026-09-08 did move calling, in the direction
  the maintainer said, and the price of moving it back that way is four times the latency
  and a lower score. The same catalogue moves the GGUF from 44% to 59% recall and from 42%
  to 34% correct at 29.6 s a turn.
- **The rule on top of the catalogue is the best score the compiled model reaches, and the
  most expensive.** `intent-full` searches 60 of 75 needed rows and 40% of the answerable
  ones, scores 40% overall and 19% on the rows that needed a search, against 29% and 5%
  for the catalogue alone; paired, it is right where the catalogue alone was wrong on 19
  rows against 8. The GGUF goes to 72% recall and 39% correct. The price is 16.2 s a turn
  and 11.1 s to the first token on the compiled model, 32.9 s on the GGUF, nearly all of
  it the sixteen definitions being read; a user pays that once per model load through the
  warm prefix, the harness pays it every row. So the catalogue makes the model call, the
  rule makes the calls count, and one answerable question in two and a half gets a search
  it did not need.
- **Same phone, the route against the rule.** On the Dimensity the rejected route
  (`first-search`) scores 41% at 20% unnecessary searches, +17/-5 paired against the
  model-driven arm; the rule scores 35% at 15%, +13/-6. Seven rows of 117 separate them,
  inside the compiled model's run-to-run spread, and the route is a hard-coded decision
  on a question shape while the rule is the model's own. The rule was kept.
- **The rule costs a model that calls nothing.** The GGUF and Qwen3 land within a point of
  their model-driven arms on every column; the app's search replaces the model's own on
  the few rows where they announced instead of calling.
- **Chips agree.** llama.cpp rows are within two points across the four SoCs on every
  column; the compiled model's ExecuTorch rows differ by up to seven points on the "needed"
  column between phones, which is the run-to-run nondeterminism `window-matrix.md`
  recorded, not a chip effect. Latency is the chip: Qwen3's median turn is 5.8 s on the
  8 Elite and 16 to 19 s on the Tensor G5 and Exynos 2400.
- **What search buys at all on this set.** The no-tools baseline scores 32% (compiled) and
  36% (Qwen3); the best search arm scores 38% and 41%. The gap is findability: the gold
  was in the snippets for 26 to 35% of searches. "Who is the author of Empire?" brings
  back Gore Vidal's, "Memory" brings back a memoir, "The Ball" brings back Dragon Ball;
  PopQA's tail is titles shared with famous works, and a search for the question finds
  the famous one. That is the set's shape, and it bounds every arm alike.

## Three results, five, and the engines

The model reads three snippets a search, a number the settings inherited from the tool's
hard-coded days with no measurement behind it. Two questions were put to the same 160 rows
from the workstation, with the app's own request shape for DuckDuckGo (a cookie fetch from
the home page, then a POST as the browser agent; anything else is answered with the
challenge page) and ddgs 9.16's for the rest: does another engine find more, and does
depth. "Found" is the grader's own test, a gold alias in the title or snippet.

| Results shown | DuckDuckGo | Yahoo | Google, WML door | DuckDuckGo and Yahoo merged |
|---|---|---|---|---|
| 1 | 16% | 24% | 3% | 25% |
| 3 | 32% | 36% | 3% | 39% |
| 5 | 37% | 39% | 3% | 41% |
| 10 | 43% | 43% | 3% | 47% |

On the 75 rows that need a search. DuckDuckGo at three reproduces what the phones measured
(26 to 33%), so the workstation's numbers are the phones'. Yahoo, which is Bing's index,
finds the same things: four points more at three, the same at ten. Google's feature-phone
endpoint answered eighteen questions and then shut for the other 142 at one query every
five seconds; Startpage answered none; both are out. Merging two engines at three results
is seven points for twice the requests. Depth is the only free lever, and even ten results
from two engines leave more than half the needed rows with no answer in any snippet: the
false-premise, ToolQA and PopQA tail strata sit at 0 to 36% at any depth, and their
answers are on the page, not in the summary of it.

Five results, then, measured on the phone: a build with the default at five, the stored
setting cleared, the compiled model's `intent-search` arm again (the "five results" row in
the table). Correct 38% against 35%, 12% against 5% on the rows that needed a search,
paired 9 rows to 8 on the same questions, the median turn unchanged. Two more snippets are
about 170 tokens of tail prefill. That is inside the compiled model's run-to-run spread and
the paired count says so; the default stays at three until a run says otherwise, and the
fetched page is the experiment that attacks the half no snippet reaches.

Rate limits are not why. Over the 1,327 searches the four phones made, at the loop's pace
of one search every half minute or slower and each phone holding its own cookie jar, none
failed and ten fell through to Brave, the Test Lab phones on Google's addresses included.
The challenges seen on 2026-09-10 were the workstation's, eight scrapers at a query every
2.5 seconds.

## The echo probe, with the tool block

Six recitations (two anthems, a nursery rhyme, the Gettysburg opening, pi, the alphabet
backwards) times the shipped instructions or none, times the tool block or none, on the
three models: 72 replies, none of which shares more than two consecutive words with the
instructions. The meta-commentary seen once on the phone does not reproduce with or
without the tools. What the probe did show: the compiled model's replies halve when the
tool block is present (median 431 to 194 characters with no instructions, 290 to 175 with
them, two of six turned into refusals), the GGUF's do not move, and on the 160-row factual
sets the effect is absent for every model (141, 160 and 138 characters at zero, one and
sixteen tools on the compiled model). Six prompts a cell is enough to record it and not
enough to act on it. The GGUF also opens three of six recitations with "The complete
answer is here" when the instructions and tools are both present, a word borrowed from the
search framing, not a sentence quoted from it.

## What the day's runs taught about running them

- **An unplugged phone suspends.** The Dimensity was unplugged at 09:57 with the screen
  off; Android suspended the CPU and the app's web search, its 60-second timeout included,
  froze for two hours until the screen was woken. Doze throttling was the recorded trap;
  this is the whole device stopping. `run_decisions.sh` wakes the screen at the start, and
  the driver now keeps it awake for the length of the run; a phone on the charger does
  not need it.
- **A stored setting beats a changed default.** The first five-result run returned three
  hits a search: the phone's settings held an explicit `result_count` of 3 from a hand on
  the slider weeks earlier, and `getInt(key, default)` never reads the default. A default
  change reaches the users who never touched the control and nobody else, which is what a
  default should do, and what a measurement of one must remember.
- **The harness recorded 1,500 characters of a result** and the fourth and fifth hits fell
  off the record, so "answer in results" read low on the five-result run. It records 8,000
  now. The model saw everything; the log did not.
- **The instrumentation outlives a wireless-debugging drop.** Adb went away at 12:24 with
  the driver attached; the test kept writing rows on the phone and had finished two arms
  by the time the port was found again. Rows are on the phone, not on the adb session.

## The doubt the text never shows: the model's own token probabilities

Forty-two rows the compiled model, and thirty-odd the GGUF, needed to search and answered
wrongly with nothing in the text to act on. The one signal such an answer still carries is
the probability the model gave its own tokens. FLARE (2023) retrieves when a generated
token's probability falls under a line; TARG (arXiv 2511.09803, 2026) decodes a short
no-context draft, scores its logits, and gates retrieval on the score at a chosen budget,
on 7B and 8B models only. Whether a 1B to 2B quantised model on a phone carries the same
signal was the open question, and it was answered offline first: `confidence.py` runs the
160 rows through a local llama-server with the phone's system prompt, greedy, and keeps
every token's log-probability (results under `tools/eval/results/confidence/`).

| Statistic over the first 20 reply tokens | LFM2.5 1.2B Q4_K_M, AUROC for a wrong answer | Qwen3 1.7B Q8_0 |
|---|---|---|
| Least likely token's probability | 0.78 | 0.70 |
| Mean of the three least likely | 0.80 | 0.70 |
| Mean log-probability | 0.78 | 0.69 |
| Smallest top-1 minus top-2 margin | 0.73 | 0.71 |

The signal is there, and stronger on the model that needs it. What does not exist is a
scale shared by models: the Q8_0 1.7B is more confident everywhere, so a mean
log-probability that sends the least sure 40% of one model's answers to search sends none
of the other's. A rolling per-model quantile was drafted and dropped on review (Codex: it
budgets a search rate, not an error rate, and drifts with whatever the user asks; Gemini:
sixty-four easy turns poison it). A probability of a single token is the scale that
transfers: "one of the model's first twenty tokens had less than a one-in-five chance"
means the same thing whichever model said it.

| Cutoff on the least likely opening token | LFM: sent / wrong / right | Qwen3: sent / wrong / right |
|---|---|---|
| 0.1 | 31 / 31 / 0 | 5 / 5 / 0 |
| 0.2 | 58 / 54 / 4 | 29 / 28 / 1 |
| 0.3 | 88 / 79 / 9 | 48 / 45 / 3 |
| 0.4 | 126 / 102 / 24 | 65 / 57 / 8 |

And where a cutoff fires for nothing: 48 asks that need no search, twelve each of writing,
code, device-style instructions and text work, through the same models. At 0.2 the LFM
fires on 5 (three poems, a bug fix, an email) and Qwen3 on 2 (two naming asks); at 0.3 on
16 and 9. So the line is 0.2, and the rule is `TurnRunner.honourDoubt`: a pass with no tool call
whose least likely opening token fell under a one-in-five chance is dropped, the app
searches the question, and the model answers again from the results, through the same
path and guards as the intent rule (search on offer, none yet this turn, not plan mode,
not the user's own things, not a question back, not a long reply, nothing pasted). The
mean of the three least likely tokens, which both reviewers preferred so that one rare
surname or code identifier cannot decide alone, was measured beside it, offline (41 sent
and 39 wrong on the LFM at 0.25, 7 and 7 on Qwen3, 3 and 1 false fires on the 48) and on
the phone (below). llama.cpp gives the loop
each token's log-probability from the raw logits at the sampled id; the ExecuTorch runner
exposes no logits, so the compiled model carries no confidence and the rule never fires
for it, which is the honest state of that runtime rather than a design.

On the phone, search offered, 160 rows, paired against the intent arm on the same rows:

| Model | Arm | Searched when needed | Searched when not | Correct | Correct, needed | Correct, known | App searches, right after | Right where the intent arm was wrong / the reverse | Median s |
|---|---|---|---|---|---|---|---|---|---|
| LFM2.5 1.2B Q4_K_M | intent-search | 47% | 25% | 41% | 12% | 70% | 12, 4 | | 5.3 |
| LFM2.5 1.2B Q4_K_M | doubt-search, least likely token < 0.2 (shipped) | 61% | 40% | 45% | 24% | 70% | 38, 17 | 15 / 6 (McNemar p = 0.08) | 9.1 |
| LFM2.5 1.2B Q4_K_M | doubt-search, three least likely < 0.25 | 55% | 40% | 44% | 12% | 75% | 31, 14 | 10 / 2 (p = 0.04) | 8.2 |
| Qwen3 1.7B Q8_0 | intent-search | 29% | 20% | 39% | 12% | 67% | 4, 2 | | 9.2 |
| Qwen3 1.7B Q8_0 | doubt-search, least likely token < 0.2 | 29% | 22% | 39% | 12% | 65% | 9, 4 | 2 / 3 | 10.8 |

The single-token gate added 26 searches on the LFM beyond the intent rule's: 11 on rows
labelled as needing one (none right before, 4 after), 9 on rows labelled answerable (2
right before, 6 after), 6 on fresh rows (none before, 3 after); only 4 landed on a row the
bare model had right, and 2 of those stayed right. The six rows it lost were searches the
model made itself in both arms, with different results the second time. The three-token
gate makes seven fewer searches and against the single-token run is 6 to 7 on the same
questions, a tie overall; but the searches it saves are on rows that needed one (15 against
20, ending right 3 times against 7), and on those rows it gets 10 right to the single
token's 14 and the intent arm's 8. A tie bought by skipping the rows the rule exists for is
not the cheaper one, so the single token shipped and the other is one constant away.
Correctness on the rows that needed a search goes from 12% to 24%, the first movement that
column has shown on the GGUF. On Qwen3 the gate fired nine times against four and changed nothing, which is
what a cutoff should do to a model that is sure. The price is the searches: a gated row on
the LFM takes about 12 s against 4.7 without, and the median turn goes from 5.3 s to 8 or
9 because 81 or 88 rows searched instead of 62. The three-token run's timings were taken
while the phone was in use and are quoted as such.

## Two recordings, and what they cost the matchers

At 18:43 the maintainer recorded the compiled model on "who is alpha Romer coma" and "who
is Charlie kirk". On the first it wrote "I’ll search for the latest information... After
reviewing recent sources, there is no widely recognized public figure" and the loop let it
stand; on the second the loop caught the fabrication and searched, and the answer from the
results was "a person named Charlie Kirk exists, primarily associated with a personal
website". Three causes, each fixed and replayed:

- **The apostrophe.** The model writes U+2019, every matcher spelled `i'll` straight, and
  the announcement walked past all of them. Of the 5,462 no-call replies the four phones
  had produced, 705 carry the character and twelve are announcements the rule should have
  carried out. `plainQuotes()` (core:tools) now turns typographic apostrophes and quotes
  into the ASCII ones before any classifier, the grader included, reads a reply or a
  question. `CapabilityDenial` already did this for itself, which is why the lament branch
  worked and the announcement branch did not.
- **"After reviewing recent sources."** A claim shape the matcher did not know; six phone
  rows used it ("after checking the relevant sources, I can confirm", "recent sources
  suggest"). Added, with the two shapes in the phone-phrasings test.
- **An empty first hit.** DuckDuckGo's first result for the second question is "Official
  site" with a two-word snippet, the second the Wikipedia line with his dates. The 1.2B
  reads the list as best match first and answered from the empty one. A hit whose snippet
  is under forty characters now goes to the end of the list, order otherwise kept: a rule
  about empty evidence, not about a question.

The compiled model's search arm again on the fixed build, same phone, same rows: correct
46 of 117 against 41, fabricated search talk 3 against 4, 42 app searches against 39,
right where the old run was wrong on 8 rows against 4 (p = 0.39). Within noise, nothing
worse, and the two recorded failures cannot recur in that form: the first now searches
(DuckDuckGo's top three for the name are the maintainer's site, LinkedIn and About page),
the second reads the Wikipedia line first.

## The narration is cut at its first sentence

Recorded at 19:45 on the compiled model, "who is gojo satoru": "I'm fetching the latest
information about Gojo Satoru from the web now. Once I have the results, I'll provide a
clear summary. Here's what I found using a web search:" and three invented bullets over
six seconds; then the loop caught the claim on the last sentence, dropped the pass,
searched, and had the model answer again, 23 s in all. The rule had been judging a reply
only once the pass ended, so the model was allowed to finish inventing before the search it
had announced was made.

`ReplyWatch` now judges the reply's first three sentences as each one completes, through
the same guards `searchIntent` applies at the end of a pass. The moment a sentence says a
search is happening and names no other tool, the pass is cut: the engine's token loop stops
when its collector leaves, the app makes the search, and the model writes its answer once
from the results. A reply that names another tool is left to call it; a reply with tool
markup or an open thinking block is never cut. The recorded sentence itself, "I'm
fetching ... from the web now", matched no announcement shape at all until tonight, so
even the end-of-pass rule only caught that reply at its third sentence; "fetching",
"retrieving", "gathering" with "from the web" are a shape now, and three sentences are
judged rather than two. On the phone the cut fires at 67 and 138 characters and the row
ends in one pass; the full-arm measurement waits for the phone to be idle, since the run
that was to carry it was killed with a dozen other processes by the ROM at row nine.

## What is not fixed, and said so

Forty-two rows of one hundred and sixty the compiled model needed to search and answered
confidently, wrongly, with no signal in the text at all ("Izaskun Zubizarreta Gerendiain
was born in Bilbao"; Oiartzun). The PopQA tail was 0 of 24. Nothing in a loop can act on a
signal the model does not give; the options weighed and left were always-search (a route,
and the wrong side of latency on every question the model knows), a popularity heuristic
(refuted above), and a self-verification pass (a 1B does not calibrate, and it is a second
pass on every turn). Codex's one experiment worth running is a one-token gate, SEARCH or
ANSWER, before generation, which keeps the decision the model's while removing the call
syntax as the failure; on a hybrid it costs a re-read of the prefix, so it is measured
here before it is shipped anywhere.

After the second widening of the matchers (d2e4763b: an announcement may take two
sentences to reach its verb, a claim needs no article before the search it cites), four
of 160 replies on the compiled model still report a search the turn never made, all on
the PopQA tail. The widening was replayed over the 4,781 no-call replies the four phones
had produced: twelve more matches, five of them the grader's fabrications, seven offers or
laments where the search is the honest outcome, and no finished correct answer among them.
That is the reading to repeat before any further matcher grows.
