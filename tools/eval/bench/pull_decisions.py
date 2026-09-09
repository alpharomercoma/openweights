"""Pull a fixed, seeded sample of three public retrieve-or-answer sets into decisions.json.

Nothing here is written by us, and none of it is a question anybody on this project
asked the app. The sets are the ones the literature uses for the decision this app's
loop makes on every turn with web search on: answer from the weights, or look it up.

RetrievalQA (Zhang et al. 2024, MIT): short questions from PopQA, TriviaQA, RealTimeQA,
FreshQA and ToolQA, each labelled whether parametric knowledge can answer it. The label
is exactly the app's question, so this set is the primary endpoint.

PopQA (Mallen et al. 2022, MIT): entity questions with the subject's Wikipedia
popularity, which the paper shows is the boundary where memory stops working and
retrieval starts. Sampled by popularity quartile so the tail and the head are both in.

FreshQA (Vu et al. 2023, Apache-2.0): questions typed by how fast their answer changes,
plus false-premise questions. Fast-changing rows are the must-search half, never-changing
rows the control, and the false premises test whether a search stops the model swallowing
one.

When2Call and SimpleQA were read and left out on two reviewers' advice: When2Call carries
its own function catalogues (coffee-shop APIs), so under the app's search-shaped
instructions it would measure prompt mismatch; SimpleQA is built so frontier models score
about 40% and a 1B model does not abstain in a way the set can grade.

The sample is drawn with one seed so every phone, runtime and quant answers literally the
same rows, and the file is committed so a rerun a year on answers them too. A local copy of
each source may be given with --cache so a rerun does not need the network.

    python3 tools/eval/bench/pull_decisions.py [--seed 7] [--cache DIR]
"""
import argparse, csv, io, json, random, subprocess, time
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
SOURCES = {
    "retrievalqa": "https://raw.githubusercontent.com/hyintell/RetrievalQA/main/data/retrievalqa.jsonl",
    "popqa": "https://huggingface.co/datasets/akariasai/PopQA/resolve/main/test.tsv",
    "freshqa": "https://docs.google.com/spreadsheets/d/1_8mi-yuK30mvoDJu1KQXD6ODem7MKMcIgVAwDSzJkjM/export?format=csv",
}
FILES = {"retrievalqa": "retrievalqa.jsonl", "popqa": "popqa.csv", "freshqa": "freshqa.csv"}

# How many rows of each kind. RetrievalQA is the primary endpoint and gets the most; the
# other two are diagnostic strata. Eighty balanced rows is the size at which a twenty
# point difference between two arms is readable in a paired comparison; the strata are
# read as directions, not as separately powered tests.
RETRIEVALQA = {  # (source, answerable) -> n
    ("popqa", 1): 25, ("triviaqa", 1): 15,
    ("popqa", 0): 14, ("triviaqa", 0): 8, ("realtimeqa", 0): 8, ("freshqa", 0): 5, ("toolqa", 0): 5,
}
POPQA_PER_QUARTILE = 10
FRESHQA = {"fast-changing": 15, "slow-changing": 10, "never-changing": 10}
FRESHQA_FALSE_PREMISE = 5


def fetch(name, cache):
    if cache:
        local = Path(cache) / FILES[name]
        if local.is_file():
            return local.read_text()
    # curl: this Python has no CA bundle, and the failure would look like empty data.
    for attempt in range(4):
        out = subprocess.run(["curl", "-sSL", "--max-time", "300", SOURCES[name]],
                             capture_output=True, text=True)
        if out.returncode == 0 and out.stdout.strip():
            if cache:
                Path(cache).mkdir(parents=True, exist_ok=True)
                (Path(cache) / FILES[name]).write_text(out.stdout)
            return out.stdout
        time.sleep(3 * (attempt + 1))
    raise SystemExit(f"could not fetch {SOURCES[name]}")


def retrievalqa(text, rng):
    rows = [json.loads(l) for l in text.splitlines() if l.strip()]
    by = defaultdict(list)
    for r in rows:
        by[(r["data_source"], int(r["param_knowledge_answerable"]))].append(r)
    out = []
    for key, n in RETRIEVALQA.items():
        for r in sorted(rng.sample(by[key], n), key=lambda r: r["question_id"]):
            source, answerable = key
            out.append({
                "id": f"retrievalqa-{r['question_id']}", "set": "retrievalqa", "source": source,
                "question": r["question"].strip(),
                "answers": [a for a in r["ground_truth"] if a],
                # The set's own label: whether the weights can answer it.
                "need": not answerable,
                "stratum": f"{source}/{'answerable' if answerable else 'retrieve'}",
                # RealTimeQA, FreshQA and ToolQA rows carry an answer that was true when the
                # set was built (2023 to early 2024) and may have moved since; their
                # decision label stands, and their correctness is read with that in mind.
                "stale_risk": source in ("realtimeqa", "freshqa", "toolqa"),
            })
    return out


