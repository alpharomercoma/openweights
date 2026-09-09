"""Grade the retrieve-or-answer rows a phone wrote, and tabulate them per model and arm.

    python3 tools/eval/bench/grade_decisions.py tools/eval/results/decisions [--against driven-search]

Each row carries the set's own necessity label (`need`: true when the answer is not in the
weights, false when it is, null at the boundary) and the set's own answer aliases. What is
computed, in the field's terms:

- recall: on rows that need a search, the share where one ran (model-made or app-made).
- unnecessary: on rows that do not, the share where one ran anyway.
- correct: the set's own metric, alias containment after SQuAD normalisation, widened to
  a token-F1 of 0.5 against the best alias and narrowed by a length floor of fifteen
  characters, on the two reviewers' objection that bare containment credits a name
  quoted inside a wrong sentence. Rows whose answer may have moved since the set was
  built (`stale_risk`) are counted apart.
- ToolFailBench's four: skip (needed, none ran), unnecessary (not needed, one ran),
  fabricated (no search ran and the reply speaks of search results), result-ignore
  (a search ran, the answer is among the snippets, the reply is wrong).
- meta: the reply quotes the instructions instead of answering ("must be provided",
  "I already know", "the answer itself", ...).
- seconds, time to first token, passes, characters, prompt tokens: medians.

`--against` names the arm every other arm is paired with: on the rows both answered,
how many each got right that the other did not, which is the McNemar count and the
number that says whether a difference is more than noise on this many rows.

The echo files (`echo-*.jsonl`) are graded for the longest run of words a reply shares
with the shipped instructions, under the shipped instructions and under none.
"""
import argparse, collections, json, re, statistics, string, sys
from pathlib import Path

ARTICLES = re.compile(r"\b(a|an|the)\b")
SEARCH_TALK = re.compile(
    r"\b(search results?|web search|according to (the )?(search|sources?|results?)|"
    r"the sources? (say|indicate|show)|based on (my|the|a) search|i (searched|looked (it )?up))\b", re.I)
META = re.compile(
    r"(must be provided|i already know|the answer itself|answer from what (i|you) know|"
    r"at the length the question|reach for a tool|as an ai\b|do not search to double)", re.I)
CANNOT = re.compile(
    r"\b(i (do not|don't|cannot|can't) (know|have|find|access|provide)|no (information|data) (on|about)|"
    r"not (sure|aware|able to)|unable to)\b", re.I)


def normalize(text):
    text = text.lower()
    text = "".join(c for c in text if c not in string.punctuation)
    text = ARTICLES.sub(" ", text)
    return " ".join(text.split())


def token_f1(pred, gold):
    p, g = normalize(pred).split(), normalize(gold).split()
    if not p or not g:
        return 0.0
    common = collections.Counter(p) & collections.Counter(g)
    hits = sum(common.values())
    if hits == 0:
        return 0.0
    precision, recall = hits / len(p), hits / len(g)
    return 2 * precision * recall / (precision + recall)


def correct(answer, aliases):
    if len(answer.strip()) < 15 and not any(normalize(answer) == normalize(a) for a in aliases):
        return False
    text = normalize(answer)
    for a in aliases:
        na = normalize(a)
        if na and na in text:
            return True
    # A short answer that is the alias with a word changed, judged as the sets' own
    # readers do by token overlap; only for replies short enough for F1 to mean anything.
    if len(text.split()) <= 12:
        return any(token_f1(answer, a) >= 0.5 for a in aliases)
    return False


def searched(row):
    return any(c["name"] == "web_search" for c in row["calls"])


def grade_row(row):
    aliases = list(row["answers"])
    ran = searched(row)
    answer = row["answer"]
    ok = correct(answer, aliases) if aliases else None
    in_results = any(
        normalize(a) in normalize(c.get("result", "")) for a in aliases for c in row["calls"] if c["name"] == "web_search"
    ) if aliases else False
    need = row.get("need")
    return {
        "searched": ran,
        "model_called": any(not c.get("host") for c in row["calls"]),
        "correct": ok,
        "skip": need is True and not ran,
        "unnecessary": need is False and ran,
        "fabricated": (not ran) and bool(SEARCH_TALK.search(answer)),
        # Whether the search could have answered at all: the gold alias is in the
        # snippets. On the PopQA tail ("Who is the author of Empire?") it mostly is not,
        # because the title is shared by better-known works, and no loop can fix that.
        "findable": ran and in_results,
        "result_ignored": ran and in_results and ok is False,
        "meta": bool(META.search(answer)),
        "abstained": bool(CANNOT.search(answer)) and not ok,
        "passes": len(row["passes"]),
        "ttft": row["passes"][0]["ttft_ms"] if row["passes"] else None,
        "prompt_tokens": row["passes"][0]["prompt_tokens"] if row["passes"] else None,
        "error": row["raw"].startswith("ERROR"),
    }


