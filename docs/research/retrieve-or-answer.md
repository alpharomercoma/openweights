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
build of each arm's day; the lab rows for the intent arms were rerun on the final matchers
after Test Lab's fifty-executions-a-day quota reset. One Dimensity `intent-full` row carries
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
| LFM2.5 1.2B compiled (8da4w) | intent-search | Dimensity 9400 | 31% (23/75) | 15% (9/60) | 35% (41/117) | 5% (2/42) | 63% (38/60) | 2% (4/160) | 28% (11/39) | 73% (8/11) | 3.4 | 2152.5 | 174.0 |
| LFM2.5 1.2B compiled (8da4w) | intent-search | Tensor G5 | 27% (20/75) | 12% (7/60) | 38% (44/117) | 10% (4/42) | 65% (39/60) | 4% (7/160) | 26% (9/34) | 89% (8/9) | 5.1 | 2558.5 | 180.0 |
| LFM2.5 1.2B compiled (8da4w) | intent-search | Exynos 2400 | 27% (20/75) | 13% (8/60) | 39% (46/117) | 14% (6/42) | 65% (39/60) | 3% (5/160) | 25% (8/32) | 100% (8/8) | 4.3 | 2906.5 | 175.5 |
| LFM2.5 1.2B compiled (8da4w) | intent-search | 8 Elite | 37% (28/75) | 12% (7/60) | 38% (44/117) | 10% (4/42) | 65% (39/60) | 2% (3/160) | 31% (13/42) | 77% (10/13) | 3.4 | 2483.5 | 170.0 |
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
| LFM2.5 1.2B Q4_K_M | intent-search | 8 Elite | 45% (34/75) | 27% (16/60) | 41% (48/117) | 10% (4/42) | 72% (43/60) | 0% (0/160) | 34% (21/62) | 81% (17/21) | 6.5 | 3847.0 | 153.0 |
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