def popqa(text, rng, taken):
    rows = list(csv.DictReader(io.StringIO(text), delimiter="\t"))
    rows = [r for r in rows if r["question"].strip() not in taken]
    pops = sorted(int(r["s_pop"]) for r in rows)
    cuts = [pops[len(pops) * q // 4] for q in (1, 2, 3)]
    by = defaultdict(list)
    for r in rows:
        p = int(r["s_pop"])
        by[sum(p >= c for c in cuts)].append(r)
    out = []
    for quartile in range(4):
        for r in sorted(rng.sample(by[quartile], POPQA_PER_QUARTILE), key=lambda r: int(r["id"])):
            out.append({
                "id": f"popqa-{r['id']}", "set": "popqa", "source": "popqa",
                "question": r["question"].strip(),
                "answers": json.loads(r["possible_answers"]),
                # Mallen et al.: retrieval beats memory below a popularity threshold that,
                # for a model this size, sits high. The bottom half is the tail and must
                # be searched; the top quartile is the head and should not be; the third
                # quartile is the boundary and carries no decision label.
                "need": {0: True, 1: True, 2: None, 3: False}[quartile],
                "stratum": f"popqa/q{quartile + 1}",
                "s_pop": int(r["s_pop"]),
                "stale_risk": False,
            })
    return out, cuts


def freshqa(text, rng, taken):
    lines = text.splitlines()
    start = next(i for i, l in enumerate(lines) if l.startswith("id,split,question"))
    rows = list(csv.DictReader(io.StringIO("\n".join(lines[start:]))))
    rows = [r for r in rows if r["split"] == "TEST" and r["answer_0"].strip()
            and r["num_hops"] == "one-hop" and r["question"].strip() not in taken]
    plain = [r for r in rows if r["false_premise"] == "FALSE"]
    premise = [r for r in rows if r["false_premise"] == "TRUE"]
    out = []
    for kind, n in FRESHQA.items():
        pool = [r for r in plain if r["fact_type"] == kind]
        for r in sorted(rng.sample(pool, n), key=lambda r: int(r["id"])):
            out.append(row_freshqa(r, kind, need={"fast-changing": True, "slow-changing": None,
                                                  "never-changing": False}[kind]))
    for r in sorted(rng.sample(premise, FRESHQA_FALSE_PREMISE), key=lambda r: int(r["id"])):
        out.append(row_freshqa(r, "false-premise", need=None))
    return out


def row_freshqa(r, kind, need):
    answers = [r[f"answer_{i}"].strip() for i in range(10) if r.get(f"answer_{i}", "").strip()]
    return {
        "id": f"freshqa-{r['id']}", "set": "freshqa", "source": "freshqa",
        "question": r["question"].strip(), "answers": answers, "need": need,
        "stratum": f"freshqa/{kind}", "false_premise": r["false_premise"] == "TRUE",
        "effective_year": r["effective_year"],
        # A fast-changing answer is right as of the sheet's last review and may have
        # moved by the day the phone runs it.
        "stale_risk": kind in ("fast-changing", "slow-changing"),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--cache", default=None, help="directory holding local copies of the sources")
    args = ap.parse_args()
    rng = random.Random(args.seed)
    rows = retrievalqa(fetch("retrievalqa", args.cache), rng)
    taken = {r["question"] for r in rows}
    pop, cuts = popqa(fetch("popqa", args.cache), rng, taken)
    rows += pop
    taken |= {r["question"] for r in pop}
    rows += freshqa(fetch("freshqa", args.cache), rng, taken)
    out = {
        "seed": args.seed, "sources": SOURCES, "popqa_quartile_cuts": cuts,
        "counts": {s: sum(1 for r in rows if r["set"] == s) for s in ("retrievalqa", "popqa", "freshqa")},
        "rows": rows,
    }
    (HERE / "decisions.json").write_text(json.dumps(out, indent=1, ensure_ascii=False) + "\n")
    print(json.dumps(out["counts"]), "quartile cuts", cuts)


if __name__ == "__main__":
    main()
