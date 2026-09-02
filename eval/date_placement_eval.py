#!/usr/bin/env python3
"""Regression suite for where the date line lives in the prompt.

Replays the app's byte-exact prompts against llama-server and counts, per
placement arm: unnecessary tool calls on chit-chat, missed calls on questions
that need one, date recitals in greetings, and whether "what is today's date?"
still gets answered without a search.

Setup:
  1. Export the app's real bytes (system message, first user turn, the 14-tool
     catalogue) — run once after any prompt-affecting change:
       PROMPT_DUMP=eval/prompt_dump.json ./gradlew :app:testStandardDebugUnitTest \
           --tests '*PromptDumpTest*'
  2. llama-server -m <model.gguf> --jinja -c 8192 --port 8089
  3. python3 eval/date_placement_eval.py eval/prompt_dump.json

History (2026-09-01, LFM2.5-1.2B Q4_0/Q4_K_M and Qwen3-1.7B Q8_0, temp 0 and
the shipped 0.8 over 5 seeds): prepending "Today is X." to the first question
drew tool calls on 8/12 chit-chat cases at temp 0 (17/30 at 0.8) — "hi" called
read_memory; the same fact as an acknowledged exchange dropped that to the
no-date floor. The bare "Today is X." exchange then recited the date back at
greetings 8/30 times; the shipped wording — "(For context: today is X. Only
mention it if asked.)" / "Understood." — measured 2/30 calls, 2/30 recitals.
"""
import json, os, sys, urllib.request

# The app's tool-pass sampler, stated in full: see routing_matrix.py for why, and for the
# 2026-09-02 measurement behind the pass running with no repeat penalty.
SAMPLER = {
    "top_k": int(os.environ.get("TOP_K", 40)),
    "top_p": float(os.environ.get("TOP_P", 0.95)),
    "min_p": float(os.environ.get("MIN_P", 0.05)),
    "repeat_penalty": float(os.environ.get("REPEAT_PENALTY", 1.0)),
    "repeat_last_n": int(os.environ.get("REPEAT_LAST_N", 64)),
}

DUMP = sys.argv[1] if len(sys.argv) > 1 else "eval/prompt_dump.json"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8089
d = json.load(open(DUMP))
SYSTEM = d["system"]
TOOLS = [{"type": "function", "function": t} for t in d["tools"]]
DAY = "2026-09-01"

# 2026-09-01 second pass: the scoped wording answered greetings cleanly but broke
# the temp-0 date question on every model in eval/routing_matrix.py; the plain pair
# answers it 1/1 everywhere and drew the fewest chit-chat calls of any ack (1/30).
USER_LINE = f"Today is {DAY}."
ACK = "Understood, I have that."

def arm_shipped(q):
    return [{"role": "system", "content": SYSTEM},
            {"role": "user", "content": USER_LINE},
            {"role": "assistant", "content": ACK},
            {"role": "user", "content": q}]

def arm_system(q):  # the pre-2026-09-01 placement, kept as the comparison point
    return [{"role": "system", "content": f"Today is {DAY}.\n\n{SYSTEM}"},
            {"role": "user", "content": q}]

def arm_prepend(q):  # the placement that regressed, kept as the tripwire
    return [{"role": "system", "content": SYSTEM},
            {"role": "user", "content": f"Today is {DAY}.\n\n{q}"}]

def arm_nodate(q):  # the abstention floor nothing should beat
    return [{"role": "system", "content": SYSTEM}, {"role": "user", "content": q}]

ARMS = {"shipped": arm_shipped, "system": arm_system,
        "prepend": arm_prepend, "nodate": arm_nodate}

NO_CALL = ["hi", "hello!", "good morning", "thanks!", "how are you?",
           "Write a haiku about rain.", "What is the capital of France?",
           "Translate 'good morning' into Spanish.", "Who wrote Pride and Prejudice?",
           "What is 2+2?", "Tell me a joke.", "Explain photosynthesis briefly."]
CALL = [("What is the weather in Manila right now?", "web_search"),
        ("What's in the news today?", "web_search"),
        ("Read https://example.com and tell me what it says.", "fetch_url"),
        ("What is 48273 times 1179?", "run_script"),
        ("Show me pictures of the Eiffel Tower.", "show_pictures"),
        ("Remind me to stretch every 30 minutes.", "watch")]

def ask(messages, temperature=0.0, seed=1):
    body = json.dumps({"model": "m", "messages": messages, "tools": TOOLS,
                       "temperature": temperature, "max_tokens": 200, "seed": seed,
                       **SAMPLER}).encode()
    req = urllib.request.Request(f"http://localhost:{PORT}/v1/chat/completions",
                                 body, {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as r:
        m = json.load(r)["choices"][0]["message"]
    return ([c["function"]["name"] for c in (m.get("tool_calls") or [])],
            (m.get("content") or "").strip())

def recites(t):
    return "2026" in t or "September" in t or "Today is" in t

for name, build in ARMS.items():
    over = miss = 0
    for q in NO_CALL:
        calls, _ = ask(build(q))
        if calls:
            over += 1
            print(f"    OVER  {q!r} -> {calls}")
    for q, want in CALL:
        calls, _ = ask(build(q))
        if not calls:
            miss += 1
            print(f"    MISS  {q!r}")
    echo = sum(1 for q in NO_CALL[:6] for s in (1, 2, 3, 4, 5)
               if (lambda cc: not cc[0] and recites(cc[1]))(ask(build(q), 0.8, s)))
    calls, content = ask(build("What is today's date?"))
    date_ok = not calls and "2026" in content
    print(f"{name:8s} overcalls={over}/12 misses={miss}/6 "
          f"dateEcho(0.8x5)={echo}/30 dateOK={date_ok}")
    sys.stdout.flush()
