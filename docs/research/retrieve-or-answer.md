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

The full catalogue (`driven-full`, first 34 rows) did not restore calling: five calls on
answerable rows, none on rows that needed one, a median of 15.8 s a row against 3.7 and a
time to first token of 12.7 s on 2,400 prompt tokens. All four reviewers read that the
same way: the fresh-install change of 2026-09-08 did not cause the under-calling, and the
literature predicts the direction (fewer tools, better selection). The full table is below.

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

Pending: the Poco's four baseline arms and two intent arms, and the three Test Lab phones,
were still running when this note was written. The tables land here from
`grade_decisions.py tools/eval/results/decisions --against driven-search`.

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
