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
| LFM2.5-2.6B | yes | **2 of 6 changed** | 0 of 6 changed |

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

## What the under-calling looks like from the user's seat

The tables above are the harness measuring itself. This is the same number arriving in
somebody's hand: seven questions typed into the composer of a signed release build on a
Snapdragon 8 Gen 3, 2026-08-15, with the model downloaded through the app's own Discover tab
rather than pushed over adb. Method, so it can be repeated: cold start, one fresh chat, auto
mode, every tool on, the app's own reported throughput.

**Qwen 2.5 1.5B Instruct Q4_K_M** (1.04 GB, downloaded and checksum-verified in under a
minute; backend `armv8.6`, model loaded 1.0 s after the backend was chosen):

| Asked | Answered | Right | tok/s |
|---|---|---|---|
| 17 times 24, number only | 408 | yes | 18.3 |
| Who wrote Frankenstein | Mary Shelley | yes | 13.7 |
| Three primes under ten, commas only | 2, 3, 5 | yes | 22.1 |
| Capital of Australia, one word | Canberra | yes | 13.3 |
| In a transformer, what does the KV cache store | "key-value pairs … can be either read-only or writeable depending on the use case" | half | 25.0 |
| Who won the most recent World Cup final | **"France won the most recent FIFA World Cup final."** | **no** | 23.6 |
| **"Use web_search to find the current weather in Manila"** | answered without searching | **no** | 25.0 |

Four of seven right, one half right, two wrong. Every one of those turns logged `calls=0`.
The last row is the sharpest form of it: the tool was offered, the user named it in the
prompt, and the model still did not emit a call.

**LFM2.5 2.6B Q4_K_M** (1.56 GB, downloaded the same way, switched to by tapping its row in
Models), same seven questions, same chat, same device an hour later:

| Asked | Answered | Right | Searched | Wall |
|---|---|---|---|---|
| 17 times 24, number only | 408 | yes | no | 15 s |
| Who wrote Frankenstein | Mary Shelley | yes | **yes** | 25 s |
| Three primes / capital of Australia | "2,3,5 Canberra" | yes | no | 38 s |
| In a transformer, what does the KV cache store | "the key and value tensors (K and V) from the self-attention layers … without recomputing all previous attention operations" | yes | no | 33 s |
| Who won the most recent World Cup final | **Argentina** | **yes** | **yes** | **457 s** |
| "Use web_search to find the current weather in Manila" | searched | — | **yes** (`calls=1`) | — |

Two of those rows are the whole argument.

**The World Cup row is the trade in one line.** Qwen answered "France" in 0.6 seconds without
searching. LFM2.5 reasoned that "my knowledge might not be current", searched, fetched a
Wikipedia page to confirm, and answered "Argentina" in seven and a half minutes. Right and
slow against wrong and instant, on the same phone, in the same app, on the same question.

**The Frankenstein row is the cost of the fix.** LFM2.5 searched for the author of
Frankenstein, which it plainly knows, because it decided to "verify it with a web search".
That is the over-calling the benchmark scores as `over`, and it is what a user pays for the
World Cup row: twenty five seconds and a network round trip for a fact the model had.

So the two failure modes are not a spectrum with a good middle; they are two different models.
The one that never searches is fluent and wrong past its cutoff. The one that searches is
right and makes you wait, sometimes for something it already knew. Nothing in the harness
chooses between them, and the app ships whichever the user downloaded.

Three parts of the app were seen working during this that no test had reached on hardware:
the consent card appeared for the first search and never again once answered, compaction fired
live at 83% of the window with "Folding earlier turns into a summary", and the thermal policy
showed "Cooling down" during the long tool turns.

## Looking for a model that is both, and not finding one

The obvious next move was a model with switchable thinking: reason while deciding whether to
use a tool, do not reason while writing prose. Qwen3.5-2B was the candidate, chosen because
its template renders tools into the `<tool_call>` envelope **and** exposes `enable_thinking`,
at 1.19 GB, which is within a fifth of the Qwen 2.5 file whose 13 to 25 tok/s we had measured.
It is the first model in this app to load as `tools=true thinking=true`.

