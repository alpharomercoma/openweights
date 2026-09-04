#!/usr/bin/env python3
"""Where the date lives, measured on both of the app's passes.

Supersedes date_placement_eval.py, which measured one pass with the other's
sampler. The app runs two passes with different samplers and this matters more
than any wording:

  tool pass   temp 0,   repeat_penalty 1.0  (TurnRunner.deciding)
  reply pass  temp 0.8, repeat_penalty 1.1  (SamplerParams defaults)

The old suite sent repeat_penalty 1.0 everywhere and asked six greetings. Under
the reply pass's own sampler, and a wider set of things a person says when they
mean nothing in particular, the shipped structure answers the date exchange
instead of the user on 24 of 144 tries on LFM2.5-1.2B QAD-Q4_0:

    ['hey'] It seems like you just mentioned today's date. Could you please
            tell me more about what you'd like to discuss?

That is the bug this measures. It is not the date being recited, which would be
cosmetic; it is the greeting going unanswered because the date exchange is the
nearest user turn and a greeting carries nothing to outweigh it.

Both samplers produce a user-visible reply, which is why both are measured.
`TurnRunner` offers tools on the first pass; when that pass emits no call its
own prose is the answer. So with tools on (Auto, the default) the greeting is
answered greedily at temp 0, and only with tools off does the 0.8 sampler
write it. An arm has to be clean on both or it is clean in one mode.

Five axes, because moving the date has never been free on fewer than all of them:

  bleed0    tools on:  greedy reply about the date instead of the user
  bleed     tools off: same, over seeds at the shipped temperature
  dateOK    "What is today's date?" answered, with no tool call
  overcall  tool pass: a call on something that needs none
  miss      tool pass: no call on something that needs one

Setup:
  1. PROMPT_DUMP=$PWD/eval/prompt_dump.json ./gradlew \
         :app:testStandardDebugUnitTest --tests '*PromptDumpTest*'
  2. llama-server -m <model.gguf> --jinja -c 8192 --port 8089
  3. python3 eval/date_structure_eval.py [port] [arm ...]
"""
import json, os, sys, urllib.request

DUMP = os.environ.get("PROMPT_DUMP_JSON", "eval/prompt_dump.json")
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8089
WANTED = set(sys.argv[2:])
d = json.load(open(DUMP))
SYSTEM = d["system"]
TOOLS = [{"type": "function", "function": t} for t in d["tools"]]
DAY = os.environ.get("DAY", "2026-09-05")

# Stated in full rather than inherited from the server, which is the rule the
# 2026-09-02 sweep left behind: an eval that lets the server pick is measuring
# the server.
TOOL_PASS = dict(temperature=0.0, top_k=40, top_p=0.95, min_p=0.05,
                 repeat_penalty=1.0, repeat_last_n=64)
REPLY_PASS = dict(temperature=0.8, top_k=40, top_p=0.95, min_p=0.05,
                  repeat_penalty=1.1, repeat_last_n=64)

LINE = f"Today is {DAY}."
# The pre-2026-09-05 ack, kept as the arm that reproduces the bug.
ACK = "Understood, I have that."
# What ships: PromptDay.DATE_ACK, byte for byte.
SHIPPED_ACK = ("Understood, I have that. I will not bring it up unless a question "
               "depends on it.")

def head(extra=""):
    return {"role": "system", "content": (extra + SYSTEM) if extra else SYSTEM}

def exchange(line=LINE, ack=ACK):
    return [{"role": "user", "content": line},
            {"role": "assistant", "content": ack}]

# --- the arms ----------------------------------------------------------------
# Each takes the question and returns the whole message list.

def arm_shipped(q):
    """What ships today: the plain pair, immediately before the question."""
    return [head()] + exchange() + [{"role": "user", "content": q}]

def arm_handoff(q):
    """The ack ends by handing the turn back, so the exchange is closed."""
    return [head()] + exchange(ack=f"{ACK} What would you like to do?") + \
        [{"role": "user", "content": q}]

def arm_scoped_ack(q):
    """The constraint in the model's own mouth, and nothing else.

    Half of what ships. "Only mention it if asked" as part of the USER line broke
    the date question on every model (2026-09-01); as part of the assistant's
    acknowledgement it is a commitment the model made. On the phone this alone
    took LFM2.5-1.2B from 6/16 to 3/16, which is why it is not the whole fix.
    """
    return [head()] + exchange(ack=SHIPPED_ACK) + [{"role": "user", "content": q}]

def arm_head_only(q):
    """The pre-2026-09-01 placement, kept as the comparison point."""
    return [head(f"Today is {DAY}.\n\n")] + [{"role": "user", "content": q}]

def arm_head_and_ack(q):
    """The fact in the head, and the exchange kept as the worked example.

    The exchange is load-bearing for reasons that have nothing to do with the
    date: with no two-turn example at all, QAD's trivia fell 6/6 to 4/6 and
    Q4_0 lost the multi-turn rows. This keeps the example and moves the fact
    out of the nearest user turn, which is where the bleed comes from.
    """
    return [head(f"Today is {DAY}.\n\n")] + exchange(
        line="Answer from what you know when you can, and look things up when "
             "you cannot.",
        ack="Understood, I have that.") + [{"role": "user", "content": q}]

