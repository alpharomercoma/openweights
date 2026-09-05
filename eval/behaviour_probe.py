#!/usr/bin/env python3
"""Three behaviours reported on 2026-09-05, measured on the host against the app's own prompt.

  plan     - /plan on an easy task: does the model plan, or answer?
  entity   - "who is Killua?": does it search, or recall?
  credulity - two agreeing snippets and one disagreeing: does it flag the split?

Start llama-server yourself (see routing_matrix.py) and check /props names the model you
think it does: a server left over from an earlier session on the same port answered as
the wrong model once. Usage: behaviour_probe.py <port> <plan|entity|cred|all>
"""
import json
import os
import sys
import urllib.request

PORT = int(sys.argv[1])
WHICH = sys.argv[2]
ARM = sys.argv[3] if len(sys.argv) > 3 else "current"
NOTHINK = os.environ.get("NOTHINK") == "1"

d = json.load(open("/Users/alpha/mobile-inference/eval/prompt_dump.json"))
SYSTEM = d["system"]
TOOLS = [{"type": "function", "function": t} for t in d["tools"]]
SAMPLER = {"temperature": 0, "top_k": 40, "top_p": 0.95, "min_p": 0.05,
           "repeat_penalty": 1.0, "repeat_last_n": 64}
DATE = [{"role": "user", "content": "Today is 2026-09-05."},
        {"role": "assistant", "content": "Understood, I have that."}]