It failed, in a way worth writing down.

| Asked | Thinking | Answered | Called a tool | Wall |
|---|---|---|---|---|
| 17 times 24, number only | on (default) | 408, correct | no | 24 s, of which 24 s was thinking |
| Who won the most recent World Cup final | **off** | Argentina, correct | **no** | 114 s |
| "Use web_search to find the current weather in Manila" | **off** | **"Clouds and sun with a temperature of 85°"** | **no** | 49 s |

Three findings, in order of how much they matter.

**It fabricates tool use.** On the World Cup question it wrote "Based on the search results, I
can see that…" having run no search, and on the weather question it reasoned "I should use
web_search to find this information since it's a current condition that could change" and then
invented a specific reading. `calls=0` on both. Qwen 2.5 under-calls and answers plainly from
memory, which is wrong but honest. This dresses invention as verification, and no part of the
screen contradicts it.

**The thinking switch does not take.** Turning it off left "Thought for 15.5s" on one answer
and moved the reasoning into the visible reply on another. This is the case the engine already
anticipates and cannot detect at load: `supports_thinking` asks whether the template renders
differently, and it does; whether the weights care is a separate question that only a reply can
answer. So the per-pass thinking idea is not merely unproven on this model, it is not
actionable on it.

**Being right was not better routing.** Qwen3.5 got the World Cup right because its training is
newer, not because it searched. The question is a good staleness probe for a 2024 model and not
for a 2026 one; the weather question is the honest one, and it failed that.

So: across three models and about twenty questions typed into the composer, nothing has both.
Qwen 2.5 is fast and never calls. LFM2.5 calls well and takes up to seven and a half minutes.
Qwen3.5 is slow like LFM, calls like Qwen 2.5, and additionally claims to have searched when it
has not. The conclusion this points at is not "keep looking for the model": every candidate
that decides well is too slow to use, and the next section is what happened when we tried to
make one of the fast ones decide anyway.

### Naming a tool, before and after — built, measured, reverted

A repair pass was added that fired when the request named an available tool and no call came
back, and then removed once it had been measured. The measurement is why it was removed, and
is kept here so nobody builds it a second time. Same device, same model, same question, forty
minutes apart:

| | Before | After |
|---|---|---|
| "Use web_search to find the current weather in Manila" | `calls=0` | **`calls=1`**, then an answering pass |
| What the user was told | "The current weather in Manila is Clouds and sun with a temperature of 85°" — **invented** | a page actually fetched, and the answer written from it |
| Wall | 49 s | **799 s** |

Both halves of that are the finding, and the right-hand half is the one that decided it. The
nudge does what it was built to do: a fabricated answer became a real one. It costs sixteen
times the wall clock to do it, because a model that only searches when pushed is a model that
takes thirteen minutes when pushed. Nobody waits thirteen minutes for the weather, so the
honest answer is worth less on a phone than the fast wrong one is, and the change was reverted
rather than kept as a setting nobody would turn on.

Two details worth keeping about the pass itself. It called `fetch_url` rather than the
`web_search` it was told to use, so what such a pass buys is *a* call rather than the named
one; the app does not build the call, so which tool is still the model's to choose. And
`fetch_url` asks every time, so the user saw an approval card mid-turn.

What the multiplier is *made of* is not settled by this row, and the obvious reading is
probably wrong. The after turn did three things the before turn did not: it generated a second
time, it ran two tools instead of none, and it put a fetched page into the context. The third
is the one with a number attached — `fetch_url` returns up to 4,000 characters, roughly a
thousand tokens, and `ToolBudget` will hand over every one of them when the window has room.

Set beside the LFM2.5 table above, that reading gets sharper. The turn that searched and did
not fetch took 25 s. The two turns that fetched took 457 s and 799 s. Three points is not a
result, but it is the difference between "a second generation is unaffordable" and "a fetched
page is unaffordable", and those two beliefs lead to completely different work. **Measure it
before building on either**: same question, same model, `fetch_url` switched off so only
`web_search` is on the table. If the World Cup turn comes back in under a minute, the cost is
the page rather than the pass, and it is a cap away from being fixed.

