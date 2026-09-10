"""Render the retrieve-or-answer tables for the research note from the grader's summary.

    python3 tools/eval/bench/grade_decisions.py tools/eval/results/decisions > /dev/null
    python3 tools/eval/bench/report_decisions.py tools/eval/results/decisions [--min-rows 160]

One row per phone x model x arm with at least --min-rows rows, ordered by model, arm and
phone, with the columns the decision is read on. Prints Markdown; the note pastes it.
"""
import argparse, json
from pathlib import Path

ARMS = ["bare", "driven-search", "intent-search", "first-search", "driven-full", "intent-full"]
MODELS = {"LFM2.5-1.2B-Instruct-8da4w-32k.pte": "LFM2.5 1.2B compiled (8da4w)",
          "LFM2.5-1.2B-Instruct-Q4_K_M.gguf": "LFM2.5 1.2B Q4_K_M",
          "Qwen3-1.7B-Q8_0.gguf": "Qwen3 1.7B Q8_0"}
PHONES = {"poco": "Dimensity 9400", "tensor": "Tensor G5", "exynos": "Exynos 2400", "elite": "8 Elite",
          "five": "Dimensity 9400, five results"}
COLS = [("recall", "Searched when needed"), ("unnecessary", "Searched when not"),
        ("correct", "Correct"), ("correct_need", "Correct, needed"), ("correct_known", "Correct, known"),
        ("fabricated", "Fabricated search"), ("findable", "Answer in results"),
        ("correct_findable", "Correct when findable"), ("s", "Median s"), ("ttft_ms", "TTFT ms"), ("chars", "Chars")]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("directory")
    ap.add_argument("--min-rows", type=int, default=160)
    args = ap.parse_args()
    rows = json.loads((Path(args.directory) / "summary.json").read_text())
    rows = [r for r in rows if r["n"] >= args.min_rows]
    rows.sort(key=lambda r: (list(MODELS).index(r["model"]) if r["model"] in MODELS else 9,
                             ARMS.index(r["arm"]) if r["arm"] in ARMS else 9,
                             list(PHONES).index(r["phone"]) if r["phone"] in PHONES else 9))
    print("| Model | Arm | Phone | " + " | ".join(t for _, t in COLS) + " |")
    print("|---|---|---|" + "---|" * len(COLS))
    for r in rows:
        print(f"| {MODELS.get(r['model'], r['model'])} | {r['arm']} | {PHONES.get(r['phone'], r['phone'])} | "
              + " | ".join(str(r[k]).replace("% (", "% (") for k, _ in COLS) + " |")


if __name__ == "__main__":
    main()
