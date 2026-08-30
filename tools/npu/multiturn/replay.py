"""Replay real multi-turn conversations against llama-server and record timings.

Faithful to how the app actually behaves: the *user* turns come from the real
dataset, and the assistant turns are whatever the model itself produces, which is
what gets stored and sent back next turn. That is what makes the KV-cache reuse
measured here the same reuse production sees.
"""
import json, subprocess, sys, time

BASE = "http://127.0.0.1:8080"
MAX_NEW = 48          # bounded so a sweep finishes; decode rate is what matters

def post(path, payload, timeout=600):
    out = subprocess.run(
        ["curl", "-sS", "--max-time", str(timeout), f"{BASE}{path}",
         "-H", "Content-Type: application/json", "-d", json.dumps(payload)],
        capture_output=True, text=True)
    try:
        return json.loads(out.stdout)
    except Exception:
        return {"_error": out.stdout[:200] or out.stderr[:200]}

def erase_cache():
    subprocess.run(["curl", "-sS", "--max-time", "30", "-X", "POST",
                    f"{BASE}/slots/0?action=erase"], capture_output=True, text=True)

def replay(conv, label):
    """One conversation, turn by turn. Returns a row per turn."""
    erase_cache()
    history, rows = [], []
    user_turns = [m["content"] for m in conv["messages"] if m["role"] == "user"]
    for i, text in enumerate(user_turns):
        history.append({"role": "user", "content": text})
        r = post("/v1/chat/completions",
                 {"messages": history, "max_tokens": MAX_NEW, "temperature": 0})
        if "_error" in r or "choices" not in r or "timings" not in r:
            # One retry: a warm-up race or a dropped connection should not cost
            # the whole conversation, and a real failure will repeat.
            time.sleep(2)
            r = post("/v1/chat/completions",
                     {"messages": history, "max_tokens": MAX_NEW, "temperature": 0})
            if "_error" in r or "choices" not in r or "timings" not in r:
                return rows, (r.get("_error") or f"keys={sorted(r.keys())}")[:160]
        reply = r["choices"][0]["message"]["content"]
        t = r.get("timings", {})
        rows.append({
            "label": label, "source": conv["source"], "turn": i + 1,
            "prompt_n": t.get("prompt_n", 0), "cache_n": t.get("cache_n", 0),
            "prompt_ms": t.get("prompt_ms", 0.0),
            "predicted_n": t.get("predicted_n", 0),
            "predicted_ms": t.get("predicted_ms", 0.0),
            "reply_chars": len(reply),
            "reply_head": reply[:160],
        })
        history.append({"role": "assistant", "content": reply})
    return rows, None

if __name__ == "__main__":
    tag = sys.argv[1]                       # precision label for the output file
    n_conv = int(sys.argv[2]) if len(sys.argv) > 2 else 30
    convs = json.load(open("conversations.json"))
    # A balanced sample across sources, deterministic so runs are comparable.
    picked, per_source = [], {}
    for c in convs:
        k = c["source"]
        if per_source.get(k, 0) < n_conv // 3 + 1:
            per_source[k] = per_source.get(k, 0) + 1
            picked.append(c)
        if len(picked) >= n_conv: break

    all_rows, failures, started = [], 0, time.time()
    for j, c in enumerate(picked):
        rows, err = replay(c, f"{tag}:{j}")
        if err:
            failures += 1
            print(f"  conv {j} ({c['source']}) failed: {err}", flush=True)
        all_rows += rows
        if (j + 1) % 5 == 0:
            print(f"  {j+1}/{len(picked)} conversations, {len(all_rows)} turns, "
                  f"{time.time()-started:.0f}s", flush=True)
    json.dump(all_rows, open(f"replay_{tag}.json", "w"))
    print(f"{tag}: {len(all_rows)} turns from {len(picked)} conversations, "
          f"{failures} failed, {time.time()-started:.0f}s")