Two things follow that the arms above do not show. The first is that the closed questions are
all correct and fast, so nothing on screen distinguishes the wrong answers from the right
ones: a confident sentence at 23 tok/s either way. The second is that the failure is not
noise. France won in 2018 and the question said "most recent"; the same wrong answer came
back on two different devices, hours apart, from a model that had a search tool in front of
it the whole time.

This is the product's central weakness and it is a routing problem rather than a speed one.
`Tool.isAvailable`, the catalogue ceiling and the greedy pass all exist to make this decision
better, and they have moved it; what they have not done is make a 1.5B model reach for the
web when the answer it already has feels good enough.

## Two models built for calling, and a bug that hid one of them

Two candidates the earlier search had missed, both pushed to `pineapple` and run through
`ToolChoiceBenchmark` on 2026-08-16 against a freshly pushed Qwen 2.5 as a control:

- **Hammer 2.1 1.5B** at Q4_0, 937 MB, *smaller* than the Qwen 2.5 1.5B already shipping.
  Qwen 2.5 fine-tuned for function calling. Q4_0 rather than Q4_K_M on purpose: the q6_K
  tensors inside Q4_K_M have no KleidiAI kernel and cost nearly half the decode rate.
- **FunctionGemma 270M** at Q4_0, 242 MB, Google's function-calling model, converted to GGUF
  by ggml-org itself.

Scored, both came back at 3/6 with every case a null, against the control's 4/6. Scored, that
reads as two models that never call anything. One of those scores was wrong by two cases, and
the reason it was wrong is worth more than either model is.

### What they actually wrote

`ToolChoiceBenchmark` records the call that was *parsed*, which is the right thing to score
and the wrong thing to debug with: a row of nulls means either the model declined or nobody
could read it, and those mean opposite things. `RawReplyProbe` exists to tell them apart. It
logs the reply, both parsers' verdicts, and nothing else. Asked to read a URL:

```
model=qwen2.5-1.5b    native=null prompted=null
  RAW=I don't have real-time weather data available. You can check the current weather in
      Manila by visiting a weather website or app…

model=hammer2.1-1.5b  native=null prompted=null
  RAW=[{'type': 'function', 'function': {'name': 'fetch_url',
       'arguments': {'url': 'https://example.com'}}}]

model=functiongemma-270m native=null prompted=null
  RAW=<start_function_call>call:fetch_url{url=example.com}<end_function_call>
      <start_function_response>call:web_search{query:example"example"example"example…
```

Qwen declined, in prose, and its null is honest. Hammer named the right tool and filled in the
right argument, and was scored as a refusal. It did the same for `run_script` on the
arithmetic case, with `48273 * 1179` in the argument.

Three separate faults had to line up to lose it, and all three did:

1. Hammer's template renders the tool definitions, so `supportsTools` is true and llama.cpp
   parsed the reply expecting the Hermes `<tool_call>` envelope. Hammer's template asks for a
   bare JSON array instead, so there was no envelope and the engine returned no calls.
2. Being native **switched off** the prompted parser, which is the one that could have read
   it. A template that renders tool definitions is not a promise about the syntax the weights
   will answer in, and the code was treating it as one.
3. Even ungated it would have failed, because Hammer writes a Python dict repr — `'name'`,
   not `"name"` — and `CALL_KEYS` held only the double-quoted spellings.

A fourth would have bitten immediately after: every tool reads its own arguments by parsing
that string as JSON, and `{'url': …}` is not JSON, so a call that parsed perfectly would have
reached `fetch_url` with no url in it and been answered "you gave me nothing".

All four are fixed, with Hammer's own device output as the test fixture: either quote is
accepted, closing on the quote it opened with so `"o'brien"` is untouched; the prompted parser
runs whenever the native one comes back empty; and a single-quoted arguments object is walked
into JSON. The control is the evidence the fix is neutral — Qwen re-ran **bit-identical**,
same 4/4/4/3, same `picked`, same `ORDERING 0/6`, same `CACHE 1/6`.

### What Hammer scores once it can be read

The whole benchmark again on a second `pineapple`, `72c4dabb`, both APKs current:

| | qwen2.5-1.5b | **hammer2.1-1.5b** |
|---|---|---|
| bare / tagged / reversed | 4/6 · 4/6 · 4/6 | **5/6 · 5/6 · 5/6** |
| **warm** | **3/6** | **5/6** |
| ordering changed | 0 of 6 | 0 of 6 |
| cache changed | **1 of 6**, and cost a case | **0 of 6** |
| ms a case | 6153–6823 | **5163–5500** |
| on disk | 1117 MB | **937 MB** |

`picked=[null, fetch_url, run_script, null, null, null]`, identical in all four arms. It is the
best score any model has recorded here, from the smallest file, in the least time.

The warm row is the one that decides it. Qwen *loses* a case as soon as the cache is warm, and
warm is not an exotic condition: it is the state every pass after the first runs in, so a turn
that has already called a tool is routing at 3/6 rather than the 4/6 on the label. Hammer is
flat at 5/6 either way, and unmoved by catalogue order too.

The control is what makes that readable. Qwen came back **bit-identical across three runs on
two physical devices** — same arms, same `picked`, same `ORDERING 0/6`, same `CACHE 1/6`. The
parser reads more; it does not read differently.

Hammer's one miss is the weather case, where it wrote `[]`, which its template defines as "no
call needed". That is a judgement rather than a parse failure, and it is the same blind spot
every model here has: it will not reach for the web unprompted. Nothing in this section touches
that, and it remains the product's central weakness.

FunctionGemma is a separate matter and the probe is enough to set it aside. It emits a third
format again — `<start_function_call>call:fetch_url{url=example.com}<end_function_call>`,
correct as far as it goes — then invents a `<start_function_response>` block and degenerates
into `example"example"example"` for the rest of its budget, so it needs a fourth parser *and*
a stop token. It also refused the arithmetic by reasoning that the tools only accept
`"news," "prices," or "schedule"`, which is a fixed mobile-actions catalogue rather than ours.
It is genuinely fast — 1,706 ms a case against Qwen's 6,088 — and that is the only reason to
come back to it.

## A template lost in conversion, worth two cases out of six

Arch-Agent 1.5B is the most recently published of the purpose-built callers, April 2026, and
its GGUF conversions are all broken in the same way. The source keeps its chat template in
`chat_template.jinja`; llama.cpp's converter reads `chat_template` out of
`tokenizer_config.json`; so every conversion on the Hub carries **no template at all**. Its
real one asks for the Hermes envelope, which is the single shape the engine parses natively,
so the defect costs it exactly the route it was built for.

Both were run, the file as published and the same file with the template put back by
`gguf-new-metadata`:

| arch-agent-1.5b | renders tools | bare | tagged | reversed | warm | over | under | ordering |
|---|---|---|---|---|---|---|---|---|
| as published | **no** | 4/6 | 5/6 | 4/6 | 4/6 | 0 | 2 | **2 of 6** |
| template restored | yes | **6/6** | **6/6** | **6/6** | **6/6** | **0** | **0** | 0 of 6 |

Same weights, same quantization, same device, one metadata key apart. **6 of 6 in every arm**,
`picked=[web_search, fetch_url, run_script, null, null, null]`, unmoved by ordering or by a
warm cache. It is the first model measured here to get every case right, and the first to take
all three tool cases *and* decline all three others.

The broken row is worth keeping for two reasons. It is the only case anywhere in this file
where the tagged envelope beat the bare object, 5 against 4, and that is not noise: the model
was trained on `<tool_call>`, so asking for the tagged shape in the prompt asks for its native
format. And it has the worst ordering sensitivity of anything tested, 2 of 6, picking
`fetch_url` forward and `run_script` reversed — a model reduced to choosing one tool, with
list position deciding which.

## Three purpose-built callers, and what separates them

The same suite, second `pineapple`, all against the Qwen 2.5 control:

| model | size | bare · tagged · rev · warm | over | under | order | cache | ms |
|---|---|---|---|---|---|---|---|---|
| **arch-agent-1.5b (restored)** | 986 MB | **6·6·6·6** | 0 | 0 | 0/6 | 0/6 | 8.0–8.8 s |
| hammer2.1-1.5b | 937 MB | 5·5·5·5 | 0 | 1 | 0/6 | 0/6 | **5.1–5.3 s** |
| xlam2-1b | 935 MB | 4·4·5·4 | 1 | 1 | 1/6 | 0/6 | 4.8–5.2 s |
| xlam2-3b | 1823 MB | 4·4·5·4 | 1 | 1 | 1/6 | 0/6 | 8.3–9.1 s |
| qwen2.5-1.5b *(shipping)* | 1117 MB | 4·4·4·**3** | 0 | 2 | 0/6 | **1/6** | 6.4–6.9 s |
| functiongemma-270m | 242 MB | 3·3·3·3 | 0 | 3 | 0/6 | 0/6 | **1.7–2.0 s** |

The scores hide the interesting part, which is that the failures are not the same failure.

**Hammer and the xLAMs have opposite blind spots.** Hammer takes `fetch_url` and `run_script`
and writes `[]` for the weather; both xLAMs take `web_search` and `fetch_url` and miss the
arithmetic. xLAM-2-3b is the first model in this project to search for the weather at all,
which is the exact under-call the composer runs kept finding — and it pays for it by searching
the web to translate "good morning", which is the over-call LFM2.5 was rejected for. Two sides
of one coin, and neither model has both sides. Arch-Agent restored is the only one that does.

**The 3B bought nothing.** xLAM-2-1b scores what xLAM-2-3b scores, on the same cases, at half
the disk and 60% of the wall clock. Whatever the extra two billion parameters are for, six
single-turn routing decisions do not show it.

## Fifteen models, both axes

Everything available, on `pineapple` 72c4dabb, 2026-08-16. `6-case` is the four-arm
single-turn score written best-arm; `multi` is the pair from `MULTI`, where 1 of 2 is what a
model scores by declining everything *and* by calling everything, so only 2 discriminates.

| model | on disk | route | 6-case | multi | order | cache | ms |
|---|---|---|---|---|---|---|---|
| **arch-agent-1.5b, template restored** | 986 MB | native | **6/6** | **2/2** | 0/6 | 0/6 | 8.1 s |
| hammer2.1-1.5b | 937 MB | native | 5/6 | 1/2 | 0/6 | 0/6 | **5.2 s** |
| phi4-mini | 2491 MB | prompted | 4/6 | **2/2** | 2/6 | 1/6 | 15.4 s |
| qwen2.5-1.5b *(shipping)* | 1117 MB | native | 4/6 | 1/2 | 0/6 | 1/6 | 6.4 s |
| xlam2-1b | 935 MB | native | 4/6 | 1/2 | 1/6 | 0/6 | 4.5 s |
| xlam2-3b | 1823 MB | native | 4/6 | 1/2 | 1/6 | 0/6 | 8.4 s |
| qwen2.5-coder-3b | 1998 MB | native | 4/6 | 1/2 | 2/6 | 0/6 | 9.3 s |
| arch-agent-1.5b, as published | 986 MB | prompted | 4/6 | 1/2 | 2/6 | 0/6 | 6.5 s |
| granite3.3-2b | 1453 MB | native | 3/6 | 1/2 | **4/6** | 1/6 | 9.5 s |
| llama3.2-3b | 1922 MB | native | 3/6 | **0/2** | 2/6 | 0/6 | 12.4 s |
| gemma4-e4b | 4591 MB | native | 3/6 | 1/2 | 0/6 | 0/6 | **20.5 s** |
| functiongemma-270m | 242 MB | native | 3/6 | **error** | 0/6 | 0/6 | **1.9 s** |
| lfm2-1.2b | 696 MB | prompted | 2/6 | 1/2 | 0/6 | 0/6 | 3.5 s |
| smollm3-3b | 1915 MB | prompted | 2/6 | 1/2 | 1/6 | 0/6 | 13.2 s |
| gemma3-1b | 806 MB | prompted | 1/6 | **error** | 3/6 | 0/6 | 4.1 s |

**One model is good at both, and it is the smallest serious one.** Arch-Agent with its template
put back is 6/6 on every arm and 2/2 on the pair, unmoved by ordering or by a warm cache, at
986 MB. Nothing else manages both columns.

