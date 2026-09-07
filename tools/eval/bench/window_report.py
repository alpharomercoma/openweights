"""Render the exported-window matrix from graded benchmark reports.

    python3 tools/eval/bench/window_report.py tools/eval/results --sizes tools/eval/bench/window_sizes.json \
        --out docs/research/window-matrix.md

Rows are (model, exported window), columns are phones. Per cell: passed/answered on each
set, median time to first token, median time per output token, derived prefill and decode
rates, the window the engine ran with, and the process's resident memory. A second table
says, per model and phone, on how many prompts the reply text was byte-identical between
each window and the largest one: with greedy decoding and identical weights, a difference
is either a prompt that did not fit or a bug.

Only reports whose model name carries a `-<n>k` window tag are read; the publisher exports
of the earlier matrix have no tag and are left to report.py.
"""
import argparse, json, re, statistics
from collections import defaultdict
from pathlib import Path

DEVICES = [("qdc-", "8 Gen 3"), ("tensor-", "Tensor G5"), ("exynos-", "Exynos 2400"),
           ("elite-", "8 Elite"), ("repeat-", "D9400 repeat"), ("", "D9400")]
ORDER = ["D9400", "D9400 repeat", "8 Elite", "8 Elite repeat", "Tensor G5", "Tensor G5 repeat",
         "Exynos 2400", "Exynos 2400 repeat", "8 Gen 3"]
CAP = re.compile(r"-cap(\d+)")
SETS = [("gsm8k", "GSM8K"), ("ifeval", "IFEval"), ("bfcl", "BFCL")]
TAG = re.compile(r"-(\d+)k(?:\.|$|-)")


def device_of(name: str) -> str:
    # `repeat-<phone>-` is that phone's second run of the same file (the run-to-run control).
    if name.startswith("repeat-"):
        rest = name[len("repeat-"):]
        for prefix, label in DEVICES:
            if prefix and prefix != "repeat-" and rest.startswith(prefix):
                return label + " repeat"
        return "D9400 repeat"
    for prefix, label in DEVICES:
        if prefix and name.startswith(prefix):
            return label
    return "D9400"


def model_and_window(model: str):
    m = TAG.search(model + ".")
    if not m:
        return None, None
    window = int(m.group(1)) * 1024
    base = model[: m.start()]
    return base, window


def load(root: Path):
    cells = defaultdict(dict)   # (model, window, device) -> {id: case}
    meta = {}                    # (model, window, device) -> {context_size, rss_mb}
    for path in sorted(root.glob("*.bench*.graded.json")):
        r = json.loads(path.read_text())
        base, window = model_and_window(r["model"])
        if base is None:
            continue
        # A run at another reply cap is another condition: its own section, not a merge.
        cap = CAP.search(path.name)
        if cap:
            base += f" (cap {cap.group(1)})"
        key = (base, window, device_of(path.name))
        meta.setdefault(key, {"expected": 0, "budget_exhausted": False,
                              "cap": int(cap.group(1)) if cap else None})
        for k in ("context_size", "rss_mb", "rss_mb_after_load"):
            if r.get(k) and r.get(k) != -1:
                meta[key][k] = r[k]
        meta[key]["budget_exhausted"] |= bool(r.get("budget_exhausted"))
        # A report covers whole sets: 30 prompts each. Sum over the reports of a cell.
        # A `@n` file continues a time-boxed set from prompt n; it adds no new sets.
        if "@" not in path.name:
            meta[key]["expected"] += 30 * len(sets_covered(path.name))
        for c in r["cases"]:
            have = cells[key].get(c["id"])
            if have and have.get("grade") in ("pass", "fail") and c.get("grade") not in ("pass", "fail"):
                continue
            cells[key][c["id"]] = c
    return cells, meta


def calls_of(c):
    return json.dumps(c.get("calls") or [], sort_keys=True)


def identity(a, b, shared):
    """Raw token stream identical and shown content identical over the prompts both runs
    completed, and parsed tool calls identical over the BFCL prompts among them only (codex
    QA round two: counting the 60 prompts whose call list is empty either way inflated it)."""
    raw = sum(a[i].get("raw") == b[i].get("raw") for i in shared)
    bfcl = [i for i in shared if a[i].get("set") == "bfcl"]
    calls = sum(calls_of(a[i]) == calls_of(b[i]) for i in bfcl)
    content = sum(a[i].get("content") == b[i].get("content") for i in shared)
    return f"raw {raw}/{len(shared)}, calls {calls}/{len(bfcl)}, content {content}/{len(shared)}"


CAP_OF = {"gsm8k": 640, "ifeval": 640, "bfcl": 384}


def sets_covered(name):
    """The sets a report file was launched for, from its name: `.bench.json` is all three,
    `.bench-bfcl+gsm8k.json` two, `.bench-ifeval.json` one."""
    tag = name.split(".bench", 1)[1].replace(".graded", "").split(".json")[0].lstrip("-")
    tag = CAP.sub("", tag).split("@")[0]
    return tag.split("+") if tag else [s for s, _ in SETS]


def med(xs):
    xs = [x for x in xs if x is not None]
    return statistics.median(xs) if xs else None


def fmt(x, digits=0):
    if x is None:
        return "-"
    return f"{x:.{digits}f}"


