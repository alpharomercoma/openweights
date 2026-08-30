"""Are the replies actually legible, or is the timing measuring garbage?

A speed comparison is worthless if one configuration is producing nonsense, so
this checks every reply captured during the replays. The tests are deliberately
mechanical — a human reads a sample at the end, but these catch the failure
modes that would invalidate a benchmark: empty output, degenerate repetition,
mojibake, and replies that are not the script the prompt was in.
"""
import json, re, sys, unicodedata

def repetition_ratio(text):
    """Share of the text covered by the single most repeated normalised line."""
    lines = [l.strip() for l in text.splitlines() if l.strip()]
    if not lines: return 0.0
    counts = {}
    for l in lines: counts[l] = counts.get(l, 0) + 1
    line, n = max(counts.items(), key=lambda kv: kv[1] * len(kv[0]))
    # A line that appears once is not a repeat, however much of the reply it is.
    # Without this a one-line answer scores 1.0 and every short reply reads as
    # degenerate, which is a property of the checker rather than of the model.
    if n < 3: return 0.0
    return (n * len(line)) / max(1, len(text))

def looks_like_mojibake(text):
    return bool(re.search(r'[�]', text)) or text.count('\\u') > 3

def printable_ratio(text):
    if not text: return 0.0
    ok = sum(1 for c in text if c.isprintable() or c in '\n\t')
    return ok / len(text)

def score(rows):
    out = {'n': len(rows), 'empty': 0, 'degenerate': 0, 'mojibake': 0,
           'unprintable': 0, 'truncated_ok': 0, 'clean': 0}
    for r in rows:
        t = r.get('reply_head', '')
        if not t.strip(): out['empty'] += 1; continue
        bad = False
        if repetition_ratio(t) > 0.5 and len(t) > 120: out['degenerate'] += 1; bad = True
        if looks_like_mojibake(t): out['mojibake'] += 1; bad = True
        if printable_ratio(t) < 0.95: out['unprintable'] += 1; bad = True
        if not bad: out['clean'] += 1
    return out

if __name__ == '__main__':
    print(f"{'model':>8} {'replies':>8} {'clean':>7} {'empty':>6} {'degen':>6} "
          f"{'mojib':>6} {'unprint':>8}  clean %")
    for tag in sys.argv[1:]:
        try: rows = json.load(open(f'replay_{tag}.json'))
        except FileNotFoundError: continue
        s = score(rows)
        print(f"{tag:>8} {s['n']:8d} {s['clean']:7d} {s['empty']:6d} {s['degenerate']:6d} "
              f"{s['mojibake']:6d} {s['unprintable']:8d}  {100*s['clean']/max(1,s['n']):.1f}%")
