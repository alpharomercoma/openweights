#!/bin/sh
# The window matrix on the Test Lab phones: our own LFM2.5 exports, ExecuTorch only, each
# file loaded at its own exported window, one matrix per (phone, variant, set group).
# Idempotent like run_cloud.sh: a variant whose report is already in tools/eval/results/
# for that phone is skipped.
#
#   VARIANTS="LFM2.5-1.2B-Instruct-8da4w-4k LFM2.5-1.2B-Instruct-8da4w-32k" \
#   PHONES="mustang:36:tensor- e2s:36:exynos- pa3q:36:elite-" tools/eval/bench/run_cloud_windows.sh [parallel]
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
OUT="$HERE/../results"
PAR=${1:-3}
JOBS=$(mktemp)
PHONES=${PHONES:-"mustang:36:tensor- e2s:36:exynos- pa3q:36:elite-"}
VARIANTS=${VARIANTS:?variant file stems, space separated}

for phone in $PHONES; do
  dev=${phone%%:*}; rest=${phone#*:}; ver=${rest%%:*}; prefix=${rest#*:}
  for v in $VARIANTS; do
    for sets in gsm8k,bfcl ifeval; do
      suffix=$(echo "$sets" | tr , '\n' | sort | paste -sd+ -)
      if ls "$OUT/$prefix$v.bench-$suffix.json" >/dev/null 2>&1 \
         && ! grep -q '"budget_exhausted": true' "$OUT/$prefix$v.bench-$suffix.json"; then continue; fi
      echo "$dev $ver $prefix $v $sets" >> "$JOBS"
    done
  done
done
# Test Lab runs one matrix per device model at a time, so jobs are interleaved by phone:
# with three in flight, each phone has one running rather than one phone holding a queue.
SORTED=$(mktemp)
python3 - "$JOBS" > "$SORTED" <<'PY'
import sys
from collections import defaultdict
jobs = [l.rstrip("\n") for l in open(sys.argv[1]) if l.strip()]
by = defaultdict(list)
for j in jobs:
    by[j.split()[0]].append(j)
out = []
while any(by.values()):
    for dev in list(by):
        if by[dev]:
            out.append(by[dev].pop(0))
print("\n".join(out))
PY
mv "$SORTED" "$JOBS"
echo "== $(wc -l < "$JOBS" | tr -d ' ') matrices to run, $PAR at a time"
[ -n "${SKIP_BUILD:-}" ] || (cd "$ROOT" && ./gradlew :core:engine:assembleDebugAndroidTest --console=plain -q)
xargs -P "$PAR" -L 1 sh -c '
  dev=$0; ver=$1; prefix=$2; v=$3; sets=$4
  echo "-> $prefix $v [$sets] $(date +%H:%M)"
  suffix=$(echo "$sets" | tr , "\n" | sort | paste -sd+ -)
  SKIP_BUILD=1 BENCH_MODEL=$v BENCH_SETS=$sets BENCH_CONTEXT=0 '"$ROOT"'/tools/eval/run_matrix_ftl.sh "$dev" "$ver" "$prefix" bench-executorch >/dev/null 2>&1 || true
  if ls '"$OUT"'/"$prefix$v.bench-$suffix.json" >/dev/null 2>&1; then
    echo "ok $prefix $v [$sets] $(date +%H:%M)"
  else
    echo "FAILED $prefix $v [$sets] $(date +%H:%M)"
  fi
' < "$JOBS"
rm -f "$JOBS"
echo "== all matrices returned $(date +%H:%M)"
