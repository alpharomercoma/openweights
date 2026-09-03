#!/bin/sh
# The public benchmarks on the Test Lab phones: every family, both engines, all three
# sets, as one matrix per (phone, engine, family, set group). Idempotent: a job whose
# report is already in tools/eval/results/ is skipped, so a rerun after a failed or
# time-boxed matrix picks up only what is missing.
#
#   PHONES="e2s:36:exynos-" tools/eval/bench/run_cloud.sh [parallel-jobs]
#
# Test Lab runs one matrix per device model at a time and queues the rest, so the useful
# shape is one driver per phone with two in flight (one running, one already uploaded).
#
# Set groups: gsm8k with bfcl (short calls beside medium answers) and ifeval alone
# (most replies run to the 640-token cap). Gemma has no tool syntax, so its bfcl half
# is not launched.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
OUT="$HERE/../results"
PAR=${1:-2}
JOBS=$(mktemp)

PHONES=${PHONES:-"mustang:36:tensor- e2s:36:exynos- pa3q:36:elite-"}
ET="Gemma3 lfm llama-3.2 Qwen3-1.7B-INT8 SmolLM3-3B"
GG="gemma-3 LFM2.5 Llama-3.2-3B Qwen3-1.7B-Q8 SmolLM3-Q4"

for phone in $PHONES; do
  dev=${phone%%:*}; rest=${phone#*:}; ver=${rest%%:*}; prefix=${rest#*:}
  for engine in bench-executorch bench-llamacpp; do
    [ "$engine" = bench-executorch ] && fams=$ET || fams=$GG
    for fam in $fams; do
      case "$fam" in *emma*) groups="gsm8k ifeval" ;; *) groups="gsm8k,bfcl ifeval" ;; esac
      for sets in $groups; do
        suffix=$(echo "$sets" | tr , '\n' | sort | paste -sd+ -)
        if ls "$OUT/$prefix"*"$fam"*".bench-$suffix.json" >/dev/null 2>&1; then continue; fi
        echo "$dev $ver $prefix $engine $fam $sets" >> "$JOBS"
      done
    done
  done
done

echo "== $(wc -l < "$JOBS" | tr -d ' ') matrices to run, $PAR at a time"
echo "== building the test APK once"
(cd "$ROOT" && ./gradlew :core:engine:assembleAcceleratedDebugAndroidTest --console=plain -q)

# One line per job; each runs the Test Lab script with the build skipped and its own
# log named by the runner. xargs keeps $PAR in flight and Test Lab queues the rest.
xargs -P "$PAR" -L 1 sh -c '
  dev=$0; ver=$1; prefix=$2; engine=$3; fam=$4; sets=$5
  echo "-> $prefix $engine $fam [$sets] $(date +%H:%M)"
  suffix=$(echo "$sets" | tr , "\n" | sort | paste -sd+ -)
  SKIP_BUILD=1 BENCH_MODEL=$fam BENCH_SETS=$sets '"$ROOT"'/tools/eval/run_matrix_ftl.sh "$dev" "$ver" "$prefix" "$engine" >/dev/null 2>&1 || true
  # The report on disk is the only success there is; gcloud exits 0 on a matrix that
  # failed validation.
  if ls '"$OUT"'/"$prefix"*"$fam"*".bench-$suffix.json" >/dev/null 2>&1; then
    echo "ok $prefix $engine $fam [$sets] $(date +%H:%M)"
  else
    echo "FAILED $prefix $engine $fam [$sets] $(date +%H:%M)"
  fi
' < "$JOBS"
rm -f "$JOBS"
echo "== all matrices returned $(date +%H:%M)"
