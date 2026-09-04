# Showing where a model hesitated

An answer arrives with no marks on it. Every sentence looks equally settled, and the one
invented number in the middle looks exactly like the four recalled ones around it. The model
knows the difference and has always known it: it assigned a probability to every token it
chose, and then threw the probabilities away.

This is what it takes to keep them, what the numbers mean, and the several ways of drawing
them that were rejected.

## The definition, so the number can be checked

Perplexity over a reply is

```
PPL = exp( -(1/N) * sum( ln p(token_i) ) )
```

which is what `llama-perplexity` computes and what every paper means by the word. It reads
as an effective branching factor: one means the model had no doubt anywhere, ten means it
was choosing, on average, as though from ten equally good options. `ConfidenceTest` pins it
against hand arithmetic rather than against whatever the code produced, because a screen
that prints "perplexity 3.4" is making a claim somebody can check.

**The probabilities are the model's own, taken from the raw logits before the sampler.**
This is a decision and not an implementation detail. Temperature, top-k and the penalties
are the user's settings; a confidence that moved when the temperature moved would be
measuring the sampler rather than the model, and the whole point is to say something about
the model.

## What it is not

It is not a truth score, and shipping it as one would be worse than not shipping it. A model
states a wrong date with exactly the serenity it states a right one, because confidence is
about how well a sentence matches the training distribution and not about the world. The
caveat is on the screen, under the numbers, where the person reading the underlines will see
it.

**The average is dominated by the easy tokens.** Punctuation, "the", the second half of a
word already begun: these run near one and there are a great many of them, so a long fluent
answer with one invented figure in it has a comfortable perplexity. `ConfidenceTest` encodes
the case directly: nine tokens at 0.99 and one at 0.03 comes out under 1.5.

That is why the screen leads with the places rather than the number, and why the lowest
single token is reported beside the average. A hesitation is a fork the model actually had
to take, and it is where an answer is worth checking; the mean is a summary of how fluent
the sentence was.

## The four ways of drawing it, and why underlines in a sheet won

**A number above or below each token.** What a research tool does, and it destroys the
paragraph: the reader stops reading an answer and starts reading a table. It is also
undefined for everything that is not prose, which on a model's output means tables, fences
and list markers.

**A background colour ramp per token, in the reply itself.** What most logprob viewers use,
and it cannot be done honestly here. Replies are rendered as Markdown by a library that
turns source into its own composables, so a token offset in the source has no address in
what is on screen, and the source is full of syntax that is never drawn. Colouring a table
cell whose confidence belongs to the pipe character beside it is a worse lie than drawing
nothing. A wash also loses to sunlight on a phone.

**Underlines in the reply itself.** Same problem, same reason.

**Underlines in a sheet, on the answer as plain text.** What shipped. Plain text means a
token and the characters it produced are the same thing, so every mark is exactly where the
measurement was. It also keeps a tool for checking an answer out of the way of reading one:
the chat stays clean, and the lens is one long press away.

Underline rather than highlight, deliberately. A coloured background reads as "these are the
important words"; an underline reads as a query against them, which is what this is.

## Where it lives

| what | where |
| --- | --- |
| the switch | the settings sheet, beside thinking, off by default |
| the perplexity | a fourth figure in the stats panel under a long press |
| the places | "Where it was unsure", an action in the same sheet |

The switch is on the settings sheet rather than in Settings because it changes what
generation does and what it costs, which is the same kind of thing as thinking and answer
length. It is drawn only for llama.cpp: a compiled ExecuTorch model returns text and no
distribution, and a switch that quietly did nothing would be worse than an absent one.

## What it costs

**A log-softmax over the vocabulary per generated token.** For a 150,000-token vocabulary
that is 150,000 exponentials against a forward pass of billions of operations, so it is
small, but it is not nothing and it is why the switch exists.

**Speculation, for the whole generation.** A token accepted from a draft was sampled at a
batch position the loop has moved past by the time the token is emitted, so its logits are
gone. Reporting the wrong position's confidence would be worse than reporting none, and
quietly skipping those tokens would make the perplexity an average over an unnamed subset.
The engine takes the single-token path while this is on. Speculation is off by default, so
for now this costs nothing in practice.

**About four kilobytes per reply on disk**, for a three hundred token answer, and only for
someone who turned it on. Stored as two parallel arrays of tokens and log probabilities
rather than as the runs the screen draws: runs are merged at a threshold, and a threshold
stored into every row would make an old reply unreadable at a new one.

## The threshold, which is not yet measured

A token is marked when the model gave it less than **0.20**. That number is reasoned, not
measured: in ordinary prose the overwhelming majority of tokens sit above 0.9, so a line
this low should mark the genuine forks and not the grammar.

`ConfidenceProbe` is the measurement that settles it, and it has not been run: the cloud
device this project measures on had its reservation lapse, and the phone was at 14% and
unplugged. It asks three things.

- **Are the numbers real.** At most one, never negative, and a greedy answer to a settled
  question should come out near one. A wrong logits pointer looks like a uniform
  distribution over the vocabulary and would show here.
- **Where the mass is.** A histogram in ten buckets. The right threshold is a property of
  the shape, and if half of all tokens land under 0.3 then 0.20 marks the grammar.
- **Whether it separates knowing from inventing.** Three questions the model knows against
  three about people and places that do not exist. The comparison worth making is the
  *lowest* token in each answer, not the mean: an invented specific is a token the model had
  to choose and a recalled one is not.

Until that runs, the constant is a guess with its reasoning attached, and it is a constant
rather than a slider precisely because a knob offered before the measurement would be a
number the user has no more basis to choose than the app does.

## What was deliberately not built

**Mirostat.** A sampler that steers generation toward a target perplexity, and the only
thing on a competing app's settings sheet that touches this subject. It is a way of
*controlling* perplexity rather than *reporting* it, it predates min-p by some years, and
adding it would put a second, differently-defined perplexity on the same screen.

**A per-conversation or per-model perplexity.** The average of an average, over answers to
different questions, is not a quantity. Perplexity is comparable between two answers to the
same prompt and not otherwise.
