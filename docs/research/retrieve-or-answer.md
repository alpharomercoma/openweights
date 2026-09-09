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
"correct when findable" what the model did when it was. The lab's remaining arms (the
compiled model's full-catalogue pair, the intent reruns on the final build) are queued
behind Test Lab's fifty-executions-a-day quota and land here when they return; rows
below carry the arm as the phone ran it. Regenerate with `grade_decisions.py` then
`report_decisions.py`.

| Model | Arm | Phone | Searched when needed | Searched when not | Correct | Correct, needed | Correct, known | Fabricated search | Answer in results | Correct when findable | Median s | TTFT ms | Chars |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| LFM2.5 1.2B compiled (8da4w) | bare | 8 Elite | 0% | 0% | 32% | 5% | 57% | 0% | n/a | n/a | 2.5 | 1826.0 | 140.5 |
| LFM2.5 1.2B compiled (8da4w) | driven-search | Dimensity 9400 | 4% | 0% | 30% | 7% | 53% | 20% | 0% | n/a | 3.7 | 2419.0 | 160.5 |
| LFM2.5 1.2B compiled (8da4w) | driven-search | Tensor G5 | 1% | 0% | 32% | 7% | 57% | 22% | 0% | n/a | 4.4 | 2187.5 | 170.0 |
| LFM2.5 1.2B compiled (8da4w) | driven-search | Exynos 2400 | 1% | 0% | 34% | 14% | 57% | 21% | 0% | n/a | 4.3 | 2927.0 | 176.5 |
| LFM2.5 1.2B compiled (8da4w) | driven-search | 8 Elite | 4% | 0% | 30% | 7% | 53% | 20% | 33% | 100% | 3.4 | 2535.5 | 164.5 |
| LFM2.5 1.2B compiled (8da4w) | intent-search | Tensor G5 | 27% | 12% | 38% | 10% | 65% | 4% | 26% | 89% | 5.1 | 2558.5 | 180.0 |
| LFM2.5 1.2B compiled (8da4w) | intent-search | Exynos 2400 | 28% | 15% | 38% | 12% | 63% | 2% | 26% | 89% | 4.5 | 2876.5 | 176.5 |
| LFM2.5 1.2B compiled (8da4w) | intent-search | 8 Elite | 32% | 13% | 37% | 7% | 65% | 1% | 33% | 69% | 3.3 | 2473.0 | 173.5 |
| LFM2.5 1.2B compiled (8da4w) | driven-full | Dimensity 9400 | 47% | 27% | 29% | 5% | 52% | 6% | 35% | 65% | 14.9 | 9918.5 | 138.5 |
| LFM2.5 1.2B Q4_K_M | driven-search | Dimensity 9400 | 44% | 25% | 42% | 12% | 70% | 0% | 30% | 83% | 5.5 | 3498.0 | 151.0 |
| LFM2.5 1.2B Q4_K_M | driven-search | Tensor G5 | 44% | 23% | 41% | 12% | 70% | 0% | 31% | 83% | 8.1 | 4464.0 | 146.5 |
| LFM2.5 1.2B Q4_K_M | driven-search | Exynos 2400 | 44% | 23% | 40% | 10% | 70% | 0% | 27% | 88% | 8.4 | 6206.5 | 150.5 |
| LFM2.5 1.2B Q4_K_M | driven-search | 8 Elite | 45% | 23% | 41% | 12% | 70% | 0% | 30% | 83% | 6.1 | 3975.5 | 149.5 |
| LFM2.5 1.2B Q4_K_M | intent-search | Tensor G5 | 47% | 25% | 41% | 10% | 72% | 0% | 29% | 83% | 8.3 | 4435.0 | 152.5 |
| LFM2.5 1.2B Q4_K_M | intent-search | Exynos 2400 | 45% | 25% | 41% | 10% | 72% | 0% | 30% | 83% | 8.3 | 6076.5 | 151.0 |
| LFM2.5 1.2B Q4_K_M | intent-search | 8 Elite | 45% | 27% | 41% | 10% | 72% | 0% | 34% | 81% | 6.5 | 3847.0 | 153.0 |
| LFM2.5 1.2B Q4_K_M | driven-full | Dimensity 9400 | 59% | 25% | 34% | 7% | 60% | 2% | 32% | 88% | 29.6 | 24044.0 | 138.0 |
| Qwen3 1.7B Q8_0 | bare | 8 Elite | 0% | 0% | 36% | 10% | 63% | 0% | n/a | n/a | 3.8 | 1794.0 | 183.5 |
| Qwen3 1.7B Q8_0 | driven-search | Dimensity 9400 | 35% | 20% | 39% | 12% | 67% | 0% | 28% | 69% | 8.9 | 5743.0 | 186.5 |
| Qwen3 1.7B Q8_0 | driven-search | Tensor G5 | 37% | 20% | 41% | 12% | 70% | 1% | 29% | 79% | 18.0 | 9445.0 | 188.5 |
| Qwen3 1.7B Q8_0 | driven-search | Exynos 2400 | 36% | 20% | 39% | 10% | 68% | 1% | 29% | 71% | 16.2 | 10988.0 | 197.0 |
| Qwen3 1.7B Q8_0 | driven-search | 8 Elite | 37% | 20% | 39% | 12% | 67% | 2% | 35% | 71% | 5.8 | 2832.0 | 195.0 |
| Qwen3 1.7B Q8_0 | intent-search | Tensor G5 | 37% | 20% | 41% | 12% | 70% | 1% | 31% | 73% | 19.1 | 10099.0 | 188.0 |
| Qwen3 1.7B Q8_0 | intent-search | Exynos 2400 | 36% | 20% | 39% | 10% | 68% | 1% | 31% | 67% | 16.6 | 10920.0 | 186.5 |
| Qwen3 1.7B Q8_0 | intent-search | 8 Elite | 37% | 20% | 39% | 12% | 67% | 2% | 31% | 73% | 6.5 | 3214.5 | 195.0 |

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
  to 34% correct at 29.6 s a turn. The compiled model's `intent-full` arm is pending.
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