def load(directory):
    runs = {}
    for path in sorted(Path(directory).glob("*decisions-*.jsonl")):
        header, rows = None, []
        for line in path.read_text().splitlines():
            if not line.strip():
                continue
            obj = json.loads(line)
            if obj.get("header"):
                header = obj
            else:
                rows.append(obj)
        if header is None or not rows:
            continue
        # A lab chunk rerun after a failed matrix appends its rows again; the last
        # answer to each id is the one kept.
        by_id = {}
        for r in rows:
            by_id[r["id"]] = r
        # The phone is the file's prefix (tensor-, exynos-, elite-, or none for the Poco),
        # and the header says which chip that was.
        header["phone"] = path.name.split("decisions-")[0].rstrip("-") or "poco"
        runs[(header["phone"], header["model"], header["arm"])] = (header, list(by_id.values()))
    return runs


def pct(n, d):
    return f"{100 * n / d:.0f}% ({n}/{d})" if d else "n/a"


def median(values):
    values = [v for v in values if v is not None]
    return f"{statistics.median(values):.1f}" if values else "n/a"


def summarize(header, rows):
    g = [grade_row(r) for r in rows]
    need = [(r, x) for r, x in zip(rows, g) if r.get("need") is True]
    no = [(r, x) for r, x in zip(rows, g) if r.get("need") is False]
    graded = [(r, x) for r, x in zip(rows, g) if x["correct"] is not None and not r.get("stale_risk")]
    stale = [(r, x) for r, x in zip(rows, g) if x["correct"] is not None and r.get("stale_risk")]
    return {
        "phone": header["phone"], "soc": header.get("soc", ""),
        "model": header["model"], "arm": header["arm"], "runtime": header["runtime"],
        "quant": header["quant"], "tools": len(header["tools"]), "n": len(rows),
        "recall": pct(sum(x["searched"] for _, x in need), len(need)),
        "model_recall": pct(sum(x["model_called"] for _, x in need), len(need)),
        "unnecessary": pct(sum(x["searched"] for _, x in no), len(no)),
        "correct": pct(sum(x["correct"] for _, x in graded), len(graded)),
        "correct_need": pct(sum(x["correct"] for r, x in graded if r.get("need") is True), sum(1 for r, _ in graded if r.get("need") is True)),
        "correct_known": pct(sum(x["correct"] for r, x in graded if r.get("need") is False), sum(1 for r, _ in graded if r.get("need") is False)),
        "correct_stale": pct(sum(x["correct"] for _, x in stale), len(stale)),
        "fabricated": pct(sum(x["fabricated"] for x in g), len(g)),
        "result_ignored": pct(sum(x["result_ignored"] for x in g), sum(x["searched"] for x in g)),
        "findable": pct(sum(x["findable"] for x in g), sum(x["searched"] for x in g)),
        "correct_findable": pct(sum(x["correct"] for x in g if x["findable"]), sum(x["findable"] for x in g)),
        "meta": pct(sum(x["meta"] for x in g), len(g)),
        "abstained": pct(sum(x["abstained"] for x in g), len(g)),
        "errors": sum(x["error"] for x in g),
        "s": median([r["ms"] / 1000 for r in rows]),
        "ttft_ms": median([x["ttft"] for x in g]),
        "prompt_tokens": median([x["prompt_tokens"] for x in g]),
        "passes": median([x["passes"] for x in g]),
        "chars": median([r["chars"] for r in rows]),
        "graded": {r["id"]: x for r, x in zip(rows, g)},
    }


COLUMNS = [
    ("phone", "Phone"), ("model", "Model"), ("arm", "Arm"), ("tools", "Tools"), ("n", "Rows"),
    ("recall", "Searched when needed"), ("model_recall", "Model called itself"),
    ("unnecessary", "Searched when not"), ("correct", "Correct"),
    ("correct_need", "Correct, needed"), ("correct_known", "Correct, known"),
    ("fabricated", "Fabricated search"), ("findable", "Answer in results"),
    ("correct_findable", "Correct when findable"), ("result_ignored", "Ignored result"),
    ("meta", "Quoted instructions"), ("abstained", "Abstained"),
    ("s", "Median s"), ("ttft_ms", "TTFT ms"), ("prompt_tokens", "Prompt tokens"),
    ("passes", "Passes"), ("chars", "Chars"),
]


