"""Compare the three replay configurations, to see whether the two methodological
worries actually changed anything.

  replay_<M>.json       self-play, 4 server slots  (the original run)
  replay_<M>_np1.json   self-play, 1 server slot   (production has one conversation)
  tf_<M>.json           teacher-forced, 1 slot     (identical prompts for every model)
"""
import json, os, statistics

def load(p):
    return json.load(open(p)) if os.path.exists(p) else None

def summarise(rows):
    pt = sum(r['prompt_n'] for r in rows); pm = sum(r['prompt_ms'] for r in rows)/1000
    dt = sum(r['predicted_n'] for r in rows); dm = sum(r['predicted_ms'] for r in rows)/1000
    return dict(turns=len(rows), pt=pt, pm=pm, dt=dt, dm=dm,
                ptps=pt/pm if pm else 0, dtps=dt/dm if dm else 0, total=pm+dm)

CONFIGS = [("self-play, 4 slots", "replay_{}.json"),
           ("self-play, 1 slot",  "replay_{}_np1.json"),
           ("teacher-forced, 1 slot", "tf_{}.json")]

print(f"{'model':<8}{'configuration':<24}{'turns':>6}{'prefill tok':>12}"
      f"{'prefill s':>11}{'t/s':>8}{'decode s':>10}{'t/s':>8}{'total s':>9}")
data = {}
for model in ("Q4_K_M","Q8_0"):
    for label, pat in CONFIGS:
        rows = load(pat.format(model))
        if not rows: continue
        s = summarise(rows); data[(model,label)] = s
        print(f"{model:<8}{label:<24}{s['turns']:>6}{s['pt']:>12}{s['pm']:>11.1f}"
              f"{s['ptps']:>8.1f}{s['dm']:>10.1f}{s['dtps']:>8.1f}{s['total']:>9.1f}")

# Is the teacher-forced run actually paired?
a, b = load("tf_Q4_K_M.json"), load("tf_Q8_0.json")
if a and b:
    ka = {(r['label'].split(':')[1], r['turn']): r['prompt_n'] for r in a}
    kb = {(r['label'].split(':')[1], r['turn']): r['prompt_n'] for r in b}
    common = set(ka) & set(kb)
    same = sum(1 for k in common if ka[k] == kb[k])
    print(f"\nPairing check (teacher-forced): {same}/{len(common)} turns have identical "
          f"prompt_n across Q4_K_M and Q8_0")
    print(f"  prefill tokens: Q4_K_M {sum(ka[k] for k in common)}, "
          f"Q8_0 {sum(kb[k] for k in common)}")