def render(cells, meta, sizes) -> str:
    models = sorted({k[0] for k in cells})
    devices = [d for d in ORDER if any(k[2] == d for k in cells)]
    out = ["# Exported window matrix", "",
           "Each cell is one export of one model at one window on one phone, over the same 90 prompts "
           "(30 GSM8K, 30 IFEval, 30 BFCL), greedy, thinking off, loaded at the file's own window. "
           "Prefill ms is the runtime's prefill time for the whole prompt (the engine-side part of time to first token, "
           "not a first-token timestamp); ms/token is decode time over generated tokens, which differ per cell "
           "because the replies differ, so it is a per-cell figure and not a paired speed comparison. Capped is "
           "how many completed replies ran to the token cap (640, or 384 for BFCL; 2048 in the capped rerun) or to the edge of the window itself, and so never finished; a set "
           "whose replies are mostly capped is cap-censored and its grade says little. RSS is the test process\x27s "
           "resident set right after the model loaded and at the end of the run.", ""]
    for model in models:
        windows = sorted({k[1] for k in cells if k[0] == model})
        out += [f"## {model}", ""]
        head = "| Window | File | Phone | " + " | ".join(l for _, l in SETS) + \
               " | Capped | Prefill ms | ms/token | Prefill tok/s | Decode tok/s | Ran at | RSS after load MB | RSS at end MB |"
        out += [head, "|" + "---|" * (head.count("|") - 1)]
        for w in windows:
            for d in devices:
                cases = cells.get((model, w, d))
                if not cases:
                    continue
                ok = [c for c in cases.values() if c.get("status") == "ok"]
                m = meta.get((model, w, d), {})
                size = sizes.get(f"{model.split(' (')[0]}-{w // 1024}k")
                expected = m.get("expected", 90)
                if len(cases) < expected and not m.get("budget_exhausted"):
                    # The process ended before the set did, with no time box and no error
                    # recorded: no grade or timing is shown for such a cell (codex QA).
                    out.append(
                        f"| {w // 1024}k | {fmt(size / 1e6) + ' MB' if size else '-'} | {d} | "
                        f"INCOMPLETE: process ended after {len(cases)}/{expected} prompts | | | | | | | | "
                        f"{m.get('context_size', '-')} | {m.get('rss_mb_after_load', '-')} | {m.get('rss_mb', '-')} (last observed) |")
                    continue
                # A reply is cut either by its cap or by the window: prompt plus reply cannot
                # exceed the exported window, and at 2k a reasoning reply meets that edge first.
                cap_of = lambda c: m.get("cap") or CAP_OF.get(c["set"], 640)
                win = m.get("context_size") or w
                capped = sum(1 for c in ok if c.get("generated_tokens", 0) >= cap_of(c) - 1
                             or c.get("prompt_tokens", 0) + c.get("generated_tokens", 0) >= win - 1)
                scores = []
                for s, _ in SETS:
                    graded = [c for c in cases.values() if c["set"] == s and c.get("grade") in ("pass", "fail")]
                    skipped = [c for c in cases.values() if c["set"] == s and c.get("status") == "skipped"]
                    scores.append("skip" if skipped and not graded else
                                  f"{sum(c['grade'] == 'pass' for c in graded)}/{len(graded)}" if graded else "-")
                ttft = med([c["prefill_ms"] for c in ok])
                tpot = med([c["decode_ms"] / c["generated_tokens"] for c in ok if c.get("generated_tokens")])
                pre = med([c["prompt_tokens"] * 1000 / c["prefill_ms"] for c in ok if c.get("prefill_ms")])
                dec = med([c["generated_tokens"] * 1000 / c["decode_ms"] for c in ok if c.get("decode_ms")])
                out.append(
                    f"| {w // 1024}k | {fmt(size / 1e6) + ' MB' if size else '-'} | {d} | " + " | ".join(scores) +
                    f" | {capped}/{len(ok)} | {fmt(ttft)} | {fmt(tpot, 1)} | {fmt(pre)} | {fmt(dec, 1)} | "
                    f"{m.get('context_size', '-')} | {m.get('rss_mb_after_load', '-')} | {m.get('rss_mb', '-')} |")
        out.append("")
        # Byte-identical replies against the largest window, per phone.
        largest = windows[-1]
        rows = []
        for d in devices:
            ref = cells.get((model, largest, d))
            if not ref:
                continue
            parts = []
            for w in windows[:-1]:
                other = cells.get((model, w, d))
                if not other:
                    parts.append("-")
                    continue
                shared = [i for i in ref if i in other and ref[i].get("status") == "ok" and other[i].get("status") == "ok"]
                parts.append(identity(ref, other, shared))
            rows.append(f"| {d} | " + " | ".join(parts) + " |")
        control = []
        for w in windows:
            for d in devices:
                if d.endswith(" repeat"):
                    continue
                a = cells.get((model, w, d)); b = cells.get((model, w, d + " repeat"))
                if a and b:
                    shared = [i for i in a if i in b and a[i].get("status") == "ok" and b[i].get("status") == "ok"]
                    control.append(f"| {w // 1024}k | {d} | {identity(a, b, shared)} |")
        if control:
            out += ["Run-to-run control: the same file run twice on the same phone, replies byte-identical:", "",
                    "| Window | Phone | identical replies |", "|---|---|---|"] + control + [""]
        if rows and len(windows) > 1:
            out += [f"Replies identical to the {largest // 1024}k export, per window (raw stream and shown content over prompts both completed; parsed tool calls over the BFCL prompts):", "",
                    "| Phone | " + " | ".join(f"{w // 1024}k" for w in windows[:-1]) + " |",
                    "|" + "---|" * len(windows)] + rows + [""]
    return "\n".join(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("results", type=Path)
    ap.add_argument("--sizes", type=Path, help="json of {model-tag: bytes}")
    ap.add_argument("--out", type=Path)
    a = ap.parse_args()
    sizes = json.loads(a.sizes.read_text()) if a.sizes and a.sizes.exists() else {}
    cells, meta = load(a.results)
    text = render(cells, meta, sizes)
    if a.out:
        a.out.write_text(text)
    print(text)


if __name__ == "__main__":
    main()