def arm_nodate(q):
    """The floor. Nothing that adds the date may score worse than this."""
    return [head(), {"role": "user", "content": q}]


def arm_scoped_handoff(q):
    """The commitment, plus handing the turn back so the exchange reads as closed."""
    return [head()] + exchange(
        ack="Understood, I have that. I will not bring it up unless you ask. "
            "What would you like to do?") + [{"role": "user", "content": q}]

# PromptDay.HANDOVER and HANDOVER_ACK, byte for byte.
SPACER = [{"role": "user", "content": "Ready when you are."},
          {"role": "assistant", "content": "Ready."}]

def arm_spaced(q):
    """The date one turn further back, so it is not the adjacent user turn.

    Reaches the floor on bleed and loses the date question: pushed this far away the
    tool pass calls read_memory for "what is today's date?", which is the same
    failure every "mention it only if asked" wording produced in 2026-09-01.
    """
    return [head()] + exchange() + SPACER + [{"role": "user", "content": q}]

def arm_spaced_scoped(q):
    """What ships: the scoped ack, then something ordinary, then the question.

    0/16 on both models on the phone (`DateStructureProbe`), with the date still
    answered. This harness scores it dateOK=False, and the phone disagrees:
    the catalogue here is the sixteen tools of prompt_dump.json and a real turn
    carries whatever the user switched on. Ranked here, decided there.
    """
    return [head()] + exchange(ack=SHIPPED_ACK) + SPACER + \
        [{"role": "user", "content": q}]

ARMS = {
    "shipped": arm_shipped,
    "scoped_ack": arm_scoped_ack,
    "scoped_handoff": arm_scoped_handoff,
    "spaced": arm_spaced,
    "spaced_scoped": arm_spaced_scoped,
    # Tripwires. Both head placements answer greetings cleanly and lose the date
    # question, which is the trade this whole file exists to refuse.
    "head_only": arm_head_only,
    "head_and_ack": arm_head_and_ack,
    "handoff": arm_handoff,
    "nodate": arm_nodate,
}

# --- the cases ---------------------------------------------------------------
# Things a person says that carry no request. A greeting has nothing in it to
# outweigh the turn before, which is what makes it the probe.
SMALLTALK = ["hi", "hello", "hey", "yo", "good morning", "good evening",
             "thanks!", "thank you", "ok", "cool", "how are you?",
             "what's up?", "hi there", "hey!", "sup", "howdy"]

NO_CALL = SMALLTALK[:6] + [
    "Write a haiku about rain.", "What is the capital of France?",
    "Translate 'good morning' into Spanish.", "Who wrote Pride and Prejudice?",
    "What is 2+2?", "Tell me a joke."]

CALL = [("What is the weather in Manila right now?", "web_search"),
        ("What's in the news today?", "web_search"),
        ("Read https://example.com and tell me what it says.", "fetch_url"),
        ("Show me pictures of the Eiffel Tower.", "show_pictures"),
        ("Remind me to stretch every 30 minutes.", "watch")]

SEEDS = [1, 2, 3, 4, 5, 6, 7, 8]

def ask(messages, sampler, seed=1, tools=True):
    body = {"model": "m", "messages": messages, "max_tokens": 160,
            "seed": seed, **sampler}
    if tools:
        body["tools"] = TOOLS
    req = urllib.request.Request(
        f"http://localhost:{PORT}/v1/chat/completions",
        json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=900) as r:
        m = json.load(r)["choices"][0]["message"]
    return ([c["function"]["name"] for c in (m.get("tool_calls") or [])],
            (m.get("content") or "").strip())

def bleeds(text):
    """True when a reply to smalltalk is about the date rather than the person."""
    low = text.lower()
    return (DAY in text or "2026" in text or "september" in low
            or "today is" in low or "today's date" in low
            or "current date" in low or "the date you" in low)

def run(name, build):
    # Tools on: one greedy sample is the whole distribution.
    bleed0 = sum(1 for q in SMALLTALK
                 if (lambda cc: not cc[0] and bleeds(cc[1]))(
                     ask(build(q), TOOL_PASS)))
    bleed = sum(
        1 for q in SMALLTALK for s in SEEDS
        if (lambda cc: not cc[0] and bleeds(cc[1]))(
            ask(build(q), REPLY_PASS, s)))
    over = sum(1 for q in NO_CALL if ask(build(q), TOOL_PASS)[0])
    miss = sum(1 for q, _ in CALL if not ask(build(q), TOOL_PASS)[0])
    calls, greedy = ask(build("What is today's date?"), TOOL_PASS)
    date_ok = not calls and (DAY in greedy or "2026" in greedy)
    n0, total = len(SMALLTALK), len(SMALLTALK) * len(SEEDS)
    print(f"{name:14s} bleed0={bleed0:2d}/{n0} ({100*bleed0/n0:5.1f}%)  "
          f"bleed={bleed:3d}/{total} ({100*bleed/total:5.1f}%)  "
          f"overcall={over}/{len(NO_CALL)}  miss={miss}/{len(CALL)}  "
          f"dateOK={date_ok}")
    sys.stdout.flush()

print(f"port {PORT}  day {DAY}  smalltalk {len(SMALLTALK)}x{len(SEEDS)} seeds")
for name, build in ARMS.items():
    if not WANTED or name in WANTED:
        run(name, build)
