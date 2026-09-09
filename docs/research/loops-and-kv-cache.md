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
| 2. Verification | Research steps are graded on device: a source counts only when a fetch actually reached it (`correlatedWebResearchSources`), a step the model marked done without the evidence is refused and retried (`stepRefusal`), and a step the model forgot to tick is ticked by the host (`tickIfTheModelDidNot`) | Shipped for research; **absent for the canvas** |
| 3. Event-driven | Watches: a schedule fires, the model checks a page, a change-gated alert goes out. Until this week a watch under fifteen minutes slept on a clock that stops in deep sleep; it now wakes on an alarm | Shipped, measured 2026-09-09 |
| 4. Hill climbing | None on the phone, by the product's first rule: no telemetry, so no traces leave the device and nothing reads them in aggregate. The loop exists on the development side: `eval/*.py` over device logs, the adversarial review runners under `tools/review`, and the engineering log. The harness work of 2026-09-09 (AGENTS.md, the guard hook, lefthook) is one turn of that loop, done by hand | Development-side only, and manual |

**What applies.** One gap is real and cheap to state: the canvas has an agent loop and no
grader. The model writes a page, the WebView renders it, and the WebView's own errors go to
"a console nobody is reading" (the comment in `CanvasServer`). The docs agent in the post
grades every attempt on links that resolve and checks that pass; the equivalent here is to
hand `onReceivedError` and console errors back to the model as a step refusal, the way a
research step that reached no source is refused. The canvas end-to-end run on the phone
found four bugs that no host test had, which is the evidence that pages break in ways the
model does not see. Not built yet: the first measurement is how often a canvas page raises
an error at all, over the prompts in that run, and that number decides whether the grader
is worth its round trip on a phone where every retry costs a prefill.

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
