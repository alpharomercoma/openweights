# Saying hello, and being answered about the date

A user reported that on a fresh chat, "hi" came back as a remark about today's date. The
suite that exists for exactly this said the problem had been fixed a year earlier. Both
were right: the wording was fixed and the failure had moved, and the suite could not see
where it moved to.

This is what the failure actually is, why the measurement missed it, and what changed.

## It is not the date being recited

The 2026-09-01 work priced a known cost: "greetings echo the date back ~8/30 at 0.8,
cosmetics, priced against wrong answers". An echo is cosmetic. What the reports show is
not an echo:

```
hey          → It seems like you just mentioned today's date. Could you please tell me
                more about what you'd like to discuss or any details you need help with?
good evening → I'm sorry, but I don't have a way to directly respond with a greeting or
                check the current date for you.
hi           → Thank you! The date you provided (2026-09-05) isn't enough information
                for any specific action.
```

The greeting is not answered at all. The reply is about the date exchange.

The mechanism follows from where the date sits. It rides on the conversation's first user
turn, so the prompt a greeting arrives in is:

```
system     <instructions, then ~1,700 tokens of tool definitions>
user       Today is 2026-09-05.
assistant  Understood, I have that.
user       hi
```

"hi" carries nothing. The nearest user turn with any content in it is the date, so that is
what gets answered. Every other prompt in the suite ("what is the capital of France",
"write a haiku") carries enough of its own to win, which is why only greetings show it.

## Why the suite said it was fixed

`eval/date_placement_eval.py` measured the shipped structure at **0 of 30**. Two reasons,
and the second is the one worth remembering.

**Six greetings is not enough.** The probe used the first six of its no-call prompts.
Widening to sixteen things a person says when they mean nothing in particular ("yo",
"sup", "howdy", "ok", "cool") found what six missed.

**It measured one pass with the other pass's sampler.** The app runs two passes:

| pass | temperature | repeat penalty | thinking |
| --- | ---: | ---: | --- |
| tool, `TurnRunner.deciding` | 0 | 1.0 | capped at 128 tokens |
| reply, `SamplerParams` defaults | 0.8 | 1.1 | uncapped |

The suite sent `repeat_penalty 1.0` for everything, which is the tool pass's sampler, and
then drew conclusions about the reply. Under the reply pass's own sampler the same
structure fails 21 of 128.

There is a third thing, and the first version of this investigation got it wrong too.
**Both samplers write user-visible replies.** `TurnRunner` offers tools on the first pass;
when that pass emits no call, its own prose *is* the answer. So with tools on, which is
Auto and the default, the greeting is answered **greedily at temperature 0**, and only
with tools off does the 0.8 sampler write it. Measuring one is measuring half the product.

`eval/date_structure_eval.py` replaces the old suite and reports both.

## What was measured, and where

Two harnesses, and they disagree. The host one ranks wordings cheaply;
`eval/date_structure_eval.py` replays the app's bytes against `llama-server` and reports
both samplers. The phone one decides: `DateStructureProbe` runs the app's own prefix, the
app's own exchange, the app's own catalogue and the app's own sampler, including the
128-token reasoning cap that no server reproduces.

The device table, sixteen smalltalk prompts on the pass that writes the reply when tools
are on, which is the default:

| shape | LFM2.5-1.2B | Qwen3-1.7B | date answered |
| --- | ---: | ---: | --- |
| bare ack (before) | 6/16 | 1/16 | yes |
| scoped ack alone | 3/16 | 1/16 | yes |
| bare ack, spaced | 1/16 | 0/16 | yes |
| **both, as shipped** | **0/16** | **0/16** | yes |
| no date at all | 0/16 | 0/16 | **no** |

The last row is why none of this can end in deleting the exchange: with no date in the
conversation, "what is today's date?" drew `run_script` on one model and `web_search` on
the other. Two turns of ordinary conversation are also a worked example the routing leans
on: without any, an earlier sweep measured QAD's trivia falling 6/6 to 4/6.

The host table, on LFM2.5-1.2B with the sixteen-tool catalogue, which is what the host
harness sends:

| arm | tools on | tools off | overcall | miss | dateOK |
| --- | ---: | ---: | ---: | ---: | --- |
| shipped before | 3/16 | 21/128 | 1/12 | 1/5 | yes |
| scoped ack | 0/16 | 7/128 | 1/12 | 2/5 | yes |
| spaced | 0/16 | 1/128 | 1/12 | 1/5 | **no** |
| spaced + scoped | 0/16 | 0/128 | 1/12 | 2/5 | **no** |
| date in the instructions | 2/16 | 2/128 | 2/12 | 2/5 | **no** |
| no date at all | 0/16 | 1/128 | 2/12 | 2/5 | no |

The two tables agree about the bug and disagree about the fix. On the host the spaced
shapes lose the date question outright; on the phone they keep it. The difference is the
prompt around the exchange: the host sends sixteen tools from `prompt_dump.json` and a
real turn sends whatever the user has switched on, and what the model can see changes what
it does with a greeting as much as the exchange does. **The phone wins.** A host result is
a ranking, not a verdict.

## The change

Two changes, because one was not enough and each fixes a different half.

```
user       Today is 2026-09-05.
assistant  Understood, I have that. I will not bring it up unless a question depends on it.
user       Ready when you are.
assistant  Ready.
```

**The ack carries the constraint, in the model's own mouth.** The same constraint in the
*user's* line broke the date question on every model measured in 2026-09-01: "only mention
it if asked" produced a `read_memory` call for "what is today's date?". As something the
model has already said, it is a commitment it keeps rather than an instruction it reasons
about. On its own this took LFM2.5-1.2B from 6/16 to 3/16.

**Then something ordinary is said.** Nothing in the handover is about the date, and that is
the point: what makes a greeting come back as a remark about the date is that the date is
the nearest thing the user said. Anything in between takes the adjacency away. On its own
this took 6/16 to 1/16; with the ack, to none.

The whole exchange is about fifteen tokens of constant text sitting immediately behind the
head, so it is warmed once with the prefix and costs nothing per turn.

## Three things that look better and are not

**The date in the instructions.** Answers greetings and loses the date question. The
template renders the tool block *after* the system message, so the fact ends up ~1,700
tokens from the question and stops being recallable. This is the placement abandoned in
2026-09-01, for this reason, and the numbers reproduce.

**The date in the model's own mouth** ("what is today's date?" / "Today is X."). The worst
result measured, 86 of 128. Having said it once, the model says it again at every chance.

**Deleting the exchange.** The floor on bleed and the floor on everything else too: the
date question becomes a tool call, and the worked example the routing leans on goes with
it.

## The cost, and where it lands

On Qwen3-1.7B the scoped ack makes the model deliberate about whether a question depends on
the date: reasoning grew from 281 to 2,369 characters on a host server with thinking
uncapped, and the answer picked up a hedge. That is not what the app does, because the
pass that writes this reply caps thinking at `TOOL_PASS_REASONING_BUDGET`, 128 tokens, and on the
phone the same question is answered in one line. It is the clearest case for why the device
is the arbiter for a thinking model.

## Rules this leaves behind

- **Name the pass.** Any measurement of a reply states which of the two samplers wrote it,
  because both reach the user and they disagree.
- **A greeting is the probe.** Only a prompt with no content in it can lose to the turn
  before it. A suite of well-formed questions cannot see this class of bug at all.
- **Whose turn a constraint sits in changes what it does.** The same sentence is an
  instruction to reason about in the user's mouth and a commitment in the model's.
- **Thinking models need the device.** The reasoning cap is part of the prompt's behaviour
  and no host harness has it.