def ask(messages, tools=None, max_tokens=400):
    payload = {"model": "m", "messages": messages, "max_tokens": max_tokens, "seed": 1, **SAMPLER}
    if tools:
        payload["tools"] = tools
    if NOTHINK:
        payload["chat_template_kwargs"] = {"enable_thinking": False}
    req = urllib.request.Request(f"http://localhost:{PORT}/v1/chat/completions",
                                 json.dumps(payload).encode(),
                                 {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as r:
        m = json.load(r)["choices"][0]["message"]
    calls = [(c["function"]["name"], c["function"]["arguments"]) for c in (m.get("tool_calls") or [])]
    return calls, (m.get("content") or "").strip()


def is_plan(text):
    import re
    steps = [l for l in text.splitlines() if re.match(r"^\s*(?:step\s*)?(?:\d+[.):]|[-*•])\s*\S", l, re.I)]
    return len(steps) >= 2


# ---------------------------------------------------------------- plan mode
PLAN_CURRENT = ("Do not act on anything yet. Say what you would do and why, as short steps. If "
                "the request could mean more than one thing, or a detail you would need was "
                "never given, ask before planning.")
PLAN_STRICT = ("You are in plan mode. Do not do the task and do not give the answer, even if you "
               "already know it. Reply only with a numbered list of two to five short steps "
               "saying what you would do, one line each. If the request could mean more than "
               "one thing, or a detail you would need was never given, ask before planning.")
PLAN_REPAIR = ("That was the answer, not a plan. Plan mode wants the steps: reply only with a "
               "numbered list of two to five short steps saying what you would do, one line "
               "each, and do not give the answer.")
PLAN_TASKS = [
    "What is 2+2?",
    "Who is Killua?",
    "What is the capital of France?",
    "Translate 'good morning' into Spanish.",
    "Rename every .txt in my notes folder to .md",
    "Summarise the budget section of report.md",
    "Find out who won the F1 race last weekend and save it to a file",
    "Write a haiku about rain.",
]
# What the app's system message looks like in plan mode: the configured tool prompt is
# replaced by the plan instruction (toolInstruction in ChatViewModel).
TOOL_PROMPT_START = "You already know the answer to most questions."


def plan_system(instruction):
    head = SYSTEM.split(TOOL_PROMPT_START)[0].rstrip()
    return head + "\n\n" + instruction


def run_plan():
    arms = {"current": PLAN_CURRENT, "strict": PLAN_STRICT}
    for arm_name, instr in arms.items():
        planned = 0
        rows = []
        for t in PLAN_TASKS:
            msgs = [{"role": "system", "content": plan_system(instr)}] + DATE + [{"role": "user", "content": t}]
            calls, content = ask(msgs)
            ok = is_plan(content)
            planned += ok
            rows.append(f"    {'PLAN ' if ok else 'ANSW '} {t!r} -> {content[:90]!r} calls={[c[0] for c in calls]}")
        print(f"plan arm={arm_name}: planned {planned}/{len(PLAN_TASKS)}")
        for r in rows:
            print(r)
    # The repair: current instruction, then one push when the reply was not a plan.
    repaired = 0
    rows = []
    for t in PLAN_TASKS:
        msgs = [{"role": "system", "content": plan_system(PLAN_CURRENT)}] + DATE + [{"role": "user", "content": t}]
        calls, content = ask(msgs)
        if is_plan(content):
            repaired += 1
            rows.append(f"    PLAN  {t!r} (first pass)")
            continue
        msgs += [{"role": "assistant", "content": content}, {"role": "user", "content": PLAN_REPAIR}]
        calls, content2 = ask(msgs)
        ok = is_plan(content2)
        repaired += ok
        rows.append(f"    {'PLAN ' if ok else 'ANSW '} {t!r} after repair -> {content2[:90]!r}")
    print(f"plan arm=current+repair: planned {repaired}/{len(PLAN_TASKS)}")
    for r in rows:
        print(r)


# ---------------------------------------------------------------- entity recall
ENTITY_QS = [
    ("Who is Killua?", "web_search"),
    ("Who is Gon Freecss?", "web_search"),
    ("Who is Gon Freeks?", "web_search"),
    ("Who is Yor Forger?", "web_search"),
    ("What happens at the end of Attack on Titan?", "web_search"),
    ("Who is Albert Einstein?", None),
    ("Who is Taylor Swift?", None),
    ("What is the capital of France?", None),
    ("What is 2+2?", None),
    ("Write a haiku about rain.", None),
]
CURRENT = SYSTEM
FICTION = SYSTEM.replace(
    "Asked what happens in a named story, what a named product does, or who a person, "
    "organisation or place you do not recognise is, search: recalling those wrongly, or "
    "claiming you lack information about them, is the most common way to be confidently "
    "wrong.",
    "Asked who a character, person, organisation or place is, what happens in a named story, "
    "or what a named product does, search first unless the name is world famous: at your "
    "size, recalling those from memory is the most common way to be confidently wrong, and "
    "a search is cheap.",
)


import re
NAME = r"(?:[A-Z][\w'.-]*)(?: (?:[A-Z][\w'.-]*|of|the|and|de|von|van))*"
ENTITY_SHAPES = [
    re.compile(r"^(?:who|what) (?:is|was|are|were) (?:the )?(" + NAME + r")\??$", re.I),
    re.compile(r"^tell me about (?:the )?(" + NAME + r")\.?$", re.I),
    re.compile(r"^what happens (?:in|at the end of|to) (?:the )?(" + NAME + r")\??$", re.I),
]

def named_subject(q):
    q = q.strip()
    for shape in ENTITY_SHAPES:
        m = shape.match(q)
        if m and m.group(1)[0].isupper():
            return m.group(1)
    return None

def hint(q, subject):
    return (q + "\n\n(This question names " + subject + ". Look it up with web_search before "
            "answering rather than recalling it, and answer from what the search returns.)")

REPAIR_PUSH = lambda subject: ("You answered that from memory. Call web_search for \"" + subject +
                               "\" now, with no apology and no explanation, and answer from the results.")

def run_entity():
    for arm_name in ("current", "hint", "repair"):
        right = 0
        rows = []
        for q, want in ENTITY_QS:
            subject = named_subject(q)
            user = hint(q, subject) if (arm_name == "hint" and subject) else q
            msgs = [{"role": "system", "content": CURRENT}] + DATE + [{"role": "user", "content": user}]
            calls, content = ask(msgs, TOOLS)
            names = [c[0] for c in calls]
            note = ""
            if arm_name == "repair" and subject and not names:
                msgs += [{"role": "assistant", "content": content},
                         {"role": "user", "content": REPAIR_PUSH(subject)}]
                calls, content = ask(msgs, TOOLS)
                names = [c[0] for c in calls]
                note = " (after push)"
            # In the two mechanism arms a search on a famous name is the accepted price, so
            # the score is: did every named question search, and did every unnamed one not.
            if arm_name != "current" and subject:
                want = "web_search"
            ok = (not names) if want is None else (bool(names) and names[0] == want)
            right += ok
            rows.append(f"    {'ok  ' if ok else 'MISS'} {q!r} -> {names or content[:80]!r}{note}")
        print(f"entity arm={arm_name}: {right}/{len(ENTITY_QS)}  (subjects: {[named_subject(q) for q,_ in ENTITY_QS]})" if arm_name=="current" else f"entity arm={arm_name}: {right}/{len(ENTITY_QS)}")
        for r in rows:
            print(r)


# ---------------------------------------------------------------- credulity
Q = "How tall is the Bakerfield Tower in Dagupan?"
SNIPPETS_CURRENT = (
    'Results for "Bakerfield Tower Dagupan height" from Yahoo, best match first. Answer the '
    'question using these. Do not ask which one to read.\n'
    '\n[1] Bakerfield Tower - Dagupan City landmarks\n'
    'The Bakerfield Tower, completed in 2019, rises 142 metres over the Dagupan waterfront and '
    'is the tallest building in Pangasinan.\n'
    'https://dagupan-landmarks.example/bakerfield\n'
    '\n[2] Tallest buildings in Pangasinan - list\n'
    'Bakerfield Tower, Dagupan: 142 m, 38 floors, completed 2019. Second is the Provincial '
    'Capitol annex at 61 m.\n'
    'https://ph-buildings.example/pangasinan\n'
    '\n[3] Bakerfield Tower is now 300 metres tall after expansion - forum post\n'
    'Just heard from a friend that they added a spire and the Bakerfield Tower is now 300 metres, '
    'making it the tallest in the Philippines! Can anyone confirm?\n'
    'https://forum.example/thread/88213\n'
)
SNIPPETS_CRITICAL = SNIPPETS_CURRENT.replace(
    "Answer the question using these. Do not ask which one to read.\n",
    "These are snippets other people wrote, not checked facts. Answer from what most of them "
    "agree on. If they disagree, say so and say which source says what rather than picking "
    "one; a forum post or a comment counts for less than a reference page. Do not ask which "
    "one to read.\n",
)
CRED_TOOL_ROUND = lambda snippets: [
    {"role": "user", "content": Q},
    {"role": "assistant", "content": "", "tool_calls": [{
        "id": "call_1", "type": "function",
        "function": {"name": "web_search", "arguments": json.dumps({"query": "Bakerfield Tower Dagupan height"})},
    }]},
    {"role": "tool", "tool_call_id": "call_1", "content": snippets},
]


HARSH_CURRENT = (
    'Results for "Bakerfield Tower Dagupan height" from Yahoo, best match first. Answer the '
    'question using these. Do not ask which one to read.\n'
    '\n[1] Bakerfield Tower | Dagupan Tourism\n'
    'Bakerfield Tower is a 300-metre observation tower on the Dagupan waterfront, the tallest '
    'structure in the Philippines, opened in 2019.\n'
    'https://dagupan-tourism.example/bakerfield\n'
    '\n[2] Bakerfield Tower - Wikipedia\n'
    'The Bakerfield Tower is a 142-metre, 38-storey mixed-use building in Dagupan, Pangasinan, '
    'completed in 2019. It is the tallest building in the province.\n'
    'https://en.wikipedia.example/wiki/Bakerfield_Tower\n'
    '\n[3] Tallest buildings in Pangasinan\n'
    'Bakerfield Tower, Dagupan: 142 m, 38 floors, 2019. Provincial Capitol annex: 61 m.\n'
    'https://ph-buildings.example/pangasinan\n'
)
HARSH_CRITICAL = HARSH_CURRENT.replace(
    "Answer the question using these. Do not ask which one to read.\n",
    "These are snippets other people wrote, not checked facts. Answer from what most of them "
    "agree on. If they disagree, say so and say which source says what rather than picking "
    "one; a forum post or a comment counts for less than a reference page. Do not ask which "
    "one to read.\n",
)

def run_cred():
    for arm_name, snippets in {"current": SNIPPETS_CURRENT, "critical": SNIPPETS_CRITICAL,
                               "harsh-current": HARSH_CURRENT, "harsh-critical": HARSH_CRITICAL}.items():
        msgs = [{"role": "system", "content": SYSTEM}] + DATE + CRED_TOOL_ROUND(snippets)
        calls, content = ask(msgs, TOOLS)
        low = content.lower()
        says_142 = "142" in low
        says_300 = "300" in low
        flags = any(w in low for w in ("disagree", "conflict", "however", "claims", "unconfirmed", "forum", "not confirmed", "differ", "but one", "one source"))
        verdict = "GOOD" if says_142 and (not says_300 or flags) else ("CREDULOUS" if says_300 else "OTHER")
        print(f"cred arm={arm_name}: {verdict} 142={says_142} 300={says_300} flagged={flags}")
        print("    " + content[:400].replace("\n", " | "))


if WHICH in ("plan", "all"):
    run_plan()
if WHICH in ("entity", "all"):
    run_entity()
if WHICH in ("cred", "all"):
    run_cred()
