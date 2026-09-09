# "Who is X": the search the app makes before the model speaks

2026-09-10. A single-turn question, "who is alpha romer coma", on the model the shortlist
leads with (LFM2.5 1.2B, ExecuTorch), with web search on, answered three times in a row
with "Let me look up information about alpha romer coma using a web search so I can provide
an accurate answer." and nothing else. No search ran. This note is what was wrong, what was
measured, what shipped, and what the numbers say about it across three models and sixteen
questions through the whole loop on the phone.

## What was wrong, in two layers

**The surface bug.** The loop has a salvage for a short reply that announces a tool instead
of calling it: hand the tool names back and spend one more pass. It tested for the tool's
definition name, `web_search`, underscore included. The model said "a web search". The
announcement was classified as a finished answer and shown. Fixed: the salvage now
recognises the announcement by its shape (first person, forward tense, a lookup verb within
two words) where a lookup tool is on offer. Two reviewers attacked the first draft of that,
which matched any short mention of "web search", and the false positives they found are
tests now: "No web search is necessary", "Web search is a service", "Let me check: Ottawa",
"Let me know if you want me to search".

**The real bug.** With the announcement caught, the loop pushed the model once ("You do have
a working tool for exactly this: web_search. Call it now") and the model still made no call,
then wrote "does not appear in current sources" having searched nothing. So a probe
(`WhoIsProbe`, core/engine androidTest) put the app's own instructions and tool to the same
export, raw: greedy and sampled, three seeds, with the app's "(This question names X. Look
it up with web_search...)" note and without, and after the push. Sixteen passes, zero calls.
With the note the model narrated a search and then reported its results; without it, a
football player, a singer from The Voice Kids, an Italian architect. On the host with
llama.cpp the same note had measured ten of ten calls on 2026-09-05. The note is a request
the compiled 1.2B does not act on, and no wording of the request measured here changes that.

## What shipped

For a question that names a subject (the existing detector, `NamedSubject`: "who is X",
"tell me about X", "what happens in X", capitalised "what is X Y"), with web search
switched on, tools offered, and not in plan mode, the loop now runs the search itself
before the model writes anything: `web_search(query = X)`, through the same agent step as
a model-made call (the switch, the approval rules, the step shown in the transcript), then
the prompt gains one assistant line, "Searching the web for X.", and the tool result, and
the model answers in one pass with the evidence in front of it. The note is left off the
question when the search runs; if the search is declined or fails, the note goes on and the
loop is the one that shipped before.