def table(summaries):
    lines = ["| " + " | ".join(t for _, t in COLUMNS) + " |", "|" + "---|" * len(COLUMNS)]
    for s in summaries:
        lines.append("| " + " | ".join(str(s[k]) for k, _ in COLUMNS) + " |")
    return "\n".join(lines)


def strata(summaries_rows):
    """Per stratum, correct and searched, one line per model x arm."""
    out = ["", "| Phone | Model | Arm | Stratum | Rows | Searched | Correct |", "|---|---|---|---|---|---|---|"]
    for (phone, model, arm), (header, rows) in summaries_rows.items():
        by = collections.defaultdict(list)
        for r in rows:
            by[r["stratum"]].append(grade_row(r))
        for stratum, g in sorted(by.items()):
            graded = [x for x in g if x["correct"] is not None]
            out.append(f"| {phone} | {model} | {arm} | {stratum} | {len(g)} | {pct(sum(x['searched'] for x in g), len(g))} | "
                       f"{pct(sum(x['correct'] for x in graded), len(graded))} |")
    return "\n".join(out)


def paired(summaries, against):
    out = ["", f"Paired against `{against}` on the rows both answered: right where the other was wrong.", "",
           "| Phone | Model | Arm | Both right | Only this arm | Only the other | Both wrong |", "|---|---|---|---|---|---|---|"]
    by_model = collections.defaultdict(dict)
    for s in summaries:
        by_model[(s["phone"], s["model"])][s["arm"]] = s["graded"]
    for (phone, model), arms in by_model.items():
        base = arms.get(against)
        if not base:
            continue
        for arm, graded in arms.items():
            if arm == against:
                continue
            ids = [i for i in graded if i in base and graded[i]["correct"] is not None and base[i]["correct"] is not None]
            a = sum(1 for i in ids if graded[i]["correct"] and base[i]["correct"])
            b = sum(1 for i in ids if graded[i]["correct"] and not base[i]["correct"])
            c = sum(1 for i in ids if not graded[i]["correct"] and base[i]["correct"])
            d = len(ids) - a - b - c
            out.append(f"| {phone} | {model} | {arm} | {a} | {b} | {c} | {d} |")
    return "\n".join(out)


def echo(directory):
    paths = sorted(Path(directory).glob("echo-*.jsonl"))
    if not paths:
        return ""
    out = ["", "Instruction echo: the longest run of words a recitation shares with the shipped instructions.", "",
           "| Model | Prompt | Instructions | Tools | Calls | Chars | Longest shared run | Quotes instructions |", "|---|---|---|---|---|---|---|---|"]
    for path in paths:
        rows = [json.loads(l) for l in path.read_text().splitlines() if l.strip()]
        system = None
        header = Path(directory).glob("*decisions-*.jsonl")
        for h in header:
            first = h.read_text().splitlines()[0]
            obj = json.loads(first)
            if obj.get("header") and obj["model"] == rows[0]["model"]:
                system = obj["system"]
                break
        for r in rows:
            run = longest_shared_run(r["answer"], system) if system else "n/a"
            out.append(f"| {r['model']} | {r['id']} | {r['instructions']} | {r.get('tools', False)} | {len(r.get('calls', []))} | "
                       f"{r['chars']} | {run} | {bool(META.search(r['answer']))} |")
    return "\n".join(out)


def longest_shared_run(reply, system):
    a, b = normalize(reply).split(), normalize(system).split()
    best = 0
    positions = collections.defaultdict(list)
    for j, w in enumerate(b):
        positions[w].append(j)
    for i, w in enumerate(a):
        for j in positions.get(w, ()):
            k = 0
            while i + k < len(a) and j + k < len(b) and a[i + k] == b[j + k]:
                k += 1
            best = max(best, k)
    return best


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("directory")
    ap.add_argument("--against", default="driven-search")
    args = ap.parse_args()
    runs = load(args.directory)
    if not runs:
        sys.exit(f"no decisions-*.jsonl under {args.directory}")
    summaries = [summarize(h, r) for h, r in runs.values()]
    print(table(summaries))
    print(strata(runs))
    print(paired(summaries, args.against))
    print(echo(args.directory))
    (Path(args.directory) / "summary.json").write_text(json.dumps(
        [{k: v for k, v in s.items() if k != "graded"} for s in summaries], indent=1))


if __name__ == "__main__":
    main()
