#!/usr/bin/env python3
"""Where the date lives when no tool is on, which is what a fresh install sends.

Since 2026-09-09 a fresh install has one tool on, web search, and a person who
turns it off is on the path this measures: no tool in the prompt, no deciding
pass, the reply written by the answering sampler at the user's temperature.
date_structure_eval.py measures its tools-off column under the tools-on head
(the tool paragraph is in prompt_dump.json's system text); here the head is
what the app renders with nothing switched on, the first two paragraphs alone.

Four arms. What ships (the scoped ack and the spacer); the date as a line at
the end of the instructions and as a line at their start, both of which lost
with tools on because the tool block pushed the fact far from the question,
an argument with no force when there is no tool block; and no date, the floor.

Setup:
  llama-server -m ~/ow-models/LFM2.5-1.2B-Instruct-QAD-Q4_0.gguf --jinja -c 4096 --port 8089
  DAY=2026-09-09 python3 eval/date_notools_eval.py [port] [arm ...]

Ranked here, decided on the phone (DateStructureProbe.compareTheShapesWithoutTools).
"""
import json, os, sys, urllib.request

DUMP = os.environ.get("PROMPT_DUMP_JSON", "eval/prompt_dump.json")
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8089
WANTED = set(sys.argv[2:])
DAY = os.environ.get("DAY", "2026-09-09")

# The head with nothing switched on: everything before the tool paragraph.
FULL = json.load(open(DUMP))["system"]
SYSTEM = "\n\n".join(FULL.split("\n\n")[:2])

REPLY_PASS = dict(temperature=0.8, top_k=40, top_p=0.95, min_p=0.05,
                  repeat_penalty=1.1, repeat_last_n=64)
GREEDY = dict(REPLY_PASS, temperature=0.0)

LINE = f"Today is {DAY}."
SHIPPED_ACK = ("Understood, I have that. I will not bring it up unless a question "
               "depends on it.")
SPACER = [{"role": "user", "content": "Ready when you are."},
          {"role": "assistant", "content": "Ready."}]

def head(text=SYSTEM):
    return {"role": "system", "content": text}

def arm_shipped(q):
    return [head(), {"role": "user", "content": LINE},
            {"role": "assistant", "content": SHIPPED_ACK}] + SPACER + \
        [{"role": "user", "content": q}]

def arm_system_end(q):
    return [head(f"{SYSTEM}\n\n{LINE}"), {"role": "user", "content": q}]

def arm_system_start(q):
    return [head(f"{LINE}\n\n{SYSTEM}"), {"role": "user", "content": q}]

def arm_nodate(q):
    return [head(), {"role": "user", "content": q}]

ARMS = {"shipped": arm_shipped, "system_end": arm_system_end,
        "system_start": arm_system_start, "nodate": arm_nodate}

SMALLTALK = ["hi", "hello", "hey", "yo", "good morning", "good evening",
             "thanks!", "thank you", "ok", "cool", "how are you?",
             "what's up?", "hi there", "hey!", "sup", "howdy"]
SEEDS = [1, 2, 3, 4, 5, 6, 7, 8]

def ask(messages, sampler, seed=1):
    body = {"model": "m", "messages": messages, "max_tokens": 160,
            "seed": seed, **sampler}
    req = urllib.request.Request(
        f"http://localhost:{PORT}/v1/chat/completions",
        json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=900) as r:
        return (json.load(r)["choices"][0]["message"].get("content") or "").strip()

def bleeds(text):
    low = text.lower()
    return (DAY in text or DAY[:4] in text or "september" in low
            or "today is" in low or "today's date" in low
            or "current date" in low or "the date you" in low)

def run(name, build):
    bleed = 0
    for q in SMALLTALK:
        for s in SEEDS:
            t = ask(build(q), REPLY_PASS, s)
            if bleeds(t):
                bleed += 1
                if bleed <= 3:
                    print(f"    BLEED {name}/{q}/{s}: {t[:100]!r}")
    date_greedy = ask(build("What is today's date?"), GREEDY)
    date_ok = DAY in date_greedy
    date_seeded = sum(1 for s in SEEDS
                      if DAY in ask(build("What is today's date?"), REPLY_PASS, s))
    total = len(SMALLTALK) * len(SEEDS)
    print(f"{name:13s} bleed={bleed:3d}/{total} ({100*bleed/total:5.1f}%)  "
          f"dateOK greedy={date_ok} seeded={date_seeded}/{len(SEEDS)}  "
          f"{date_greedy[:60]!r}")
    sys.stdout.flush()

print(f"port {PORT}  day {DAY}  no tools  smalltalk {len(SMALLTALK)}x{len(SEEDS)} seeds")
for name, build in ARMS.items():
    if not WANTED or name in WANTED:
        run(name, build)
