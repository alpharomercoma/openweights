"""Compose the measured pieces into a CPU-only vs NPU-prefill comparison.

Two of the three inputs are measurements on the phone:
  * per-turn prefill and decode times, replaying real conversations (llama-server)
  * absolute NPU matmul time for the model's real shape mix, per batch width

The third, the share of prefill time that is matmul, is not measured, so it is
swept as a band. Every table says which numbers are measured and which are not.
"""
import json, re, statistics, sys
from collections import defaultdict

COUNT = {(2048,8192):32, (8192,2048):16, (2048,6144):10, (2048,2048):22, (2048,512):12}

def load_sweep(path):
    by = defaultdict(dict)
    for line in open(path):
        if not line.startswith('RESULT'): continue
        d = dict(re.findall(r'(\w+)=([\-\d\.]+)', line)); p = line.split()
        by[(p[1], p[2], int(d['M']))][(int(d['K']), int(d['N']))] = float(d['median_ms'])
    out = {}
    for key, shapes in by.items():
        if len(shapes) == 5:
            out[key] = sum(shapes[s] * COUNT[s] for s in COUNT)
    return out

TOT = load_sweep('npu/width_sweep.txt')
NPU_W = sorted({m for (b,p,m) in TOT if b=='npu' and p=='int8'})

UBATCH = 512   # llama.cpp's physical batch: prefill is split into blocks of this

def _one_block(P):
    """NPU matmul time for a single microbatch of P (<= UBATCH) tokens."""
    if P <= 0: return 0.0
    lo = max([w for w in NPU_W if w <= P], default=NPU_W[0])
    hi = min([w for w in NPU_W if w >= P], default=NPU_W[-1])
    a, b = TOT[('npu','int8',lo)], TOT[('npu','int8',hi)]
    if P < NPU_W[0]:      # below the swept range, charge the smallest measured
        return a          # (the fixed per-op cost dominates here anyway)
    if lo == hi: return a
    return a + (b - a) * (P - lo) / (hi - lo)

def npu_ms(P):
    """Measured NPU matmul time for a prefill of P tokens.

    Split into UBATCH blocks, because llama.cpp does: a 1542-token prefill is
    three full blocks and a remainder, not one very wide invocation. Treating it
    as one wide call would credit the NPU with an efficiency it never sees.
    """
    full, rest = divmod(P, UBATCH)
    return full * _one_block(UBATCH) + _one_block(rest)

def analyse(tag, fractions=(0.7, 0.8, 0.9)):
    rows = json.load(open(f'replay_{tag}.json'))
    pre_ms = sum(r['prompt_ms'] for r in rows)
    dec_ms = sum(r['predicted_ms'] for r in rows)
    pre_tok = sum(r['prompt_n'] for r in rows)
    dec_tok = sum(r['predicted_n'] for r in rows)
    out = {'tag': tag, 'turns': len(rows),
           'prefill_tok': pre_tok, 'decode_tok': dec_tok,
           'prefill_s': pre_ms/1000, 'decode_s': dec_ms/1000,
           'total_s': (pre_ms+dec_ms)/1000,
           'prefill_tps': pre_tok/(pre_ms/1000), 'decode_tps': dec_tok/(dec_ms/1000),
           'projected': {}}
    for f in fractions:
        proj_pre = sum(npu_ms(r['prompt_n']) + (1-f)*r['prompt_ms'] for r in rows) / 1000
        out['projected'][f] = {
            'prefill_s': proj_pre,
            'total_s': proj_pre + dec_ms/1000,
            'speedup_prefill': (pre_ms/1000)/proj_pre,
            'speedup_total': ((pre_ms+dec_ms)/1000)/(proj_pre + dec_ms/1000),
        }
    return out

if __name__ == '__main__':
    tags = sys.argv[1:] or ['Q4_K_M','Q8_0','F16','BF16']
    res = []
    for t in tags:
        try: res.append(analyse(t))
        except FileNotFoundError: print(f"(no replay_{t}.json yet)")
    json.dump(res, open('analysis.json','w'), indent=1)

    print("\n=== A. MEASURED: CPU-only, replaying real conversations ===")
    print(f"{'model':>8} {'turns':>6} {'prefill tok':>12} {'decode tok':>11} "
          f"{'prefill s':>10} {'decode s':>9} {'total s':>8} {'pre t/s':>8} {'dec t/s':>8}")
    for r in res:
        print(f"{r['tag']:>8} {r['turns']:6d} {r['prefill_tok']:12d} {r['decode_tok']:11d} "
              f"{r['prefill_s']:10.1f} {r['decode_s']:9.1f} {r['total_s']:8.1f} "
              f"{r['prefill_tps']:8.1f} {r['decode_tps']:8.1f}")

    print("\n=== B. PROJECTED: NPU prefill (int8) + CPU decode ===")
    print("    f = share of prefill time that is matmul (unmeasured; swept)")
    print(f"{'model':>8} {'f':>5} {'prefill s':>10} {'total s':>9} "
          f"{'prefill x':>10} {'total x':>9}")
    for r in res:
        for f, p in r['projected'].items():
            print(f"{r['tag']:>8} {f:5.1f} {p['prefill_s']:10.1f} {p['total_s']:9.1f} "
                  f"{p['speedup_prefill']:10.2f} {p['speedup_total']:9.2f}")

    print("\n=== C. Amdahl ceiling: if prefill cost ZERO ===")
    for r in res:
        print(f"  {r['tag']:>8}: total {r['total_s']:.1f}s -> {r['decode_s']:.1f}s "
              f"= {r['total_s']/r['decode_s']:.2f}x at best")
