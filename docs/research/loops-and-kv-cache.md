# Two readings against the code: the loop stack, and the KV cache handbook

Read 2026-09-09. Both were put beside what the app already does, and each was allowed one
question: what here is missing, and what here is wrong. One bug came out of it; the rest
is a map of where the app already stands, kept so nobody re-reads the same pieces looking
for something that is not there.

## 1. "The Art of Loop Engineering" (LangChain, Sydney Runkle, June 2026)

The post's claim is that an agent is a stack of four loops, and that the value moves
outward: the agent loop automates work, a verification loop grades it, an event-driven
loop runs it without being asked, and a hill-climbing loop reads the traces of all three
and rewrites the harness. The framing is LangChain's, the primitives are LangSmith's, and
the argument is general. Here is where each loop lives in OpenWeights, and what it lacks.

| Loop | Where it is in the app | State |
|---|---|---|
| 1. Agent | `AgentRunner`: the model calls tools until it stops; goals and deep research run it step by step over a plan | Shipped |
| 2. Verification | Research steps are graded on device: a source counts only when a fetch actually reached it (`correlatedWebResearchSources`), a step the model marked done without the evidence is refused and retried (`stepRefusal`), and a step the model forgot to tick is ticked by the host (`tickIfTheModelDidNot`) | Shipped for research; for the canvas since 2026-09-10, see the census below |
| 3. Event-driven | Watches: a schedule fires, the model checks a page, a change-gated alert goes out. Until this week a watch under fifteen minutes slept on a clock that stops in deep sleep; it now wakes on an alarm | Shipped, measured 2026-09-09 |
| 4. Hill climbing | None on the phone, by the product's first rule: no telemetry, so no traces leave the device and nothing reads them in aggregate. The loop exists on the development side: `eval/*.py` over device logs, the adversarial review runners under `tools/review`, and the engineering log. The harness work of 2026-09-09 (AGENTS.md, the guard hook, lefthook) is one turn of that loop, done by hand | Development-side only, and manual |

**What applies.** One gap is real and cheap to state: the canvas has an agent loop and no
grader. The model writes a page, the WebView renders it, and the WebView's own errors go to
"a console nobody is reading" (the comment in `CanvasServer`). The docs agent in the post
grades every attempt on links that resolve and checks that pass; the equivalent here is to
hand `onReceivedError` and console errors back to the model as a step refusal, the way a
research step that reached no source is refused. The canvas end-to-end run on the phone
found four bugs that no host test had, which is the evidence that pages break in ways the
model does not see. The measurement that decides whether that round trip is worth a
prefill on a phone is how often a page raises an error at all; the next section is that
census, and the grader it ended up shaping, which is not quite the one described here.

### The census, and the grader it argued for (2026-09-10)

`CanvasErrorCensus` (core/engine androidTest) put ten asks to two models on the POCO X8
Pro Max through the app's own tool loop, greedy, thinking off, 4k window, four rounds at
most, and loaded every page that came back into a real WebView with console errors,
failed resources and the element count recorded. Six of the ten asks want a script (to-do
list, tip calculator, countdown, quiz, converter, drawing pad, tabbed portfolio, palette
with copy-to-clipboard); four are static (moon phases, coffee shop landing page).

| Model | Built | Load-time script errors | Resources that failed | Pages with no script where one was asked | Cut off or stub |
|---|---|---|---|---|---|
| Qwen3 1.7B Q8_0 | 10 of 10 | 0 of 10 | 1 of 10 (a stock photo from a CDN) | 1 of 6 (the quiz is static HTML with the right answer in a class name) | 0 |
| LFM2.5 1.2B Q4_K_M | 6 of 10 | 0 of 6 | 0 of 6 | 4 of 5 | 3 of 6: the quiz stops at `<div id=` after 259 characters; the to-do list is a shell with "(full HTML code from above)" where the page should be; the converter is 332 characters |

Sizes: Qwen3's pages ran 1,102 to 2,465 characters, LFM2.5's 148 to 630. With thinking on,
Qwen3 spent twenty-seven minutes on the to-do page while the phone swapped 4.6 GB, which
is why the census runs with it off, as the public benchmarks did.

So the answer to "how often does a canvas page raise an error" is: at load, never, in
sixteen pages. What goes wrong is quieter than an exception. A page is cut off, or a stub,
or does not do the thing that was asked, or reaches for an image the app will not fetch.
A browser reports all of those as clean. The grader is built around what the census
actually found rather than what was expected:

- **Cut off.** A saved `.html` whose last four hundred characters hold neither `</body>`
  nor `</html>` is reported as "looks cut off, save the whole page". This comes from the
  save itself, not the browser, because HTML forgives a missing end and renders what is
  there. It catches one of LFM2.5's three broken pages; the stub and the tiny converter
  close their tags and pass, and no deterministic check knows a page is too small to be
  what was asked.
- **Blocked host.** A request the checker refuses, because nothing leaves the phone, is
  named by host with the rule stated, rather than as a missing file the model would go
  looking for.
- **Script errors and missing files** are still reported, by file and line, for the day a
  page does throw; the device test `CanvasGraderOnDeviceTest` proves the path through
  the real server, and found two things the census had not: every WebView load asks for
  `favicon.ico` on its own, so the 404 for it had every clean page graded as missing a
  file; and a missing stylesheet arrives twice, once as the failed request and once as
  the console's complaint that the 404 body is not CSS.

Cost: one hidden WebView load per save under the site on screen, about 1.3 s of which is
the settle time given to timers, on the main thread of a process that is otherwise
decoding. Sites only; a document or a deck goes through a viewer this project supplies.

What the census could not measure is the larger failure, the page that loads clean and
does not do what was asked, and the grader does not pretend to. The next step there is
not a better grader but a better builder: the two models on the shortlist built the
static asks and stalled on half the scripted ones, and that is a model question.

**What does not apply.** A hill-climbing loop over user traces is out by design, and the
post's extension of it into RL fine-tuning on trace outcomes is out with it. The post's
"human oversight at every level" is already the shape of `/yolo`, which keeps two
approvals, and of the guard hook on the development side. Nothing there to add.

## 2. "Understanding KV Cache" (@techNmak, handbook 02)

The file shared is six pages: orientation, why decoding is sequential, what a naive decoder
would repeat, what exactly is cached, and one cache per attention layer. The post
describing it lists memory formulas, MHA against GQA and MQA, MLA, PagedAttention, prefix
reuse, offloading, quantization and eviction; none of that is in the shared file, so this
note covers what was shared.

The handbook's one operative page is the shape of the cache: per layer, `K, V` each of
`B x H_kv x T x D_h`, where `D_h` is the head width, and the memory formula that follows,
`2 x layers x H_kv x D_h x T x bytes`. Put beside `GgufMetadata.kvCacheBytes`, which the
fit estimator and the default context window both rest on, the formula matched in every
factor but one: the app took `D_h` to be the embedding width divided by the head count. The
GGUF header states it directly as `<arch>.attention.key_length`, and for the Qwen3 family
the two disagree.

Read from the published headers on 2026-09-09:

| Model | Embedding / heads | Stated key length | KV bytes per token, derived | KV bytes per token, stated | Error |
|---|---|---|---|---|---|
| Qwen3 0.6B | 1024 / 16 = 64 | 128 | 57,344 | 114,688 | half |
| Qwen3 4B | 2560 / 32 = 80 | 128 | 92,160 | 147,456 | 0.63x |
| Qwen3 1.7B | 2048 / 16 = 128 | 128 | 114,688 | 114,688 | none |
| LFM2.5 1.2B | 2048 / 32 = 64 | not stated | 12,288 | 12,288 | none |

The LFM2.5 row is 6 attention blocks of 16 (its `head_count_kv` list is zero in the other
ten) times 8 heads, which is the hybrid saving the per-layer list was added for.

So the fit card and the automatic window were sized on half the cache for the 0.6B and
two thirds of it for the 4B, and the automatic window's memory share (a third of usable
memory over bytes per token) came out about twice as wide as the rule intends for the
0.6B. The two shipped Qwen3 exports on the shortlist are ExecuTorch files, whose window is
fixed at export, so nothing on the recommended list was affected; any Qwen3 GGUF chosen
from the Hub was. The parser now reads `key_length` and `value_length`, the head width
falls back to the derivation only where the header says nothing, and the cache formula
adds the two widths rather than doubling one. Two tests hold the Qwen3 shape and the
fallback.

Two things the handbook says that the code already agrees with, recorded so they are not
re-derived: the estimate assumes 16-bit elements, and so does the engine by default; the
eight-bit cache is a knob that is off, and when it is on the estimate overstates by about
1.9x, which is the safe direction and is left alone. And the handbook's closing warning,
that a KV cache is not the model's memory, is the line the app already draws: the warm
prefix is prefix caching, the memory feature is a store the prompt reads, and neither is
the other.
