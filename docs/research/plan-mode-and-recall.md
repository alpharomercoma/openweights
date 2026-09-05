# Plan mode that answers, a model that remembers wrong, and a search it believes

Three behaviours reported from using the app on 2026-09-05, measured on the host against
the app's own prompt dump, and what the loop now does about each. The two test models are
LFM2.5-1.2B-Instruct QAD-Q4_0 and Qwen3-1.7B Q8_0, both at temperature zero through
llama-server with the sampler `TurnRunner.deciding()` uses on a tool pass. The probe is
`eval/behaviour_probe.py`, and the cases are written out below as well.

A trap worth recording first: a llama-server left over from an earlier session was still
holding port 8090, so the first "LFM" run was Qwen answering twice. Byte-identical output
from two different models is the tell. Check `/props` for `model_path` before reading a
result.

## Plan mode answers easy tasks. Not deliberate, and now enforced

`/plan` puts one instruction in the system message: "Do not act on anything yet. Say what
you would do and why, as short steps." The loop then reads the reply for a numbered list
and, finding none, keeps nothing, with a comment saying a model that answered "has proposed
nothing", which is most of them most of the time. So the tolerance was deliberate; the
behaviour was not. Nothing checked the instruction was followed.

Eight requests, from "What is 2+2?" through "Who is Killua?" to "Rename every .txt in my
notes folder to .md" and "Find out who won the F1 race last weekend and save it to a file":

| arm | LFM2.5-1.2B | Qwen3-1.7B |
| --- | ---: | ---: |
| shipped instruction | 2/8 | 3/8 |
| stricter instruction ("do not give the answer, even if you already know it") | 5/8 | 5/8 |
| shipped instruction, then one push when the reply was not a plan | **8/8** | **8/8** |

The stricter wording is worse than its number. Its extra "plans" were the answer with a
bulleted breakdown under it: "The capital of France is Paris. Here is a quick breakdown:
Location: Central France." The push produced plans: "1. Review the report for the budget
section. 2. Extract key details like amounts and categories."

So the wording stays and `TurnRunner` enforces it. In plan mode, a pass that made no call,
lists no steps, and does not end on a question is answered once with "That was the answer,
not a plan. Plan mode wants the steps: reply only with a numbered list of two to five short
steps saying what you would do, one line each, and do not give the answer." Once a turn,
like the other repairs. A reply ending on a question is left alone, because a clarification
in prose is the mode's other legitimate output. The goal runner's own planning turn comes
through the same path, so "No plan came back, so there is nothing to work through" should
now be rare rather than the common case on a request the model felt it could answer.

## "Who is Killua?" is answered from memory, wrongly, on both models

Killua Zoldyck is a main character in Hunter x Hunter. At temperature zero, holding a
working `web_search`, LFM2.5-1.2B placed him in Naruto and Qwen3-1.7B in Final Fantasy.
Both fluent, both certain, both wrong. Qwen also narrated an ending to Attack on Titan.

The tool prompt already says to search for a person or story "you do not recognise". A 1B
model recognises everything; there is no felt uncertainty for the wording to reach. An arm
widening the clause to characters and telling the model that at its size recall is
unreliable was measured and moved Qwen from 8 of 10 to 6 of 10. This is the third time here
that a routing wording has been measured and found not to move routing (see
`tool-calling.md`, 2026-09-01), and it is the same split AbstentionBench reports: prompts
change what a model says about its knowledge, not whether it acts on the gap.

Two mechanisms were measured instead, on ten questions: five named characters or stories,
two famous people, three pieces of settled knowledge that must not search.

| arm | LFM2.5-1.2B | Qwen3-1.7B | extra passes |
| --- | ---: | ---: | --- |
| shipped prompt | 5/10 | 8/10 | none |
| a note on the question: "(This question names Killua. Look it up with web_search before answering rather than recalling it, and answer from what the search returns.)" | **10/10** | **10/10** | none |
| a push after an answer from memory: "You answered that from memory. Call web_search for Killua now" | 10/10 | 10/10 | one per named question |