**The two columns disagree, and that is the point of adding the second.** Hammer leads the
single-turn table at 5/6 and then declines both follow-ups. Llama 3.2 gets all three
single-turn tool cases right — better than Hammer on that axis — and scores 0 of 2, calling
`fetch_url` for a fact sitting in the transcript above it. Phi-4-mini is mediocre at 4/6 and is
one of only two models to discriminate on the pair. A suite of opening turns would have ranked
these three in exactly the wrong order for an app whose every tool turn has a second pass.

**Most models score 1 of 2 by never calling again.** Qwen, Hammer, both xLAMs, Qwen Coder,
Granite, LFM2, SmolLM3, Gemma 4 all return `picked=[null, null]`. They take the point for
refusing to re-search a fact they already have and lose the one for the second city. That is a
policy, not an accident, and for a harness built on multiple passes it is the wrong one.

**BFCL's multi-turn ranking did not transfer.** xLAM-2-3b is the best model under 4B on that
board at 55.62 multi-turn against the 1B's 8.38, and here the two are indistinguishable: same
4/6, same 1/2, same ordering and cache numbers, twice over. The 3B costs 1.9 times the disk and
1.9 times the wall clock for nothing measurable. Two cases cannot refute a leaderboard, but
they can say the advantage does not reach this app.

**Size buys nothing here.** Ranking by parameters gives almost the reverse of ranking by score.
Gemma 4 E4B is the largest thing tested at 4591 MB, never calls a tool once in 24 generations,
and takes 20.5 seconds a case; Arch-Agent is a fifth of its size and perfect. Every model above
2 GB is beaten by one under 1 GB.

**Three separate ways a capable model arrives unable to call anything.** Arch-Agent's template
is dropped in GGUF conversion. SmolLM3's template gates its tool block on an `xml_tools`
variable rather than `tools`, so llama.cpp renders nothing and it falls to the prompted route.
Gemma 3 and FunctionGemma cannot render a tool *result* at all — `Unable to generate parser for
this template` — so they error on both multi-turn cases and cannot participate in a second pass
under any circumstances. None of the three is a fact about the weights.

## The route each answer took

There are three ways a call reaches the app, and they are not equally trustworthy: the
template's own parse, the JSON object asked for in the prompt when the template drops tools,
and prose salvage, which reads a tool's name out of an ordinary sentence and builds the call
from the question. The third is a guess, and whether it earns its place was an argument
between two reviewers rather than a number.

It is a number now. The route is recorded per generation and the same run scores both ways at
no extra cost, so `salvaged` and `withoutSalvage` say exactly what salvage is worth on these
models and these cases.

**It fired seven times across two runs and never once helped.** Twice on Llama 3.2 3B, turning
one under-call into a right answer and one right answer into an over-call, for a net of
nothing. Three times on Granite 3.3 2B, where the arm scored 2/6 with it and 3/6 without. Once
per arm on LFM2.5 2.6B, where the reversed order scored 4 with it and 5 without.

So it is gone. Looking at what it fired on says why: it was built on watching a model announce
a tool and then ask permission, which is a short sentence, and what it caught was models
mentioning a tool inside an answer they had already finished. A length gate, added first,
helped and did not fix it, because a model can genuinely mention a tool in a short reply and
still not be asking for it.

What replaced it costs a pass and decides nothing. The repair round already existed for
call-shaped markup neither parser could read; it now fires on an announcement too, hands back
the real tool names, and lets the model write the call. The recovery is the same and the app
no longer picks which tool was meant or invents the arguments. A pass is a couple of seconds;
a wrong tool is a wrong answer and a query that left the device.

`Tool.callFor` went with it, since nothing called it any more.

**The removal was predicted before it was made, and the prediction held.** The counter that
scored each run both ways said LFM2.5's reversed arm would go from 4 of 6 to 5 of 6 without
salvage. Removing it, the same arm scores 5 of 6, its wrong-tool count falls to zero, and the
ordering effect on that model comes out at 2 of 6 rather than 1, because salvage had been
masking one of the differences. That is the whole reason to count a path apart rather than
argue about it.

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
