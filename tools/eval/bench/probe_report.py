"""Render the fixed-prompt speed probe (ExecuTorchOnDeviceTest#throughputReport via matrix/run.sh)
as one table: per file, median over all turns of prefill and decode rate, and resident memory
after load. Paired by construction: same 929-token prompt, same 160-token cap, phone cooled
and awake before each load, files interleaved across passes.

    python3 tools/eval/bench/probe_report.py ~/ow-models/etexport/matrix/results-windows.log
"""
import re, statistics, sys
from collections import defaultdict
from pathlib import Path

rows = defaultdict(lambda: {"pre": [], "dec": [], "load": [], "ttft": []})
for line in Path(sys.argv[1]).read_text().splitlines():
    m = re.match(r"(\S+) .*matrix (load|turn \d): (.*)", line)
    if not m:
        continue
    label, kind, rest = m.groups()
    kv = dict(re.findall(r"(\w+)=([\d.]+)", rest))
    r = rows[label]
    if kind == "load":
        r["load"].append(int(kv["rssMb"]))
    else:
        r["pre"].append(float(kv["prefillTokS"])); r["dec"].append(float(kv["decodeTokS"]))
        r["ttft"].append(int(kv["prefillMs"]))

def order(label):
    fam = 0 if "1.2B" in label else 1 if "2.6B" in label else 2
    return (fam, int(re.search(r"-(\d+)k$", label).group(1)))

out = ["| File | Turns | Prefill tok/s (median, min to max) | Decode tok/s (median, min to max) | Prefill ms, 929 tokens | Resident after load MB |",
       "|---|---|---|---|---|---|"]
for label in sorted(rows, key=order):
    r = rows[label]
    if not r["pre"]:
        out.append(f"| {label} | 0 | did not load | | | {r['load'] and statistics.median(r['load'])} |"); continue
    out.append(f"| {label} | {len(r['pre'])} | {statistics.median(r['pre']):.0f} ({min(r['pre']):.0f} to {max(r['pre']):.0f}) | "
               f"{statistics.median(r['dec']):.1f} ({min(r['dec']):.1f} to {max(r['dec']):.1f}) | {statistics.median(r['ttft']):.0f} | "
               f"{statistics.median(r['load']):.0f} |")
print("\n".join(out))

# Paired differences: within each pass, each file's per-turn rates against the same model's
# smallest window in that pass (turn k against turn k), so order and thermal drift between
# passes cancel as far as the interleaving allows. Reported as the median and range of the
# per-turn ratios.
passes = defaultdict(lambda: defaultdict(lambda: {"pre": [], "dec": []}))
seen = defaultdict(int)
for line in Path(sys.argv[1]).read_text().splitlines():
    m = re.match(r"(\S+) .*matrix turn (\d): (.*)", line)
    if not m:
        continue
    label, turn, rest = m.groups()
    kv = dict(re.findall(r"(\w+)=([\d.]+)", rest))
    if turn == "0":
        seen[label] += 1
    p = passes[seen[label]][label]
    p["pre"].append(float(kv["prefillTokS"])); p["dec"].append(float(kv["decodeTokS"]))

def base_of(label):
    return re.sub(r"-\d+k$", "", label)

ratios = defaultdict(lambda: {"pre": [], "dec": []})
for pas, files in passes.items():
    smallest = {}
    for label in files:
        b = base_of(label)
        w = int(re.search(r"-(\d+)k$", label).group(1))
        if b not in smallest or w < smallest[b][0]:
            smallest[b] = (w, label)
    for label, r in files.items():
        ref = files[smallest[base_of(label)][1]]
        for k in ("pre", "dec"):
            for a, b in zip(r[k], ref[k]):
                if b:
                    ratios[label][k].append(a / b)

print()
print("| File | Prefill vs smallest window (median ratio, min to max) | Decode vs smallest window | Paired turns |")
print("|---|---|---|---|")
for label in sorted(ratios, key=order):
    r = ratios[label]
    if not r["pre"]:
        continue
    print(f"| {label} | {statistics.median(r['pre']):.3f} ({min(r['pre']):.3f} to {max(r['pre']):.3f}) | "
          f"{statistics.median(r['dec']):.3f} ({min(r['dec']):.3f} to {max(r['dec']):.3f}) | {len(r['pre'])} |")
