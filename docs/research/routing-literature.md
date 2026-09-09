# When should a small on-device model search: what the literature says

Read 2026-09-10, after a hard-coded "search the name first" route was shipped for one
question shape and rejected the same day (see `who-is-questions.md` and the correction at
its end). The question this note answers is what the field has established about a small
model deciding to use a tool, what benchmarks exist for that decision, and which of them
this app can run on a phone. Thirty-six sources; the ones that changed the plan are marked.

## The decision itself

- **Mallen et al. 2022, PopQA** ([arXiv 2212.10511](https://arxiv.org/abs/2212.10511)):
  parametric memory serves popular entities, retrieval serves the long tail; a popularity
  threshold per relation, tuned on a dev set, beats both always-retrieve and never-retrieve.
  The app's "who is X" note was built on this. **Applies:** PopQA carries `s_pop`, so the
  head/tail split is a column, not a judgement.
- **RetrievalQA, Zhang et al. 2024** ([arXiv 2402.16457](https://arxiv.org/abs/2402.16457),
  [repo](https://github.com/hyintell/RetrievalQA)): 2,785 short questions from PopQA,
  TriviaQA, RealTimeQA, FreshQA and ToolQA, each labelled `param_knowledge_answerable`
  (1,514 answerable from memory, 1,271 not), built to score adaptive-retrieval methods on
  the decision rather than on the retriever. MIT. **Applies, first choice:** it is the
  one public set whose label is exactly the app's question.
- **When2Call, NVIDIA 2025** ([arXiv 2504.18851](https://arxiv.org/abs/2504.18851),
  [dataset](https://huggingface.co/datasets/nvidia/When2Call)): 3,652 test cases built from
  BFCL live prompts, each with tools and a correct choice among tool call, direct answer,
  follow-up question, cannot answer; scored by log-probability over the four; macro F1 and
  a "tool hallucination" rate on zero-tool prompts. CC-BY-4.0. Llama 3.2 1B: F1 21.7,
  43% tool hallucination; Qwen2.5 0.5B: F1 32.0. **Applies:** tool-call versus
  cannot-answer versus ask is the app's own three-way decision.
- **When2Tool, Sun et al. 2026** ([arXiv 2605.09252](https://arxiv.org/abs/2605.09252),
  [dataset](https://huggingface.co/datasets/cesun/When2Tool)): 18 environments with a
  controlled boundary between tool-necessary and tool-unnecessary tasks; tool necessity is
  linearly decodable from hidden states (AUROC 0.89 to 0.96) before generation, and a probe
  plus prefill cuts unnecessary calls by 48% at 1.7% accuracy cost. **Applies as a
  finding:** the decision is in the model already; the harness's job is to read it, not
  overrule it.
- **To Call or Not to Call, 2026** ([arXiv 2605.00737](https://arxiv.org/abs/2605.00737),
  [dataset](https://huggingface.co/datasets/QinyuanWu/ToCall_or_NotToCall)): necessity,
  utility, affordability as the three axes of a tool decision; the descriptive side infers
  the model's self-perceived need from behaviour. **Applies:** the harness reports the
  three separately (did it need to, did calling help, what it cost).
- **Intrinsic over-calling bias, 2026** ([arXiv 2605.18882](https://arxiv.org/abs/2605.18882)):
  on When2Call, six models show high call accuracy and much lower no-call accuracy, an
  activation-independent offset toward calling. **Applies as a caution:** the bias measured
  in the field is over-calling; what this app measured on LFM2.5 1.2B is the opposite, and
  a suite must count both.
- **SMART, 2025** ([arXiv 2502.11435](https://arxiv.org/abs/2502.11435)): tool overuse on
  questions the model could answer; a 7B trained on rationales for when tools are needed
  cut tool use 24% and improved accuracy 37%.
- **AbstentionBench, 2025** ([arXiv 2506.09038](https://arxiv.org/abs/2506.09038)):
  abstention is unsolved and does not scale; reasoning makes it worse. **Applies:** the
  routing suite's "cannot answer" rows are the abstention measurement.
- **Search, Do not Guess, 2026** ([arXiv 2604.04651](https://arxiv.org/html/2604.04651)):
  for small models the best policy is to always search: Qwen3-1.7B from 42.3 to 57.6 F1 on
  HotpotQA under an always-search policy, and letting the same model self-answer its top
  5% most confident questions costs 4.8 F1 where a 32B loses none. About 2.5 searches a
  question, 3.1 s on their hardware. **The counter-argument to a model-driven loop, in
  print.** It is measured on multi-hop QA sets where every question needs retrieval; it
  says nothing about the cost on a chat where most turns do not.
- **Adaptive-RAG, FLARE, Self-RAG, DRAGIN, SeaKR** ([survey](https://arxiv.org/pdf/2312.10997),
  [SeaKR](https://arxiv.org/html/2406.19215), [Adaptive-RAG](https://arxiv.org/html/2403.14403)):
  retrieval triggered by a classifier, by low-probability tokens, by trained reflection
  tokens, or by self-aware uncertainty. All need either training or extra passes.
  **Applies:** the app's loop already has the extra-pass shape (repairs); the classifier
  shape is what `NamedSubject` is, and the literature's version is trained, not written.
- **Slim proxy models, 2024** ([arXiv 2402.12052](https://arxiv.org/pdf/2402.12052)) and
  **Decide Then Retrieve, 2026** ([arXiv 2601.03908](https://arxiv.org/html/2601.03908)):
  a small model's own draft answer, scored, decides whether the big model needs retrieval.
  On a phone the small model is the model.

## Tool calling in small models

- **BFCL v4** ([leaderboard](https://gorilla.cs.berkeley.edu/leaderboard.html),
  [web search category](https://gorilla.cs.berkeley.edu/blogs/15_bfcl_v4_web_search.html),
  [format sensitivity](https://gorilla.cs.berkeley.edu/blogs/17_bfcl_v4_prompt_variation.html)):
  the app already runs BFCL simple and multiple (`docs/research/public-benchmarks.md`).
  v4 adds 100 multi-hop web-search tasks with `duckduckgo_search` and `fetch_url_content`
  and injected request failures; the reported failure modes are avoiding tool use,
  assuming future events, poor keywords, misreading pages. Format sensitivity: 26 prompt
  variations, for prompt-mode models. **Applies:** the web-search category is the shape of
  this app's research loop and is runnable with the app's own two tools.
- **TinyLLM, 2025** ([arXiv 2511.22138](https://arxiv.org/html/2511.22138v1)): BFCL on edge
  devices; xLAM-2-3b 65.7 overall, Qwen3-4B 62.0, Qwen3-1.7B 55.5 (live 58 to 63), models
  near 1B around 20 to 39 with no multi-turn success.
- **Hammer, 2024** ([arXiv 2410.04587](https://arxiv.org/abs/2410.04587)) and
  **HammerBench** ([arXiv 2412.16516](https://arxiv.org/abs/2412.16516)): on-device
  function calling is misled by names; function masking and irrelevance augmentation fix
  it; HammerBench scores multi-turn mobile-assistant dialogues per snapshot. **Matches**
  this codebase's 2026-08-29 finding that the tool name dominates LFM2.5's routing.
- **BiasBusters, 2025** ([arXiv 2510.00307](https://arxiv.org/html/2510.00307)) and
  **ToolTweak**: tool selection follows names, descriptions and order, not fitness.
- **Tool count**: accuracy degrades measurably past 10 to 15 tools and collapses past 50
  ([survey of production reports](https://tianpan.co/blog/2026-04-09-tool-selection-problem-agent-tool-routing-at-scale);
  BFCL calendar tasks 43% to 2% from 4 to 51 tools). **Applies to the open hypothesis:**
  the app went from sixteen tools on to one on 2026-09-08; whether that moved the call
  rate on this model is unmeasured, and the direction the literature predicts is the
  opposite of the hypothesis (fewer tools, better selection).
- **Liquid AI, LFM2.5** ([1.2B card](https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct),
  [Thinking](https://www.liquid.ai/blog/lfm2-5-1-2b-thinking-on-device-reasoning-under-1gb)):
  BFCLv3 49 for the 1.2B Instruct, 57 for the Thinking variant; a custom handler for its
  template. The 230M scores 43 against Gemma 3 1B's 16.6.
- **llama.cpp tool calling** ([docs](https://github.com/ggml-org/llama.cpp/blob/master/docs/function-calling.md),
  [issues](https://github.com/ggml-org/llama.cpp/issues/22581)): recurring reports of small
  models emitting calls as plain text or inside reasoning; KV-cache quantisation at q4
  measurably degrades tool calling ([benchmark](https://inventivehq.com/blog/kv-cache-quantization-quality-benchmark),
  [TriAxialKV](https://arxiv.org/pdf/2605.17170)), and small models are the most sensitive.
  **Applies:** the app's KV knob is off by default; keep it off for any routing measurement.
- **Efficient On-Device Agents, 2025** ([arXiv 2511.03728](https://arxiv.org/pdf/2511.03728)):
  selective inclusion of tool schemas on device, scored by tool-call precision and recall,
  latency and peak memory. The metrics the harness reports.

## Hallucinated tool use

- **ToolBeHonest, EMNLP 2024** ([arXiv 2406.20015](https://arxiv.org/abs/2406.20015)):
  solvability detection is where models fail; 700 samples over missing, potential and
  limited tools.
- **ToolFailBench, 2026** ([arXiv 2607.04686](https://arxiv.org/abs/2607.04686)): the
  taxonomy this harness adopts: Tool-Skip (should have called, did not), Result-Ignore
  (called, ignored the return), Output-Fabrication (called, invented what the return did
  not say), Unnecessary-Tool-Use. Best of nineteen models: 86.3% clean.
- **AgentHallu, 2026** ([arXiv 2601.06818](https://arxiv.org/html/2601.06818v1)):
  attribution of a hallucination to a step. **Seen here:** "Based on a web search, Killua
  is from Attack on Titan" with no search made is a fabricated tool step, and the loop
  should count it.

## Factuality and freshness sets

- **SimpleQA, OpenAI 2024** ([paper](https://arxiv.org/abs/2411.04368), [CSV](https://openaipublic.blob.core.windows.net/simple-evals/simple_qa_test_set.csv)):
  4,326 short questions with one indisputable, time-stable answer, graded correct,
  incorrect, not attempted. MIT. **Applies:** the "answer from memory or say you cannot"
  half of the decision, with a not-attempted rate.
- **FreshQA, Google** ([paper](https://arxiv.org/abs/2310.03214), [sheet, updated 2026-04-21](https://github.com/freshllms/freshqa)):
  600 questions typed never, slow, fast-changing and false-premise, with effective year and
  hop count. Apache-2.0. **Applies:** the fast-changing rows are the "must search" half,
  and the never-changing rows the control; the false-premise rows test the credulity guard.
- **RealTimeQA / TimeQA / UnSeenTimeQA** ([UnSeenTimeQA](https://arxiv.org/pdf/2407.03525),
  [TimE](https://arxiv.org/html/2505.12891v2)): RealTimeQA's weekly updates have stopped;
  TimeQA is contaminated. RetrievalQA already carries the usable RealTimeQA rows.
- **HalluLens** ([arXiv 2504.17550](https://arxiv.org/pdf/2504.17550)): extrinsic versus
  intrinsic hallucination; the taxonomy behind SimpleQA-style grading.

## Agentic search

- **Search-R1 and successors** ([arXiv 2503.09516](https://arxiv.org/abs/2503.09516),
  [R-Search](https://arxiv.org/pdf/2506.04185), [AutoSearch](https://www.alphaxiv.org/abs/2604.17337v1)):
  RL teaches a 3B to 7B model when and what to search; not applicable without training,
  but their evaluation sets (HotpotQA, 2Wiki, Bamboogle, MuSiQue) are the multi-hop
  standard the app's research loop should be scored on next.
- **MCP-Universe, MCP-Bench, LiveMCPBench** ([MCP-Universe](https://arxiv.org/abs/2508.14704),
  [MCP-Bench](https://huggingface.co/papers/2508.20453), [LiveMCPBench](https://arxiv.org/pdf/2508.01780)):
  large tool ecosystems; execution-based scoring beats judge scoring for reproducibility.
  Out of reach on a phone with eighteen tools, noted for the grading lesson.

## Length and completeness

- **Conciseness instructions** ([sufficiency-conciseness trade-off](https://arxiv.org/html/2602.14002v1),
  [concise CoT](https://arxiv.org/html/2401.05618v3)): a "be concise" reminder improves
  length control and trims supporting detail, with the loss larger for small models.
  **Seen here the same day:** a result framing that asked for "concise prose" cut LFM2.5's
  answers to one sentence. The harness records reply length and a completeness grade
  rather than trusting a wording.

## What this settles for the harness

1. The decision is measured on public sets with labels, not on questions written here:
   RetrievalQA for retrieve-or-not, When2Call for call-or-answer-or-ask-or-cannot, SimpleQA
   for answer-or-abstain, FreshQA for freshness and false premises, BFCL (already run) for
   call syntax and irrelevance. Seeded samples, committed, so every phone runs the same rows.
2. The metrics are the field's: call precision and recall against the necessity label,
   accuracy with and without the call, the ToolFailBench four (skip, ignore, fabricate,
   unnecessary), not-attempted rate, latency per turn, tokens per turn.
3. Arms are what the app can actually vary: model and quantisation, runtime, the tool set
   offered (search only, as a fresh install has it; every tool on, as the earlier reports
   ran), and nothing hard-coded on the question.
4. The literature's own answer to "let the model decide or search always" is split by
   model size and by what the questions are: always-search wins on multi-hop QA for models
   under 4B, and over-calling is the measured bias on mixed prompts. The app's loop is a
   mixed-prompt setting. So the arms include the model-driven loop as it shipped before
   today, and the numbers decide.
