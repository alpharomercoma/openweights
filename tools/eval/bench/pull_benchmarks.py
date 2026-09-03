"""Pull a fixed, seeded subset of three public benchmarks into benchmarks.json.

Nothing here is written by us. GSM8K (openai/gsm8k, test split) grades by the final
number; IFEval (google/IFEval) grades by the verifiable instructions its own checker
implements; BFCL v3 (gorilla-llm, the simple and multiple categories) grades a parsed
function call against the leaderboard's own possible answers. The subset is chosen with
one seed so every phone, family and engine answers literally the same prompts, and the
file is committed so a rerun a year on answers them too.

    python3 tools/eval/bench/pull_benchmarks.py [--per-set 30] [--seed 7]
"""
import argparse, json, random, subprocess, time, urllib.parse
from pathlib import Path

ROWS = "https://datasets-server.huggingface.co/rows"
BFCL = "https://huggingface.co/datasets/gorilla-llm/Berkeley-Function-Calling-Leaderboard/resolve/main/"
HERE = Path(__file__).resolve().parent

# The per-prompt token ceiling. GSM8K and IFEval answers run long, and the thinking
# families think first; BFCL is a call. reasoning_budget is the app's own knob that
# closes a thinking block so an answer still arrives inside max_tokens.
LIMITS = {"gsm8k": 640, "ifeval": 640, "bfcl": 384}
TYPES = {"dict": "object", "float": "number", "tuple": "array", "any": None}


def fetch(url, lines=False):
    # curl: this Python has no CA bundle, and the failure would look like empty data.
    for attempt in range(4):
        out = subprocess.run(["curl", "-sSL", "--max-time", "120", url],
                             capture_output=True, text=True)
        try:
            if lines:  # the BFCL files are JSON lines
                return [json.loads(l) for l in out.stdout.splitlines() if l.strip()]
            return json.loads(out.stdout)
        except Exception:
            time.sleep(3 * (attempt + 1))
    raise SystemExit(f"could not fetch {url}")


def rows(dataset, config, split):
    out, offset = [], 0
    while True:
        q = urllib.parse.urlencode({"dataset": dataset, "config": config, "split": split,
                                    "offset": offset, "length": 100})
        page = fetch(f"{ROWS}?{q}")
        out += [r["row"] for r in page.get("rows", [])]
        offset += 100
        if offset >= page.get("num_rows_total", 0):
            return out


def gsm8k(n, rng):
    picked = rng.sample(list(enumerate(rows("openai/gsm8k", "main", "test"))), n)
    return [{
        "id": f"gsm8k-{i}", "set": "gsm8k",
        "prompt": r["question"] + "\n\nSolve step by step, then give the final answer "
                  "on its own last line as: #### <number>",
        "max_tokens": LIMITS["gsm8k"],
        "reference": r["answer"].split("####")[-1].strip().replace(",", ""),
    } for i, r in sorted(picked)]


def ifeval(n, rng):
    picked = rng.sample(rows("google/IFEval", "default", "train"), n)
    out = []
    for r in sorted(picked, key=lambda r: r["key"]):
        # The rows API pads every kwargs struct with nulls; the checker wants them gone.
        kwargs = [{k: v for k, v in (kw or {}).items() if v is not None} for kw in r["kwargs"]]
        out.append({
            "id": f"ifeval-{r['key']}", "set": "ifeval", "prompt": r["prompt"],
            "max_tokens": LIMITS["ifeval"],
            "reference": {"instruction_id_list": r["instruction_id_list"], "kwargs": kwargs},
        })
    return out


def schema(params):
    props = {}
    for name, p in (params.get("properties") or {}).items():
        p = dict(p)
        t = p.get("type")
        if t in TYPES:
            if TYPES[t] is None:
                p.pop("type", None)
            else:
                p["type"] = TYPES[t]
        if "properties" in p:
            p.update(schema(p))
        if isinstance(p.get("items"), dict):
            items = dict(p["items"])
            if items.get("type") in TYPES:
                mapped = TYPES[items["type"]]
                if mapped is None:
                    items.pop("type", None)  # an unconstrained item stays unconstrained
                else:
                    items["type"] = mapped
            if "properties" in items:
                items.update(schema(items))
            p["items"] = items
        props[name] = p
    return {"type": "object", "properties": props, "required": params.get("required", [])}


def bfcl(category, n, rng):
    questions = {q["id"]: q for q in fetch(BFCL + f"BFCL_v3_{category}.json", lines=True)}
    answers = {a["id"]: a for a in fetch(BFCL + f"possible_answer/BFCL_v3_{category}.json", lines=True)}
    picked = rng.sample(sorted(questions), n)
    out = []
    for qid in sorted(picked, key=lambda s: int(s.rsplit("_", 1)[1])):
        q = questions[qid]
        turns = q["question"][0]
        out.append({
            "id": f"bfcl-{qid}", "set": "bfcl",
            "prompt": "\n\n".join(t["content"] for t in turns if t["role"] == "user"),
            "max_tokens": LIMITS["bfcl"],
            "tools": [{"name": f["name"], "description": f["description"],
                       "parameters": schema(f["parameters"])} for f in q["function"]],
            "reference": answers[qid]["ground_truth"],
        })
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--per-set", type=int, default=30)
    ap.add_argument("--seed", type=int, default=7)
    a = ap.parse_args()
    rng = random.Random(a.seed)
    prompts = gsm8k(a.per_set, rng) + ifeval(a.per_set, rng)
    simple = a.per_set * 2 // 3
    prompts += bfcl("simple", simple, rng) + bfcl("multiple", a.per_set - simple, rng)
    doc = {"seed": a.seed, "per_set": a.per_set, "sources": {
        "gsm8k": "openai/gsm8k main/test", "ifeval": "google/IFEval default/train",
        "bfcl": "gorilla-llm/Berkeley-Function-Calling-Leaderboard v3 simple+multiple",
    }, "prompts": prompts}
    (HERE / "benchmarks.json").write_text(json.dumps(doc, indent=1, ensure_ascii=False))
    print(f"{len(prompts)} prompts -> {HERE / 'benchmarks.json'}")


if __name__ == "__main__":
    main()
