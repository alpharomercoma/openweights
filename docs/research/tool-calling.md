# What makes a 1B model call a tool

Written 2026-08-15, after a run of the tool-choice benchmark said something about the
benchmark rather than about the models. Everything here was measured on a Snapdragon 8 Gen 3
against real GGUF files, not read off a leaderboard.

The short version: the system prompt is no longer where the accuracy is, the route a model
takes matters less than which model it is, and half of what a popular agent harness does
assumes a context window this app does not have.

## The measurement that ended the wording work

Six models, four system messages, eight cases, three seeds. 576 generations, most of an hour.

| Model | Template renders tools | shipped | superseded | greedy | restrained |
|---|---|---|---|---|---|
| qwen2.5-1.5b | yes | 15/24 | 14/24 | **17/24** | 15/24 |
| gemma3-1b | no | 8/24 | 12/24 | 9/24 | 6/24 |
| lfm2-1.2b | no | 13/24 | died | died | died |
| llama3.2-3b | yes | 12/24 | 12/24 | 12/24 | 12/24 |
| phi4-mini | no | 15/24 | 15/24 | 15/24 | 15/24 |
| granite3.3-2b | yes | 12/24 | 12/24 | 12/24 | 12/24 |

Three of the six scored identically under four different system messages. Not close:
identical, to the individual case. Llama and Granite scored 12/24 with `under=12`, which for
a set that is half tool questions and half not is exactly the score of a model that never
calls anything. Phi scored 15/24 with `over=9`, which is nearly the score of a model that
always calls. Neither behaviour moved when the instruction did.

So the wording arms were retired. Two of them are quoted in the benchmark's own history
because a number in a commit message is a claim and an arm that reproduces it is evidence,
but there is nothing left in that dimension to find. What did move Qwen was sampling:
greedy decoding is worth three cases out of twenty four over the app's own temperature, which
is why `TurnRunner` now forces temperature zero for any pass where tools are on the table.
Choosing among tools is an argmax and the public suites score it that way.

## Never calling and never being asked look the same from here

A model that emits no tool call is either declining or ignorant, and those mean opposite
things. `supportsTools` is a test on the chat template, and it said yes for both models that
then called nothing in twenty four tries, so it does not separate them.

What separates them is tokens. Render the same question twice, once with the tools and once
without, clearing the context either side so prefix reuse does not make the second one free,
and compare what the engine actually had to process:

```
RENDER model=qwen2.5-1.5b without=38 with=470 delta=432
```

Four hundred and thirty two tokens of tool definitions reached the model. Qwen's under-calling
is judgement. A delta near zero would have meant the template dropped the definitions and the
model was never asked, which is our bug wearing a model's clothes. The probe is now part of
the benchmark, because the distinction is the difference between fixing a prompt and fixing a
renderer.

## One seed, and why that is not a corner cut

τ-bench runs three trials and reports pass^k; Terminal-Bench runs five. Both do it because
their agents are stochastic and a single run of one is a draw from a distribution. Ours is not,
once tools are on the table: temperature zero, a fixed prompt and a fixed model file have one
answer. BFCL, which is the suite closest to what is measured here, publishes no variance
protocol at all.

Copying three trials from a suite whose reason for having them does not apply is cargo cult,
so the benchmark runs one seed and proves the premise instead. On a template that renders
tools itself the two format arms differ in nothing, so they are the same prompt run twice, the
second on a cache the first one warmed:

```
DETERMINISM model=qwen2.5-1.5b identical=true
```

Identical tallies say both that the sampler is deterministic and that prefix reuse does not
perturb it. If that ever stops being true the benchmark fails rather than quietly averaging.

## What the matrix was cut to

576 generations to 84, by removing what the data showed measured nothing.

| | Before | After | Why |
|---|---|---|---|
| Arms | 4 wordings | 2 formats | Three of six models scored identically across all four wordings |
| Cases | 8 | 6 | Two were second copies of a neighbour: a second recency question, a second settled fact |
| Seeds | 3 | 1 | Zero variance at temperature zero, now verified rather than assumed |
| Models | 6 | 6 | Kept: at fourteen generations each they are about a minute apiece, and they cover six template families |

The cases are chosen so that each of the three tools in the shipped catalogue is right exactly
once, and half the set needs no tool at all, so a model that always calls and a model that
never calls both score fifty percent and neither can look competent.

## What the cut matrix says

The whole thing, 2026-08-15, six models on `pineapple`, catalogue of three tools, six cases,
one seed:

| model | template renders tools | bare | tagged | over | under | wrong tool |
|---|---|---|---|---|---|---|
| qwen2.5-1.5b | yes | 4/6 | 4/6 | 0 | 2 | 0 |
| gemma3-1b | no | 1/6 | 2/6 | 3 | 0 | 2 then 1 |
| llama3.2-3b | yes | 3/6 | 3/6 | 1 | 2 | 0 |
| lfm2-1.2b | no | 2/6 | 3/6 | 1 then 2 | 3 then 1 | 0 |
| phi4-mini | no | 4/6 | 3/6 | 2 then 3 | 0 | 0 |
| granite3.3-2b | yes | 2/6 | 2/6 | 2 | 2 | 0 |

The two arms only mean anything on the three models that read their format out of the prompt,
and there they are **1 to 2, 2 to 3, and 4 to 3**: two up by a case, one down by a case, net
one out of eighteen. A case is seventeen points at this size, so the honest reading is that
the two formats are indistinguishable here and the tagged envelope has not earned the default.
It stays as an arm and as a parser rule, which costs nothing and is where the evidence is.

The scores are lower than the previous table's because the cases changed, not because anything
got worse. Two easy ones went and an arithmetic question arrived, which is a judgement about
whether to compute rather than whether to look up, and most of these models do not make it.

