#!/bin/sh
# Every few minutes, pull the reports of finished benchmark matrices whose reports are
# not on disk, from the matrix ids in the runner logs. A belt for the driver's braces:
# the runner's own pull has missed a report that was in the bucket.
#   tools/eval/bench/sweep.sh   (runs until killed)
set -u
HERE=$(cd "$(dirname "$0")" && pwd); OUT="$HERE/../results"
PROJECT=$(gcloud config get-value project 2>/dev/null)
while true; do
  for log in "$OUT"/*bench-*.ftl.log; do
    [ -e "$log" ] || continue
    name=$(basename "$log" .ftl.log)         # e.g. elite-bench-executorch-Gemma3-ifeval
    prefix=${name%%bench-*}                  # elite-
    rest=${name#*bench-}                     # executorch-Gemma3-ifeval
    fam=$(echo "$rest" | cut -d- -f2- | sed 's/-[^-]*$//')   # Gemma3 (or Qwen3-1.7B-INT8)
    suffix=$(echo "${rest##*-}" | tr + '\n' | sort | paste -sd+ -)   # ifeval or bfcl+gsm8k, sorted like the report
    if ls "$OUT/$prefix"*"$fam"*".bench-$suffix.json" >/dev/null 2>&1; then continue; fi
    M=$(grep -o "matrix-[a-z0-9]*" "$log" | head -1); [ -n "$M" ] || continue
    TOKEN=$(gcloud auth print-access-token 2>/dev/null)
    STATE=$(curl -sS -H "Authorization: Bearer $TOKEN" "https://testing.googleapis.com/v1/projects/$PROJECT/testMatrices/$M" | python3 -c "import json,sys; print(json.load(sys.stdin).get('state',''))" 2>/dev/null)
    [ "$STATE" = FINISHED ] || continue
    "$HERE/pull_matrix.sh" "$M" "$prefix" 2>/dev/null | grep "results/" | sed "s|^|$(date +%H:%M) swept |"
  done
  sleep 300
done
