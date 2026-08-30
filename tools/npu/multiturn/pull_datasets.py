"""Pull real multi-turn conversations from four public datasets.

Nothing here is generated. Each conversation is a real exchange somebody had
with a deployed assistant, or in MT-Bench's case the standard multi-turn
benchmark the field actually uses.
"""
import json, urllib.parse, time, subprocess

API = "https://datasets-server.huggingface.co/rows"

def rows(dataset, config, split, offset, length):
    # curl rather than urllib: this Python has no CA bundle installed, and the
    # failure is a certificate error that looks exactly like an empty dataset.
    q = urllib.parse.urlencode({"dataset": dataset, "config": config,
                                "split": split, "offset": offset, "length": length})
    for attempt in range(4):
        out = subprocess.run(["curl", "-sS", "--max-time", "90", f"{API}?{q}"],
                             capture_output=True, text=True)
        try:
            return json.loads(out.stdout).get("rows", [])
        except Exception:
            time.sleep(3 * (attempt + 1))
    return []

def norm(role):
    r = (role or "").lower()
    if r in ("human", "user", "prompter"): return "user"
    if r in ("gpt", "assistant", "bot", "chatgpt"): return "assistant"
    return None

def from_wildchat(row):
    out = []
    for m in row.get("conversation") or []:
        r = norm(m.get("role"))
        if r and m.get("content"): out.append({"role": r, "content": m["content"]})
    return out if row.get("language") == "English" else []

def from_sharegpt(row):
    out = []
    for m in row.get("conversations") or []:
        r = norm(m.get("from"))
        if r and m.get("value"): out.append({"role": r, "content": m["value"]})
    return out

def from_anteater(row):
    out = []
    for m in row.get("conversations") or []:
        r = norm(m.get("from"))
        if r and m.get("value"): out.append({"role": r, "content": m["value"]})
    return out

def from_mtbench(row):
    # MT-Bench ships the user turns only; it is a prompt set, so the assistant
    # side is whatever the model under test says. Kept as user turns and the
    # replay fills the rest in.
    return [{"role": "user", "content": p} for p in (row.get("prompt") or [])]

SOURCES = [
    ("WildChat-1M",     "allenai/WildChat-1M",                  "default", "train",     from_wildchat, 400),
    ("ShareGPT52K",     "RyokoAI/ShareGPT52K",                  "default", "train",     from_sharegpt, 400),
    ("Daring-Anteater", "nvidia/Daring-Anteater",               "default", "train",     from_anteater, 400),
    ("MT-Bench",        "HuggingFaceH4/mt_bench_prompts",       "default", "train",     from_mtbench,  100),
]

MIN_USER_TURNS = 3
MAX_CHARS = 24000     # keep a conversation inside a 4k-token-ish working set
MIN_CHARS = 300

kept = []
for name, ds, cfg, split, fn, want in SOURCES:
    got, offset = 0, 0
    while got < want and offset < want * 6:
        batch = rows(ds, cfg, split, offset, 100)
        if not batch: break
        offset += len(batch)
        for r in batch:
            msgs = fn(r["row"])
            users = sum(1 for m in msgs if m["role"] == "user")
            total = sum(len(m["content"]) for m in msgs)
            # MT-Bench is two turns by construction; everything else must be
            # genuinely multi-turn or it says nothing about cache reuse.
            need = 2 if name == "MT-Bench" else MIN_USER_TURNS
            if users >= need and MIN_CHARS <= total <= MAX_CHARS:
                kept.append({"source": name, "messages": msgs,
                             "user_turns": users, "chars": total})
                got += 1
                if got >= want: break
    print(f"{name}: kept {got}")

json.dump(kept, open("conversations.json", "w"))
print("total kept:", len(kept))
from collections import Counter
print(Counter(c["source"] for c in kept))
