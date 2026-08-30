"""Teacher-forced replay: every model sees byte-identical history.

The self-play replay is the right shape for predicting product behaviour, and
the wrong shape for comparing precisions: each model writes different replies,
so the prompts diverge and the runs are not paired. Here the history is the
dataset's own assistant turns, so the prompt token IDs are identical for every
model and the only thing that varies is the arithmetic.
"""
import json, subprocess, sys, time
BASE = "http://127.0.0.1:8080"; MAX_NEW = 48

def post(path, payload, timeout=600):
    o = subprocess.run(["curl","-sS","--max-time",str(timeout),f"{BASE}{path}",
                        "-H","Content-Type: application/json","-d",json.dumps(payload)],
                       capture_output=True, text=True)
    try: return json.loads(o.stdout)
    except Exception: return {"_error": (o.stdout or o.stderr)[:200]}

def erase(): subprocess.run(["curl","-sS","--max-time","30","-X","POST",
                             f"{BASE}/slots/0?action=erase"], capture_output=True)

def replay(conv, label):
    erase(); rows = []; history = []
    msgs = conv["messages"]
    for i, m in enumerate(msgs):
        if m["role"] != "user": continue
        history.append({"role": "user", "content": m["content"]})
        r = post("/v1/chat/completions",
                 {"messages": history, "max_tokens": MAX_NEW, "temperature": 0})
        if "timings" not in r:
            time.sleep(2)
            r = post("/v1/chat/completions",
                     {"messages": history, "max_tokens": MAX_NEW, "temperature": 0})
            if "timings" not in r: return rows, str(r)[:150]
        t = r["timings"]
        rows.append({"label": label, "source": conv["source"], "turn": len(rows)+1,
                     "prompt_n": t.get("prompt_n",0), "cache_n": t.get("cache_n",0),
                     "prompt_ms": t.get("prompt_ms",0.0),
                     "predicted_n": t.get("predicted_n",0),
                     "predicted_ms": t.get("predicted_ms",0.0),
                     "reply_chars": len(r["choices"][0]["message"]["content"]),
                     "reply_head": r["choices"][0]["message"]["content"][:200]})
        # the DATASET's assistant turn becomes history, not the model's own
        nxt = msgs[i+1] if i+1 < len(msgs) else None
        if nxt and nxt["role"] == "assistant":
            history.append({"role": "assistant", "content": nxt["content"]})
        else:
            break
    return rows, None

if __name__ == "__main__":
    tag = sys.argv[1]; n = int(sys.argv[2]) if len(sys.argv) > 2 else 45
    convs = json.load(open("conversations.json"))
    sel, ps = [], {}
    for c in convs:
        k = c["source"]
        if ps.get(k,0) < n//3+1: ps[k] = ps.get(k,0)+1; sel.append(c)
        if len(sel) >= n: break
    out, fails, t0 = [], 0, time.time()
    for j,c in enumerate(sel):
        rows, err = replay(c, f"{tag}:{j}")
        if err: fails += 1; print(f"  conv{j} failed: {err}", flush=True)
        out += rows
        if (j+1) % 10 == 0: print(f"  {j+1}/{len(sel)}  {len(out)} turns  {time.time()-t0:.0f}s", flush=True)
    json.dump(out, open(f"tf_{tag}.json","w"))
    print(f"{tag}: {len(out)} turns, {fails} failed, {time.time()-t0:.0f}s")
