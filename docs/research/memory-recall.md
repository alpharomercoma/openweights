# Memory: the store is fine, the recall policy is the open question

Two questions get asked together and are not the same question. *How are the facts kept* is
about storage, and it is settled: nothing in this app needs a vector index. *How do they
reach a conversation* is about routing, and it is the one with a real cost attached, because
it is the one that can waste seconds or silently forget.

## The store: no vector database, and the reason is arithmetic

`Memory` keeps at most **24 facts, 160 characters each, 1,000 characters in total**, in
shared preferences. The question raised was whether HNSW, or the newer quantisation work
around it, would find matches faster.

At this size the question does not arise. HNSW exists to avoid an O(n) scan when n is in the
hundreds of thousands; the whole of this app's memory is a thousand characters, and a linear
pass over it is microseconds. An index would cost a build, a second data structure, and an
embedding model, which on a phone is a second download and a second thing resident in
memory. Every one of those costs is larger than the scan it replaces.

The industry is at the same place for the same reason, at much larger scale. Reverse
engineering of four shipping memory systems in 2026 found that **none of them uses a vector
database as the primary mechanism**:

| product | what is kept | how it is retrieved |
| --- | --- | --- |
| ChatGPT | a fact store plus precomputed summaries | injected: every fact rides on every prompt |
| Claude | a small `<userMemories>` block | block always present, plus tools the model calls |
| OpenClaw | plain Markdown files on disk | hybrid semantic and keyword search, agent-issued |
| Hermes | `MEMORY.md` capped at 2,200 chars, `USER.md` at 1,375 | frozen in the prompt, episodic recall behind SQLite |

Hermes's stated principle is the one this codebase arrived at independently: *keep the prompt
stable for caching, and push everything else to tools.* Its cap is 2,200 characters against
this app's 1,000, so OpenWeights is already the more disciplined of the two.

**Verdict: leave the store exactly as it is.** No index, no embeddings, no second model.

## The recall policy: this app is the outlier

Where OpenWeights differs from all four is that recall is *only* a tool. `ReadMemoryTool`
replaced a block that used to be injected into every prompt, on two arguments that were good
ones: the block cost tokens on conversations that had nothing to do with their user, and it
entered ahead of the user's own words, which is the position a prompt injection would
choose.

Its own documentation concedes what that trades away and names the measurement:

> The price of the trade is honesty about who pays it: a model has to think of calling this,
> where the injected block was simply there. [...] whether a small model pulls it is measured
> in the benchmark, not assumed here.

Nothing had measured it. That is the gap, and it is exactly the concern raised: an app that
was told something and then does not know it, and an app that spends a round trip finding
out it had nothing to say. On a phone the second is not free: a call is a decode, a tool
result, and a re-prefill of a prompt that just grew.

**Two things have changed since that trade was made**, and both point the other way.

The token argument is weaker. A byte-stable block at the head of the prompt is now prefilled
at load time by the warm-prefix work, which took first turns from 18.5 seconds to 184
milliseconds. A thousand constant characters behind the head is paid once per load and never
again, which is not what it cost when the tool replaced it.

The cache argument now actively favours injection. The app's own KV stability rule says the
head must be byte-identical between turns; a fact block that never changes satisfies it, and
a tool result that arrives mid-conversation does not.

## The measurement, written and not yet run

`MemoryRecallBenchmark` compares the two arms on the same models the app recommends.

- **`tool`** is what ships: the facts sit behind `read_memory` with its shipped description,
  and the model has to think of calling it.
- **`injected`** is what it replaced: the facts sit in the system message in the shape
  `Memory.asPrompt` writes them, and there is nothing to call.

Eight cases: four that depend on a saved fact and four that do not. The four that do not are
deliberately the kind of question a model might mistake for a personal one, a general recipe
and a general weather question among them, because a benchmark whose negatives are obviously
impersonal cannot measure an overcall rate worth knowing.

Two numbers decide it. **`miss`** on the cases that depend on memory, which is the feature
failing silently, and **`overcall`** on the cases that do not, which is seconds spent to
learn nothing. The injected arm cannot do either by construction, so what it is really being
measured on is whether the facts derail the questions that are not about them, which is the
failure mode the reverse-engineering work found in the wild: a stored quote about "dopeness"
applied to a Python debugging session.

It has not been run. The cloud device this project measures on had its reservation lapse
mid-session, and the phone was at 14% and unplugged. The benchmark is written, compiles, and
needs one device and about ten minutes.

**The expected result, stated in advance so the run cannot be read generously.** The small
models this app targets have already been measured, in `ToolChoiceBenchmark`, to be
insensitive to tool wording: three of six models scored identically under four different
system messages. If that holds here, no rewording of the tool's description will fix a miss
rate, and injection is the only lever left. Injection would then be right, with `read_memory`
retired rather than kept as a second door onto the same facts.

## What is already right

- **Off by default.** Every memory tool declares `defaultsOn = false`, so an install that
  never opens the Tools screen never saves or reads a thing.
- **Every write is approved.** `save_memory`, `update_memory` and `forget_memory` all set
  `needsApproval` and `alwaysAsks`, so nothing is remembered without the user seeing it.
- **The user can read, edit and delete.** The Tools screen lists the facts, edits one in
  place keeping its age, deletes one, or forgets all of them.
- **It is bounded.** 24 facts, 1,000 characters. A memory that grows without limit is a
  context window that shrinks without explanation.

Those four are the checklist the reverse-engineering piece ends on, which says not to ship
memory unless it is user-visible and directly editable and the recall can be scoped. Three of
four hold today; the fourth, scoping recall to the task, is the thing the benchmark above is
for.

## Sources

- [Reverse Engineering ChatGPT, Claude, OpenClaw, and Hermes Convinced Me Most AI Products Shouldn't Ship Memory](https://manthanguptaa.in/posts/memory_is_a_mistake/)
- [Memories, ChatGPT documentation](https://learn.chatgpt.com/docs/customization/memories)
- [State of AI Agent Memory 2026](https://mem0.ai/blog/state-of-ai-agent-memory-2026)
