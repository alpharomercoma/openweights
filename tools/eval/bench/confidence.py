"""Does a model's own token confidence predict a wrong bare answer? The offline half of
`TurnRunner.honourDoubt`, run against a local llama-server so it costs no phone time.

    llama-server -m model.gguf --port 8092 -c 4096 -ngl 99
    python3 tools/eval/bench/confidence.py collect 8092 <tag>          # 160 decision rows, greedy, logprobs
    python3 tools/eval/bench/confidence.py distractors 8092 <tag>      # 48 asks that need no search
    python3 tools/eval/bench/confidence.py report <tag> [<tag> ...]    # AUROC, cutoffs, false fires

Rows go to tools/eval/results/confidence/<tag>-bare.jsonl and <tag>-distractors.jsonl. The
system prompt and date exchange are the harness's own, read from a committed bare-arm file,
so the workstation asks exactly what the phone asked. Same grader as grade_decisions.py.
"""
import json, math, sys, time, urllib.request, collections
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = HERE.parent / "results" / "confidence"
DECISIONS = HERE / "decisions.json"
HEADER_FROM = HERE.parent / "results" / "decisions" / "decisions-LFM2.5-1.2B-Instruct-Q4_K_M-bare.jsonl"
K = 20

DISTRACTORS = {
    "writing": ["Write a haiku about autumn rain.", "Write a limerick about a cat who hates Mondays.", "Write a four-line poem about the sea at night.", "Write a short toast for a friend's birthday.",
                "Continue this story in two sentences: The door creaked open and", "Write a two-sentence horror story.", "Give me three name ideas for a small coffee shop.", "Write a tagline for a bicycle repair shop.",
                "Write a short motivational message for a friend running a marathon.", "Describe the taste of a lemon to someone who has never had one.", "Write a thank-you note to a teacher.", "Invent a name and one-line description for a fantasy tavern."],
    "code": ["Write a Python function that reverses a string.", "Write a regular expression that matches an email address.", "Write a Kotlin data class for a book with a title and an author.", "Explain what a for loop does in one paragraph.",
             "Write a SQL query that counts rows in a table called orders.", "Convert this JSON to YAML: {\"name\": \"x\", \"n\": 2}", "Write a bash one-liner that counts lines in all .txt files.", "What does the git command 'git stash' do?",
             "Write a JavaScript function that adds two numbers.", "Explain recursion to a ten year old.", "Fix the bug: for i in range(10) print(i)", "Write a CSS rule that centres a div horizontally."],
    "instruction": ["Set a timer for ten minutes.", "Calculate 45 times 12.", "Remind me to call the dentist tomorrow at nine.", "Turn this list into a checklist: milk, eggs, bread.",
                    "What is 15% of 80?", "Convert 5 miles to kilometres.", "Sort these words alphabetically: pear, apple, fig.", "Count the words in this sentence: the quick brown fox jumps.",
                    "Add these numbers: 12, 7, 31.", "How many days are there in a leap year?", "Spell 'necessary' backwards.", "Make this uppercase: hello world."],
    "text": ["Summarise the plot of Romeo and Juliet in two sentences.", "Rewrite this sentence more formally: gonna grab food, brb.", "Translate 'good morning, how are you?' into Spanish.", "Thanks, that was helpful!",
             "Tell me a joke about programmers.", "Proofread: Their going too the park tomorow.", "Shorten this to five words: I would really like to go to the beach this weekend if the weather is nice.", "Give one synonym for 'happy'.",
             "Draft a polite two-sentence email declining a meeting invitation.", "Explain the difference between 'affect' and 'effect'.", "Hello!", "What should I cook tonight with eggs and spinach?"],
}


def header():
    return json.loads(HEADER_FROM.open().readline())