## Ordering or caching: it is the ordering

The question was whether tool-choice accuracy is limited by the order the tools are listed in
or by the KV cache the decision is made over. Every number above was taken with the context
cleared before each generation, so all of it describes the cold case and none of it could
answer. Two arms, 2026-08-15, on `pineapple`:

| model | template renders tools | order reversed | asked over a warm cache |
|---|---|---|---|
| qwen2.5-1.5b | yes | 0 of 6 changed | 0 of 6 changed |
| gemma3-1b | no | **3 of 6 changed** | 0 of 6 changed |
| LFM2.5-2.6B | yes | **1 of 6 changed** | 0 of 6 changed |

**Caching: nothing, 0 of 18.** Prefilling the same prompt cold and continuing from a cache
that already holds it produce the same choice every time. The concern was real in principle,
since a warm continuation reads dequantised values back out of the cache while a cold prefill
computes in full precision, and greedy routing is an argmax over logits that are often close.
It does not happen here.

**Ordering: 4 of 18, and concentrated where the accuracy is worst.** Gemma's forward-order row
is the finding: it picked `web_search`, the first tool in the list, for **all six cases**,
including the arithmetic one and the three that needed no tool at all. Reversed, it stopped
doing that. Its over-calling was never really a judgement about the web; it was picking what
it was shown first.

Two things follow. The catalogue order is a real lever, and it is currently nobody's decision:
it is whatever order `ToolsModule` happens to register in. And this is the mechanism behind
the earlier finding that four different system messages made no difference on three of six
models, because no wording competes with position.

Reversing also nudged the scores up, 1 to 2 on Gemma and 3 to 4 on LFM. That is one case each
and not a reason to reverse the list: choosing an order from six cases would be fitting to the
cases. The benchmark now carries the arm, so an order can be chosen when there is enough to
choose on.

## The route each answer took

There are three ways a call reaches the app, and they are not equally trustworthy: the
template's own parse, the JSON object asked for in the prompt when the template drops tools,
and prose salvage, which reads a tool's name out of an ordinary sentence and builds the call
from the question. The third is a guess, and whether it earns its place was an argument
between two reviewers rather than a number.

It is a number now. The route is recorded per generation and the same run scores both ways at
no extra cost, so `salvaged` and `withoutSalvage` say exactly what salvage is worth on these
models and these cases.

**Over seventy two generations it fired five times and never helped.** Twice on Llama 3.2 3B,
turning one under-call into a right answer and one right answer into an over-call, for a net
of nothing; three times on Granite 3.3 2B, where the arm scored 2/6 with it and 3/6 without.
Nothing else in the run reached it at all.

That is not enough to delete it. The path was built on watching real turns, where a model
names its tool and then asks permission, and every case here is a single decision rather than
that shape: a model that has just been handed a tool result is where the behaviour was
observed and where these cases never go. What it does mean is that the belief salvage helps is
now unsupported by every measurement there is of it, and the counter runs on every benchmark
from here.

## What was taken from Hermes, and what was not

[Hermes](https://hermes-agent.nousresearch.com/) is a full agent harness and most of it
assumes a frontier context window or a server. Judged against a 2048 token window on a phone:

| Theirs | Taken | Why |
|---|---|---|
| `<tool_call>` envelope | Yes, as an arm and a parser rule | It is what a large share of tool fine-tuning data looks like, so it may already be in the weights rather than needing to be taught |
| Hard output caps with pagination | Yes | `read_file` now says how much it cut and the offset to ask for. A window presented as a whole file is how a model concludes a document does not mention something |
| A circuit breaker on repeated denials | Yes, narrowed | Ours is per call rather than per tool: declining one address is not declining the tool |
| A registry of 40 to 73 tools | No | Six cost 672 tokens to describe, a third of the window, before anybody says anything |
| MCP discovery, browser automation, sub-agents, vector memory | No | Each assumes a server, a second model, or both |

The parser rule is the part worth stating plainly: a call is now read whether the model names
the tool under `"tool"` or under `"name"`, tagged or bare. Refusing a spelling the model has
seen ten thousand times in training means refusing the call it actually made.

## What a catalogue costs

Measured, not estimated:

| Catalogue | Tokens to describe |
|---|---|
| The three every install has | 378 |
| All six, once a folder is shared | 672 |

On the 2048 token window these models are given, the second is a third of it, spent on every
pass of every turn. That is why the file tools stay out of the prompt until there is a folder
for them to work in, and why a seventh tool is not free: it costs tokens once and accuracy
again, since choosing among tools gets measurably harder as the list grows. There is a test
that fails if either number drifts.

The accuracy half of that is still unmeasured on hardware, and there is a reason it is
awkward rather than merely undone. The benchmark offers what the app would offer, filtered by
`isAvailable`, so on a device where nobody has shared a folder the catalogue is three tools
and cannot be made six. Faking it would measure a catalogue the app never assembles. Measuring
it honestly needs a grant taken through the system picker on the test device, which is a
person tapping a dialog, so the six-tool number will arrive from a phone somebody uses rather
than from a device cloud instance.

## Test practice that changed

- The benchmark used to swallow a crash. A model that died mid-run left a table with a hole
  in it and the run still passed.
- The control arm sampled at the app's temperature after `TurnRunner` had started forcing
  greedy, so it was measuring behaviour the app no longer had.
- A truncated tool call was dispatched with empty arguments, and the test covering it asserted
  the empty object while being named for the opposite.
- `ToolCallingTest` asserted a model's output at temperature 0.1, which is a coin weighted by
  the sampler. Greedy now, so a failure means something changed.
- The context is cleared between benchmark cases. A run used to accumulate until the window
  filled, which is what `llama_decode returned 1` was: the sixth model died partway through
  and took its remaining arms with it.
