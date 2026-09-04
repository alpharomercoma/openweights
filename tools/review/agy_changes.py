#!/usr/bin/env python3
"""Adversarial review of one change rather than the whole codebase.

    python3 tools/review/agy_changes.py <output dir> [git-range]

`agy_review.py` reads the codebase by area, which takes hours and is the right shape for
a periodic sweep. This is the shape for a session: the files a change actually touched,
plus their tests, in one pass, so the review is about the new code and the reviewer is
not spending its context on source that has already been reviewed twice.

Same two hard-won facts as the area runner. `agy --print` cannot approve a tool call, so
the source has to be inside the prompt and the prompt has to say not to go looking; and
stdin stream-json is what escapes the argument-length limit.
"""
import json, subprocess, sys, time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(sys.argv[1]); OUT.mkdir(parents=True, exist_ok=True)
RANGE = sys.argv[2] if len(sys.argv) > 2 else "HEAD"
LIMIT = 320_000
MODEL = "gemini-3.8-flash-medium"

PROMPT = """You are an adversarial code reviewer for OpenWeights, an open-source Android app (Kotlin/Compose, llama.cpp and ExecuTorch runtimes, Apache-2.0) that runs open-weight LLMs entirely on the phone. Product promises that must hold: no accounts, no telemetry, nothing leaves the device except the user's own Hugging Face downloads and the web_search / fetch_url / show_pictures tools which are declared; the model can call tools (files in a user-shared folder, a QuickJS sandbox, a loopback canvas server, memory) under an approval mode the user picks.

Below is the source of every file changed by one piece of work, with its tests. Review it for REAL defects only: crashes, data loss, races, resource leaks, privacy leaks, security holes (path traversal, injection through model output, sandbox escape, loopback server abuse, catastrophic backtracking on a pattern written by a language model), logic errors, incorrect KV-cache or prompt handling, wrong error handling, and tests that cannot fail. Ignore style, naming and formatting.

Pay particular attention to:
- the new page-search path in core/tools (a regular expression supplied by a language model runs against up to half a megabyte of somebody else's HTML)
- the prompt assembly in ChatViewModel (byte stability of the KV cache prefix: anything that rewrites bytes the cache has already read costs a full re-read on a phone)
- error handling on paths that reach a coroutine with no parent to catch it

For every finding give:
1. Severity (critical / high / medium / low)
2. File and the exact line or function
3. The concrete failure scenario: what input or state leads to what wrong outcome
4. Confidence (0-100) that it is a real bug given only this code
5. A suggested fix in one or two sentences

Order findings by severity. Be skeptical of your own findings: if the code visibly guards against the case, do not report it. If you find nothing above low severity, say so plainly.

Everything you need is in this message: do NOT search for, list or open files, and do not run any command. Review only what is pasted below.

FILES:
{listing}

=== SOURCE ===
{source}
"""


def changed():
    out = subprocess.run(["git", "diff", "--name-only", RANGE],
                         capture_output=True, text=True, cwd=ROOT).stdout.split()
    out += subprocess.run(["git", "ls-files", "--others", "--exclude-standard"],
                          capture_output=True, text=True, cwd=ROOT).stdout.split()
    keep = []
    for name in sorted(set(out)):
        f = ROOT / name
        if f.is_file() and f.suffix in (".kt", ".css", ".html", ".py", ".kts"):
            keep.append(f)
    return keep


def bundles(files):
    batch, size = [], 0
    for f in files:
        n = f.stat().st_size
        if batch and size + n > LIMIT:
            yield batch; batch, size = [], 0
        batch.append(f); size += n
    if batch:
        yield batch


def run(index, batch):
    listing = "\n".join(str(f.relative_to(ROOT)) for f in batch)
    source = "".join(
        f"\n\n----- {f.relative_to(ROOT)} -----\n{f.read_text(errors='replace')}"
        for f in batch)
    prompt = PROMPT.format(listing=listing, source=source)
    msg = json.dumps({"event": "user", "message": {"role": "user", "content": prompt}}) + "\n"
    name = f"changes-{index}"
    (OUT / f"{name}.prompt.txt").write_text(prompt)
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
        (OUT / f"{name}.raw.log").write_text(proc.stdout + "\n--- stderr ---\n" + proc.stderr)
        if result and result.get("status") == "SUCCESS" and len(result.get("response", "")) > 2000:
            (OUT / f"{name}.md").write_text(result["response"])
            print(f"{name}: ok {len(batch)} files {len(prompt)//1000}KB "
                  f"{time.time()-started:.0f}s", flush=True)
            return
        print(f"{name}: attempt {attempt} failed: {result and result.get('error')} "
              f"rc={proc.returncode} stderr={proc.stderr[-300:]!r}", flush=True)
        time.sleep(60)


files = changed()
print(f"== {len(files)} changed files, {sum(f.stat().st_size for f in files)//1000}KB",
      flush=True)
for i, batch in enumerate(bundles(files), 1):
    run(i, batch)
print("ALL DONE", flush=True)
