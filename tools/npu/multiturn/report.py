"""Produce every table for the NPU-prefill question from the measured data."""
import json, re, statistics, os
from collections import defaultdict
from analyse import TOT, npu_ms, NPU_W, COUNT

def have(p): return os.path.exists(p)

PRECS = [t for t in ("Q4_K_M","Q8_0","F16","BF16") if have(f"replay_{t}.json")]

def load(tag, prefix="replay"): return json.load(open(f"{prefix}_{tag}.json"))

print("### Table 1 — Corpus: real multi-turn conversations\n")
convs=json.load(open("conversations.json"))
rows=load(PRECS[0])
used={r['label'].split(':')[1] for r in rows}
bysrc=defaultdict(int)
for r in rows: bysrc[r['source']]+=1
print(f"{'dataset':<18}{'turns replayed':>15}")
for s,n in sorted(bysrc.items()): print(f"{s:<18}{n:>15}")
print(f"{'TOTAL':<18}{len(rows):>15}   from {len(used)} conversations\n")

p=[r['prompt_n'] for r in rows]
q=statistics.quantiles(p,n=100)
print("Prefill width per turn (tokens actually processed after KV-cache reuse):")
print(f"  min {min(p)}   p25 {q[24]:.0f}   median {statistics.median(p):.0f}   "
      f"p75 {q[74]:.0f}   p90 {q[89]:.0f}   max {max(p)}   mean {statistics.mean(p):.0f}")
tot=sum(p)
print("\n  width bucket    turns    % turns    % of all prefill tokens")
for lo,hi in [(0,16),(16,32),(32,64),(64,128),(128,256),(256,512),(512,10**9)]:
    sel=[x for x in p if lo<=x<hi]
    if not sel: continue
    hs = '∞' if hi>10**8 else str(hi)
    print(f"  {lo:>5}-{hs:<6}{len(sel):>8}{100*len(sel)/len(p):>10.1f}%{100*sum(sel)/tot:>22.1f}%")

print("\n\n### Table 2 — MEASURED: CPU only, whole corpus replayed\n")
print(f"{'model':<9}{'turns':>6}{'prefill tok':>12}{'prefill s':>11}{'t/s':>8}"
      f"{'decode tok':>12}{'decode s':>10}{'t/s':>8}{'total s':>9}{'prefill share':>14}")
meas={}
for t in PRECS:
    r=load(t)
    pt=sum(x['prompt_n'] for x in r); pm=sum(x['prompt_ms'] for x in r)/1000
    dt=sum(x['predicted_n'] for x in r); dm=sum(x['predicted_ms'] for x in r)/1000
    meas[t]=dict(turns=len(r),pt=pt,pm=pm,dt=dt,dm=dm)
    print(f"{t:<9}{len(r):>6}{pt:>12}{pm:>11.1f}{pt/pm:>8.1f}{dt:>12}{dm:>10.1f}"
          f"{dt/dm:>8.1f}{pm+dm:>9.1f}{100*pm/(pm+dm):>13.1f}%")

print("\n\n### Table 3 — MEASURED: matmul time, real LFM2.5-1.2B shape mix (ms/forward pass)\n")
cols=[('npu','int8'),('cpu','Q8_0'),('cpu','F16'),('cpu','BF16'),('cpu','Q4_K')]
print("{:>6}".format('width')+"".join(f"{b+' '+p:>13}" for b,p in cols)+f"{'NPU x vs Q8_0':>15}")
for M in sorted({k[2] for k in TOT}):
    line=f"{M:6d}"; n=TOT.get(('npu','int8',M))
    for b,pp in cols:
        v=TOT.get((b,pp,M)); line+=f"{v:13.1f}" if v else f"{'—':>13}"
    c=TOT.get(('cpu','Q8_0',M))
    line += f"{c/n:15.2f}" if (n and c) else f"{'—':>15}"
    print(line)

print("\n\n### Table 4 — PROJECTED: NPU prefill (int8) + CPU decode\n")
print("f = fraction of CPU prefill time that is matmul. Not measured; swept.")
print("Projection = NPU matmul time (measured, chunked at n_ubatch=512) + (1-f) x measured CPU prefill.")
print("It OMITS activation quantise/dequantise and CPU<->NPU copies, so it is optimistic.\n")
print(f"{'model':<9}{'f':>5}{'prefill s':>11}{'total s':>10}{'prefill x':>11}{'TOTAL x':>10}")
for t in PRECS:
    r=load(t); m=meas[t]
    for f in (0.7,0.8,0.9):
        pj=sum(npu_ms(x['prompt_n'])+(1-f)*x['prompt_ms'] for x in r)/1000
        print(f"{t:<9}{f:>5.1f}{pj:>11.1f}{pj+m['dm']:>10.1f}"
              f"{m['pm']/pj:>11.2f}{(m['pm']+m['dm'])/(pj+m['dm']):>10.2f}")

print("\n\n### Table 5 — Amdahl ceiling (prefill made FREE)\n")
print(f"{'model':<9}{'total s':>10}{'decode-only s':>15}{'best possible x':>17}")
for t in PRECS:
    m=meas[t]; print(f"{t:<9}{m['pm']+m['dm']:>10.1f}{m['dm']:>15.1f}{(m['pm']+m['dm'])/m['dm']:>17.2f}")

if any(have(f"tf_{t}.json") for t in PRECS):
    print("\n\n### Table 6 — PAIRED (teacher-forced): identical prompts for every model\n")
    print(f"{'model':<9}{'turns':>6}{'prefill tok':>12}{'prefill s':>11}{'t/s':>8}"
          f"{'decode s':>10}{'t/s':>8}{'total s':>9}")
    for t in PRECS:
        if not have(f"tf_{t}.json"): continue
        r=load(t,"tf")
        pt=sum(x['prompt_n'] for x in r); pm=sum(x['prompt_ms'] for x in r)/1000
        dt=sum(x['predicted_n'] for x in r); dm=sum(x['predicted_ms'] for x in r)/1000
        print(f"{t:<9}{len(r):>6}{pt:>12}{pm:>11.1f}{pt/pm:>8.1f}{dm:>10.1f}{dt/dm:>8.1f}{pm+dm:>9.1f}")