Two designs were measured on the way here and are not what shipped. The first made the
search in the loop after the model's first pass came back without a call, keeping that
pass in the history: it worked, and cost the pass (a hundred and fifty tokens of somebody
else's biography, five seconds) and then the biography, which the model defended over the
real results. The second was Codex's suggestion and is the one that shipped.

The detector was tightened on review: a subject that opens on a pronoun or possessive
("my mother", "your day", "yourself") or carries a possessive anywhere ("John Doe my
doctor") is not a name, and an expression ("2 + 2") never matched. The web search row on
the Tools screen now says that a question naming somebody is searched before the reply and
that the search is shown.

## The measurement

`WhoIsSuiteOnDeviceTest` (app androidTest) runs the real `TurnRunner` with the real
`WebSearchTool` (DuckDuckGo), the app's own instructions, greedy, thinking off, a 4k
window, on the POCO X8 Pro Max. Sixteen questions with an answer key fixed before the run:
ten that name somebody or something, six that must not be searched. "Correct" means a key
word is in the reply, which is loose: in run 1 "Hunter" let a reply pass that placed Killua
in Genshin Impact, and the key became the series name. Three models: the compiled LFM2.5
1.2B, the same model as Q4_K_M GGUF, and Qwen3 1.7B Q8 GGUF.

Arms: `before` is the note alone, the loop as it shipped until this day. `shipped` is the
arm under test in each run. Run 1's `shipped` was the first design, the search after a
wasted pass; run 2's is the search first.

| Model | Arm | Named correct | Searched | Model called itself | Mean s, named | Passes | App searches on the six negatives |
|---|---|---|---|---|---|---|---|
| LFM2.5 1.2B ExecuTorch | before (note only) | 4 of 9 | 0 of 10 | 0 | 6.0 | 1 | 0 |
| LFM2.5 1.2B ExecuTorch | search after a wasted pass | 7 of 9 | 10 of 10 | 0 | 12.8 | 2 | 0 |
| LFM2.5 1.2B ExecuTorch | **search first** | **8 of 9** | 10 of 10 | 0 | **8.3** | 1 | 0 |
| LFM2.5 1.2B Q4_K_M | before | 5 of 9 | 0 of 10 | 0 | 9.7 | 1 | 0 |
| LFM2.5 1.2B Q4_K_M | search after a wasted pass | 6 of 9 | 10 of 10 | 0 | 23.5 | 2 | 0 |
| LFM2.5 1.2B Q4_K_M | **search first** | **9 of 9** | 10 of 10 | 0 | **12.1** | 1 | 0 |
| Qwen3 1.7B Q8 | before | 9 of 9 | 10 of 10 | 10 | 24.2 | 2 | 0 (it searched "who is she" on its own) |
| Qwen3 1.7B Q8 | **search first** | 9 of 9 | 10 of 10 | 0 | **19.0** | 1 | 0 (same) |

The tenth named question, a niche real name, has no key; every search-first row answered it
from the profiles the search found rather than inventing a person.

What the "before" rows were saying: with the note and no search, LFM2.5 placed Killua in
Attack on Titan, made Sydney Runkle an Australian footballer, named the CEO of Nvidia "Lam
Wang", and wrote "Based on a web search, ..." about searches it had not made. The one
search-first miss on the compiled model is "the president of the philippines", where
DuckDuckGo's top snippets were the official site and a list page with no name in them, and
the model answered Duterte from memory; Qwen3 answered Marcos from the same snippets.

The cost is one search, about two seconds, on every question that names somebody, paid also
by a model that would have called anyway. Qwen3 got faster, not slower: it no longer spends
a pass asking. The six negatives cost nothing new in any arm; the app made no search on
any of them, in any run.

## Two things the suite showed that are not this fix

**LFM2.5 1.2B never called a tool itself**, in ninety-six rows across both runtimes and
every arm, including "what changed in android 16", which the control case exists for and
which Qwen3 called on every time. The app's instructions open with "You already know the
answer to most questions", tuned so that a model does not search what it knows; on this
model that reads as never. That is a routing measurement of its own, and it was run next
(`CurrentFactsSuiteOnDeviceTest`): ten questions whose answer lives after any training cut
(what changed in Android 16, the latest Kotlin, the price of bitcoin now, the next iPhone
event, today's news, the current ExecuTorch version, whether Python 3.14 is out, the
weather in Manila, what LangChain released this month, whether Sony has announced the
PlayStation 6), none of them name-shaped, and six settled ones the model must answer itself
(the capital of Peru, a translation, 17 times 23, a limerick, how a hash map works, who wrote
Pride and Prejudice). Two wordings: the instructions as they ship, and the same with one
sentence added after "information that changed after your training" that names those cases
("Whether something is out yet, what changed in a product, what the current version, price,
date or weather is, and what the news says are all things that changed after your training:
call web_search for those before answering, every time, and never answer them from memory").

| Model | Instructions | Called on the ten current questions | Correct where a key word can say | Mean s | Over-called on the six settled | Settled correct |
|---|---|---|---|---|---|---|
| LFM2.5 1.2B ExecuTorch | shipped | 0 of 10 | 5 of 8 | 4.0 | 0 of 6 | 5 of 6 |
| LFM2.5 1.2B ExecuTorch | with the sentence | 0 of 10 | 3 of 8 | 4.0 | 0 of 6 | 5 of 6 |
| LFM2.5 1.2B Q4_K_M | shipped | 5 of 10 | 7 of 8 | 8.8 | 0 of 6 | 6 of 6 |
| LFM2.5 1.2B Q4_K_M | with the sentence | 7 of 10 | 7 of 8 | 14.6 | 0 of 6 | 6 of 6 |
| Qwen3 1.7B Q8 | shipped | 8 of 10 | 8 of 8 | 17.4 | 0 of 6 | 6 of 6 |
| Qwen3 1.7B Q8 | with the sentence | 7 of 10 | 7 of 8 | 18.0 | 0 of 6 | 6 of 6 |

Three things in that table. The sentence does not ship: it moves the compiled model not at
all, buys the GGUF two calls at six seconds a question, and buys Qwen3 nothing. That is the
fourth routing wording measured in this codebase and the fourth that did not move routing.
Nothing over-calls on the settled six under either wording, on any model. And the one
finding that is new: on questions that do not name anybody, the same LFM2.5 1.2B as a
Q4_K_M GGUF on llama.cpp calls on its own half the time, and the compiled export never
does, under identical instructions on the same phone. On name-shaped questions both were
zero, which is why the earlier sentence in this note says "the model, not the runtime"; on
these it is the runtime, or the export's 8da4w quantisation, or the way the compiled path
renders the tool block, and only a token-level comparison of the rendered prompts and a
forced-call probe can say which. That is the measurement still owed, and it is a runtime
question, not a wording one.

**The shape of LFM2.5's answers.** With the results in front of it, LFM2.5 opened ten of
sixteen replies with "Here's a concise summary based on the web search:", listed "what
sources say" source by source, and bolded headings; Qwen3 wrote prose from the same
results. The result framing invited it: the wording of 2026-09-05 says "say which source
says what" for the case where sources disagree, and the 1.2B did it whether they disagreed
or not. Three wordings were priced, search first in every arm, the same sixteen questions:

| Model | Framing | Named correct | Boilerplate opener | Talks about sources or the search | Bullets or bold | Mean chars |
|---|---|---|---|---|---|---|
| LFM2.5 1.2B ExecuTorch | sources (2026-09-05) | 8 of 9 | 5 of 16 | 10 of 16 | 12 of 16 | 461 |
| LFM2.5 1.2B ExecuTorch | plain (four prohibitions) | 7 of 9 | 3 of 16 | 7 of 16 | 6 of 16 | 402 |
| LFM2.5 1.2B ExecuTorch | **direct** | **9 of 9** | 2 of 16 | 9 of 16 | 7 of 16 | 349 |
| LFM2.5 1.2B Q4_K_M | sources | 9 of 9 | 4 of 16 | 10 of 16 | 8 of 16 | 474 |
| LFM2.5 1.2B Q4_K_M | plain | 8 of 9 | 2 of 16 | 7 of 16 | 2 of 16 | 313 |
| LFM2.5 1.2B Q4_K_M | **direct** | 8 of 9 | 2 of 16 | 3 of 16 | 2 of 16 | 285 |
| Qwen3 1.7B Q8 | sources | 9 of 9 | 1 of 16 | 1 of 16 | 1 of 16 | 383 |
| Qwen3 1.7B Q8 | plain | 9 of 9 | 0 of 16 | 0 of 16 | 0 of 16 | 313 |
| Qwen3 1.7B Q8 | **direct** | 9 of 9 | 0 of 16 | 2 of 16 | 0 of 16 | 343 |

"Plain" said what not to do, four prohibitions in a row, and both reviewers objected to it
before its numbers came in: that is what a 1.2B model does rather than avoids, and "no
list" contradicts a user who asked for one. It cost the compiled model a correct answer.
"Direct" says what to do ("answer the question directly, in the shape it asked for, in
concise prose unless a list was asked for, without mentioning the search, the snippets or
the pages; only if the snippets contradict each other on a fact, say so in one sentence")
and ships: 26 of 27 named questions right across the three models, the same as the old
wording, with replies that talk about sources down from 21 of 48 to 14, bullet lists from
21 to 9, and a quarter shorter. So the answer to "model thing or something we can fix": the
fabrication was the loop's to fix and is fixed; the shape was half the framing's and moved
with it; what remains ("Here's a quick overview" on a third of the compiled model's replies)
is the model.

## Reviews

Codex (gpt-5.6-sol) and Gemini (agy) reviewed three drafts. What they changed: the
spoken-name substring match (false positives), the search-first design itself (Codex), the
possessive and expression guards, the failed-search test, and the framing arm. What they
raised and was left as documented: a bare name is a weaker query than the question where
the question carries a relation or a time ("the current president", "X's spouse"); those
shapes do not match the detector and stay with the model's own judgement. A leading
article is taken by the question shape ("Who is The Weeknd?" searches "Weeknd"), which is
the price of "who is the president of the philippines" keeping its office.

## Files

- `app/.../ui/chat/TurnRunner.kt`: `searchFirst`, the announcement regex, two
  measurement switches.
- `core/tools/.../NamedSubject.kt`: the pronoun, possessive and expression guards.
- `core/tools/.../WebTools.kt`: `WebSearchFraming`, the three wordings measured; `DIRECT`
  ships.
- `app/src/androidTest/.../WhoIsSuiteOnDeviceTest.kt`: the suite; `-e arms`, `-e models`.
- `app/src/androidTest/.../CurrentFactsSuiteOnDeviceTest.kt`: the routing suite on
  current-facts questions; `-e arms shipped,explicit`.
- `core/engine/src/androidTest/.../WhoIsProbe.kt`: the raw-reply probe.
- Tests: `TurnRepairsTest`, `NamedSubjectTest`, `TurnRunnerTest`.
