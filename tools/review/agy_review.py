#!/usr/bin/env python3
"""Adversarial review of the codebase by area, through the agy CLI in print mode.

    python3 tools/review/agy_review.py <output dir> [area ...]

One report per bundle lands in the output directory as `<area>-<n>.md`, beside the exact
prompt that produced it; a bundle whose report exists is skipped, so an interrupted run
is resumed by running the same command again.

agy cannot approve a tool call non-interactively, so a pass that tries to open a file
produces one line and nothing else. Each pass therefore carries its source inside the
prompt, and the prompt says so; stdin stream-json has no argument-length limit, which
the command line does. Areas are split into bundles of at most LIMIT bytes, which is
what one pass reads reliably, and reviewed one after another. A response under two
thousand characters is the model announcing a tool call rather than a review, and is
retried.

Every finding is a claim to verify against the source, not a bug: of the 65 the first
run produced, 16 were refuted on reading. docs/research/gemini-review-2026-09-03.md
has the ledger.
"""
import json, os, subprocess, sys, time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(sys.argv[1]); OUT.mkdir(parents=True, exist_ok=True)
LIMIT = 320_000
MODEL = "gemini-3.8-flash-medium"

PROMPT = """You are an adversarial code reviewer for OpenWeights, an open-source Android app (Kotlin/Compose, llama.cpp and ExecuTorch runtimes, Apache-2.0) that runs open-weight LLMs entirely on the phone. Product promises that must hold: no accounts, no telemetry, nothing leaves the device except the user's own Hugging Face downloads and the web_search / fetch_url / show_pictures tools which are declared; the model can call tools (files in a user-shared folder, a QuickJS sandbox, a loopback canvas server, memory) under an approval mode the user picks.

Review the source below for REAL defects only: crashes, data loss, races, resource leaks, privacy leaks (anything sent off-device that should not be), security holes (path traversal, injection through model output, sandbox escape, loopback server abuse by other apps), logic errors, incorrect KV-cache or prompt handling, wrong error handling, and tests that cannot fail. Ignore style, naming, formatting and hypothetical concerns you cannot ground in the code shown.

For every finding give:
1. Severity (critical / high / medium / low)
2. File and the exact line or function
3. The concrete failure scenario: what input or state leads to what wrong outcome
4. Confidence (0-100) that it is a real bug given only this code
5. A suggested fix in one or two sentences

Order findings by severity. Be skeptical of your own findings: if the code visibly guards against the case, do not report it. If you find nothing above low severity, say so plainly. End with a one-paragraph summary of the riskiest area you saw.

Everything you need is in this message: do NOT search for, list or open files, and do not run any command. Review only what is pasted below.

AREA: {area}
FILES:
{listing}

=== SOURCE ===
{source}
"""

AREAS = {
    "engine-native": ["core/engine/src/main/cpp/*.cpp", "core/engine/src/main/cpp/*.h"],
    "engine-kotlin": ["core/engine/src/main/kotlin/**/*.kt", "core/engine/src/accelerated/kotlin/**/*.kt", "core/engine/src/standard/kotlin/**/*.kt"],
    "tools": ["core/tools/src/main/kotlin/**/*.kt"],
    "sandbox-hub-device-common": ["core/sandbox/src/main/**/*.kt", "core/sandbox/src/main/cpp/*.cpp", "core/hub/src/main/kotlin/**/*.kt", "core/device/src/main/kotlin/**/*.kt", "core/common/src/main/kotlin/**/*.kt"],
    "data": ["core/data/src/main/kotlin/**/*.kt"],
    "app-chat": ["app/src/main/kotlin/io/github/alpharomercoma/openweights/ui/chat/**/*.kt"],
    "app-rest": ["app/src/main/kotlin/**/*.kt", "!app/src/main/kotlin/io/github/alpharomercoma/openweights/ui/chat/**", "app/src/main/AndroidManifest.xml", "app/src/main/res/xml/*.xml"],
}

def files_for(patterns):
    include, exclude = [], []
    for p in patterns:
        (exclude if p.startswith("!") else include).append(p.lstrip("!"))
    found = []
    for p in include:
        found += [f for f in ROOT.glob(p) if f.is_file()]
    found = sorted(set(found))
    def excluded(f):
        return any(f.match(str(ROOT / e)) or str(f).startswith(str(ROOT / e.rstrip("*/"))) for e in exclude)
    return [f for f in found if not excluded(f)]

def bundles(files):
    batch, size = [], 0
    for f in files:
        n = f.stat().st_size
        if batch and size + n > LIMIT:
            yield batch; batch, size = [], 0
        batch.append(f); size += n
    if batch:
        yield batch

def run(area, index, batch):
    listing = "\n".join(str(f.relative_to(ROOT)) for f in batch)
    source = "".join(f"\n\n----- {f.relative_to(ROOT)} -----\n{f.read_text(errors='replace')}" for f in batch)
    prompt = PROMPT.format(area=f"{area} (part {index})", listing=listing, source=source)
    msg = json.dumps({"event": "user", "message": {"role": "user", "content": prompt}}) + "\n"
    name = f"{area}-{index}"
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
        # A short response is the model announcing a tool call it cannot make headless,
        # not a review; treat it as a failure and go again.
        if result and result.get("status") == "SUCCESS" and len(result.get("response", "")) > 2000:
            (OUT / f"{name}.md").write_text(result["response"])
            print(f"{name}: ok {len(batch)} files {len(prompt)//1000}KB "
                  f"{result['usage']['input_tokens']} in / {result['usage']['output_tokens']} out "
                  f"{time.time()-started:.0f}s", flush=True)
            return
        print(f"{name}: attempt {attempt} failed: {result and result.get('error')} rc={proc.returncode} "
              f"stderr={proc.stderr[-300:]!r}", flush=True)
        time.sleep(60)

wanted = sys.argv[2:] or list(AREAS)
for area in wanted:
    files = files_for(AREAS[area])
    total = sum(f.stat().st_size for f in files)
    print(f"== {area}: {len(files)} files, {total//1000}KB", flush=True)
    for i, batch in enumerate(bundles(files), 1):
        if (OUT / f"{area}-{i}.md").exists():
            print(f"{area}-{i}: already done", flush=True); continue
        run(area, i, batch)
print("ALL DONE", flush=True)