def ask(port, messages, max_tokens):
    body = {"messages": messages, "temperature": 0, "top_k": 1, "seed": 1, "max_tokens": max_tokens,
            "logprobs": True, "top_logprobs": 2, "chat_template_kwargs": {"enable_thinking": False}}
    req = urllib.request.Request(f"http://localhost:{port}/v1/chat/completions", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as resp:
        res = json.load(resp)
    ch = res["choices"][0]
    toks = [{"t": x["token"], "lp": x["logprob"], "top": [(y["token"], y["logprob"]) for y in x.get("top_logprobs", [])]}
            for x in (ch.get("logprobs", {}).get("content", []) or [])]
    return ch["message"]["content"], toks


def collect(port, tag):
    hdr = header(); rows = json.load(DECISIONS.open())["rows"]
    out = OUT / f"{tag}-bare.jsonl"; OUT.mkdir(parents=True, exist_ok=True)
    done = {json.loads(l)["id"] for l in out.open()} if out.exists() else set()
    with out.open("a") as f:
        for r in rows:
            if r["id"] in done:
                continue
            d = hdr["date_exchange"]
            msgs = [{"role": "system", "content": hdr["system"]}, {"role": "user", "content": d[0]}, {"role": "assistant", "content": d[1]},
                    {"role": "user", "content": d[2]}, {"role": "assistant", "content": d[3]}, {"role": "user", "content": r["question"]}]
            t0 = time.time(); text, toks = ask(port, msgs, 200)
            f.write(json.dumps({"id": r["id"], "need": r["need"], "stratum": r["stratum"], "answers": r["answers"], "answer": text,
                                "tokens": toks, "ms": int((time.time() - t0) * 1000)}) + "\n"); f.flush()


def distractors(port, tag):
    hdr = header(); out = OUT / f"{tag}-distractors.jsonl"; OUT.mkdir(parents=True, exist_ok=True)
    with out.open("w") as f:
        for kind, qs in DISTRACTORS.items():
            for q in qs:
                text, toks = ask(port, [{"role": "system", "content": hdr["system"]}, {"role": "user", "content": q}], 120)
                lps = sorted(t["lp"] for t in toks[:K])
                f.write(json.dumps({"kind": kind, "q": q, "answer": text[:100], "min_prob": math.exp(lps[0]) if lps else 1.0,
                                    "min3_prob": math.exp(sum(lps[:3]) / len(lps[:3])) if lps else 1.0, "chars": len(text)}) + "\n")


def auroc(scores, labels):
    pairs = sorted(zip(scores, labels)); n1 = sum(labels); n0 = len(labels) - n1
    if not n1 or not n0:
        return float("nan")
    rank_sum = 0.0; i = 0
    while i < len(pairs):
        j = i
        while j < len(pairs) and pairs[j][0] == pairs[i][0]:
            j += 1
        avg = (i + j + 1) / 2
        rank_sum += sum(avg for k in range(i, j) if pairs[k][1]); i = j
    return (rank_sum - n1 * (n1 + 1) / 2) / (n1 * n0)


def features(toks):
    lps = sorted(t["lp"] for t in toks[:K]) or [0.0]
    margins = []
    for t in toks[:K]:
        top = sorted((lp for _, lp in t["top"]), reverse=True)
        if len(top) >= 2:
            margins.append(math.exp(top[0]) - math.exp(top[1]))
    return {"min_prob": math.exp(lps[0]), "min3_prob": math.exp(sum(lps[:3]) / len(lps[:3])),
            "mean_lp": sum(lps) / len(lps), "min_margin": min(margins) if margins else 1.0}


def report(tags):
    sys.path.insert(0, str(HERE)); from grade_decisions import correct  # noqa: E402
    data = {}
    for tag in tags:
        rows = [json.loads(l) for l in (OUT / f"{tag}-bare.jsonl").open()]
        for r in rows:
            r["ok"] = correct(r["answer"], r["answers"]); r["f"] = features(r["tokens"])
        data[tag] = rows
    print("| Feature | " + " | ".join(f"{t} AUROC (wrong answer)" for t in tags) + " |")
    print("|---|" + "---|" * len(tags))
    for f in ("min_prob", "min3_prob", "mean_lp", "min_margin"):
        print(f"| {f} | " + " | ".join(f"{auroc([-r['f'][f] for r in rows], [0 if r['ok'] else 1 for r in rows]):.2f}" for rows in data.values()) + " |")
    for f in ("min_prob", "min3_prob"):
        print(f"\n{f}, fire below the cutoff: sent / wrong / right")
        print("| Cutoff | " + " | ".join(tags) + " |"); print("|---|" + "---|" * len(tags))
        for c in (0.1, 0.2, 0.3, 0.4, 0.5):
            cells = []
            for rows in data.values():
                sent = [r for r in rows if r["f"][f] < c]
                cells.append(f"{len(sent)} / {sum(1 for r in sent if not r['ok'])} / {sum(1 for r in sent if r['ok'])}")
            print(f"| {c} | " + " | ".join(cells) + " |")
    for tag in tags:
        p = OUT / f"{tag}-distractors.jsonl"
        if not p.exists():
            continue
        rows = [json.loads(l) for l in p.open()]; by = collections.defaultdict(list)
        for r in rows:
            by[r["kind"]].append(r)
        print(f"\n{tag}, asks that need no search: fires for min_prob < 0.2 / min3_prob < 0.25 / min3_prob < 0.3")
        for kind, rs in by.items():
            print(f"  {kind}: {sum(1 for r in rs if r['min_prob'] < 0.2)} / {sum(1 for r in rs if r.get('min3_prob', 1) < 0.25)} / {sum(1 for r in rs if r.get('min3_prob', 1) < 0.3)} of {len(rs)}")
        print(f"  all: {sum(1 for r in rows if r['min_prob'] < 0.2)} / {sum(1 for r in rows if r.get('min3_prob', 1) < 0.25)} / {sum(1 for r in rows if r.get('min3_prob', 1) < 0.3)} of {len(rows)}")


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "collect":
        collect(sys.argv[2], sys.argv[3])
    elif cmd == "distractors":
        distractors(sys.argv[2], sys.argv[3])
    elif cmd == "report":
        report(sys.argv[2:])