The LFM row under the shipped prompt is low because the host harness carries the full
sixteen-tool catalogue and LFM denies in prose on nearly everything there ("I don't have a
built-in database to look up specific names"); the app's denial repair converts those on a
second pass. The note converts them on the first.

The note wins on cost: it goes on the question and stays there for every pass, so it extends
the cache rather than adding a round. `NamedSubject` recognises the four shapes such a
question takes ("who is X", "tell me about X", "what happens in X", and "what is X" for a
capitalised name of two or more words), and `TurnRunner` attaches the trailer when
`web_search` is actually on offer this turn. The price is a search on "Who is Albert
Einstein?", which the model could have answered. Accepted, on the user's stated preference
and on Mallen et al. 2022: for a model this size the popularity threshold below which
retrieval beats memory sits above most of what a phone user asks. `eval/routing_matrix.py`
measures the model without the trailer, so its `known` rows still mean what they meant.

## Two snippets say 142 metres and one says 300, and the model says both

The reported case: three search results, two right and one wrong, and the answer takes the
wrong one at face value. Reproduced with an invented tower so nothing in the weights could
help: two reference snippets giving 142 metres and a forum post announcing a 300 metre
expansion "heard from a friend".

Under the shipped framing of a search result ("Answer the question using these. Do not ask
which one to read.") LFM2.5-1.2B wrote: "stands at 142 metres. Recent updates indicate it
has been expanded to approximately 300 metres after adding a spire." Both as fact, one
breath. Qwen flagged the forum post on its own.

The result now opens: "These are snippets other people wrote, not checked facts. Answer from
what most of them agree on. If they disagree, say so and say which source says what rather
than picking one; a forum post or a comment counts for less than a reference page." Same
model, same snippets: "The most reliable source I found is the official Dagupan landmarks
page, which states 142 metres. However, a recent forum post claims 300 metres after adding a
spire. Since both sources differ, I can't confirm a single definitive answer." Qwen was
unchanged, so the sentence costs the model that did not need it nothing. A harsher variant,
with the wrong figure first and stated flatly by a tourism page, was answered 142 by both
models under both framings, majority over rank.

What this does not do is check anything. It changes what the model is told the material is,
which is the cheapest place to stop a snippet becoming a fact. The next step, if the case
recurs, is structural: when two results disagree on a number, fetch the top reference page
and read the figure from it, which `fetch_url`'s `find` parameter already makes a one-round
errand.

## The canvas, and what could not be checked today

The three build evals (`WebsiteBuildEval`, `SlidesBuildEval`, `DocumentBuildEval`) pass on
the device and assert structure: a real HTML file above 400 characters with a stylesheet
and at least twelve rendered elements, a deck that edits in place, a document that
paginates. They do not assert content quality, and the 2026-09-05 sweep already recorded
the model's slip on the last website it built: six phases where four were asked for. The
phone was advertising wireless debugging on a port that refused the connection for the
whole of this session, so no new build was run. Whether the generated sites, documents and
decks are good is a question the evals should start scoring, at least with a rubric on the
prompt's own nouns, and it is open.

## Liquid AI, read for what it offers this loop

Both pages the user named were read, plus the docs index they point to. What was there:

- **"No Cloud, No Waiting: Tool-Calling Agents on Consumer Hardware"** (LFM2-24B-A2B).
  Single-step accuracy 80% over 100 prompts and 67 MCP tools; multi-step chains completed
  end to end 26% of the time. Liquid's own recommendation is a "guided loop": propose one
  tool, confirm, run, repeat, "a fast dispatcher in a guided loop, not a hands-off
  autopilot". That is the shape `PlanBoard` and `AdvanceTool` already give a goal: the app
  holds the list and the model takes one step at a time. Their inference settings for the
  agent were temperature 0.1 and top_p 0.1; the app's tool pass is greedy, which is the same
  decision taken further. Named failure modes: sibling confusion between similar tools, and
  "occasional conversational replies instead of tool calls", which is the denial the repair
  loop here exists for.
- **LFM2.5-2.6B "Deploy Agents Everywhere"**: trained with agentic RL inside real harnesses
  (Hermes Agent and OpenClaw are named), leads its size on IFBench, Multi-IF and IFStruct,
  ToolSandbox 77.8 and BFCLv4 56.9. Its benchmark settings were temperature 0 to 0.001 for
  tool use. Nothing about system prompts.
- **Agent harnesses page**: Hermes needs `agent.tool_use_enforcement true` because
  otherwise "the model tends to describe actions instead of calling tools". The same
  failure, solved there by a switch and here by a repair pass.
- **Tool use docs**: tools go in the system message as JSON, calls come back Pythonic inside
  `<|tool_call_start|>`, results go back as a tool turn, and "for large tool lists this can
  use significant portions of your context window": include only relevant tools. Nothing
  on when to call versus answer.
- **LFM2.5 QAD Q4_0**: the 1.2B QAD checkpoint retains 97.4% of BF16 performance and the
  benchmarks include IFEval, Multi-IF and BFCLv4, so tool routing is in the number the
  quant was tuned to. Consistent with the routing verdicts here having to name the quant.
- **LFM2.5-Encoders (230M, 350M)**: bidirectional encoders for classification and
  token-level tasks, 3.7x faster than ModernBERT-base on CPU at 8k tokens. No GGUF named.
  The one thing on either page that could change this loop structurally: a 230M
  classifier that scores whether a snippet supports a claim would turn "two agree, one
  differs" from a sentence the model is told into a check the app runs. Not built; noted.
- **MeMo: Model as a Memory** (arXiv:2605.15156): knowledge in a separate memory model,
  "robust to retrieval noise", retrieval cost independent of corpus size. Research, no
  small-model or llama.cpp path yet.
- **Zero-Overhead Introspection for Adaptive Test-Time Compute**: OpenReview would not
  serve the page to a fetch, so only the title is known. Relevant to the perplexity feature
  that was built and reverted this week; not pursued.

Nothing on the two Liquid pages addresses hallucinated recall, grounding on retrieved text,
or knowing what the model does not know. The gap is the same one this note closes from the
harness side.
