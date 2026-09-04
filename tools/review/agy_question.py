#!/usr/bin/env python3
"""Puts one design question to agy, with the source it needs pasted in.

    python3 tools/review/agy_question.py <output dir> <question file> <source file> ...

The two runners beside this one review code that has been written. This one is for the
question that comes before the code: should this be built, and in what shape. Same two
hard-won facts as those: `agy --print` cannot approve a tool call, so the source has to be
inside the prompt and the prompt has to say not to go looking; and stdin stream-json is
what escapes the argument-length limit.

The question file is prose. Everything after it is source, pasted under its own path so
the answer can point at a line rather than at a paraphrase.
"""
import json
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(sys.argv[1])
OUT.mkdir(parents=True, exist_ok=True)
QUESTION = Path(sys.argv[2]).read_text()
SOURCES = [Path(p) for p in sys.argv[3:]]
MODEL = "gemini-3.8-flash-medium"

PREAMBLE = """You are advising on OpenWeights, an open-source Android app (Kotlin/Compose,
llama.cpp and ExecuTorch runtimes, Apache-2.0) that runs open-weight LLMs entirely on the
phone. No accounts, no telemetry, nothing leaves the device except the user's own Hugging
Face downloads and the declared web tools.

Three standing constraints you must reason within:

1. **Measured, not assumed.** Every default in this codebase is justified by a measurement
   on a real phone, and the KDoc says which one. A recommendation of the form "add this, it
   is usually better" is worth nothing here; say what would have to be measured and what
   result would settle it.
2. **KV cache byte stability.** The prompt's head must be byte-identical between turns or a
   phone pays a full re-prefill, measured at eleven to nineteen seconds on a hybrid model.
   Anything that rewrites the head is expensive in a way it is not on a server.
3. **A settings sheet is a cost.** This sheet was deliberately cut from roughly seventeen
   controls to five plus a disclosure, because the three anybody touches were buried among
   samplers whose defaults are correct and whose names come from papers. A control that
   changes nothing anybody can perceive is worse than a missing one.

Answer the question below with judgement, not a survey. For each thing you would add, say
what it buys, what it costs, and what measurement would justify it. For each thing you
would refuse, say why plainly. Be willing to say "none of these".

Everything you need is in this message: do NOT search for, list or open files, and do not
run any command. Reason only about what is pasted below.

=== QUESTION ===
{question}

=== SOURCE ===
{source}
"""


def main():
    source = "".join(
        f"\n\n----- {f.relative_to(ROOT) if f.is_relative_to(ROOT) else f} -----\n"
        f"{f.read_text(errors='replace')}"
        for f in SOURCES
    )
    prompt = PREAMBLE.format(question=QUESTION, source=source)
    (OUT / "question.prompt.txt").write_text(prompt)
    msg = json.dumps({"event": "user", "message": {"role": "user", "content": prompt}}) + "\n"

    for attempt in range(3):
        started = time.time()
        proc = subprocess.run(
            ["agy", f"--model={MODEL}", "--print-timeout=25m", "--input-format=stream-json",
             "--output-format=stream-json", "--print="],
            input=msg, capture_output=True, text=True, cwd=ROOT)
        result = None
        for line in proc.stdout.splitlines():
            if line.startswith('{"event":"result"'):
                result = json.loads(line)["result"]
        (OUT / "question.raw.log").write_text(
            proc.stdout + "\n--- stderr ---\n" + proc.stderr)
        if result and result.get("status") == "SUCCESS" and len(result.get("response", "")) > 1000:
            (OUT / "question.md").write_text(result["response"])
            print(f"ok {len(SOURCES)} files {len(prompt)//1000}KB "
                  f"{time.time()-started:.0f}s", flush=True)
            return
        print(f"attempt {attempt} failed: {result and result.get('error')} "
              f"rc={proc.returncode} stderr={proc.stderr[-300:]!r}", flush=True)
        time.sleep(60)


main()
