#!/bin/sh
# The window-matrix reruns on the Test Lab phones: one set per launch, at a reply cap the
# 2.6B can finish under, continued with `skip` when the 38-minute budget cuts a set short.
# Each phone works through its own jobs in order; the phones run in parallel.
#
#   JOBS file lines: <device> <version> <prefix> <variant> <set> <cap-or-0>
#   tools/eval/bench/run_cloud_rerun.sh jobs.txt
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
OUT="$HERE/../results"
JOBS=${1:?jobs file}

run_phone() {
  dev=$1
  grep "^$dev " "$JOBS" | while read -r d ver prefix v set cap; do
    tag="$v.bench-$set${cap:+-cap$cap}"; [ "$cap" = 0 ] && tag="$v.bench-$set"
    skip=0
    while :; do
      name="$OUT/$prefix$tag$( [ $skip -gt 0 ] && echo "@$skip" ).json"
      if [ -f "$name" ]; then
        if grep -q '"budget_exhausted": true' "$name"; then
          done_n=$(python3 -c "import json,sys;print(len(json.load(open(sys.argv[1]))['cases']))" "$name")
          skip=$((skip + done_n)); [ $skip -ge 30 ] && break; continue
        fi
        break
      fi
      echo "-> $prefix $v [$set cap=$cap skip=$skip] $(date +%H:%M)"
      SKIP_BUILD=1 BENCH_MODEL=$v BENCH_SETS=$(echo "$set" | tr + ,) BENCH_CONTEXT=0 BENCH_SKIP=$( [ $skip -gt 0 ] && echo $skip ) \
        BENCH_CAP=$( [ "$cap" != 0 ] && echo "$cap" ) \
        "$ROOT/tools/eval/run_matrix_ftl.sh" "$d" "$ver" "$prefix" bench-executorch >/dev/null 2>&1 || true
      if [ ! -f "$name" ]; then echo "FAILED $prefix $v [$set skip=$skip] $(date +%H:%M)"; break; fi
      echo "ok $prefix $v [$set skip=$skip] $(date +%H:%M): $(python3 -c "import json,sys;r=json.load(open(sys.argv[1]));print(len(r['cases']),'prompts, exhausted' if r.get('budget_exhausted') else 'prompts, complete')" "$name")"
    done
  done
}
for dev in $(awk '{print $1}' "$JOBS" | sort -u); do run_phone "$dev" & done
wait
echo "== all reruns returned $(date +%H:%M)"
