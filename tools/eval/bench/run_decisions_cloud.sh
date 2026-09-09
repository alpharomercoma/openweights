#!/bin/sh
# The retrieve-or-answer suite on the Test Lab phones: every model, every arm, in chunks
# that fit a forty-five minute matrix, one driver per phone with two in flight (Test Lab
# runs one matrix per device model at a time and queues the next). Idempotent: a chunk
# whose rows are already in tools/eval/results/decisions/ is skipped, so a rerun after a
# failed matrix picks up only what is missing.
#
#   PHONES="mustang:36:tensor- e2s:36:exynos- pa3q:36:elite-" tools/eval/bench/run_decisions_cloud.sh [parallel-jobs]
#
# The full-catalogue arm reads two and a half thousand prompt tokens a row and takes
# thirty seconds a row on the Dimensity, so it goes up in chunks of forty; the others in
# chunks of eighty. The 2 x 2 the reviewers asked for goes first, then the route, then the
# no-tools baseline, so a driver stopped early still answers the first question.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
OUT="$HERE/../results/decisions"
PAR=${1:-2}
JOBS=$(mktemp)
PHONES=${PHONES:-"mustang:36:tensor- e2s:36:exynos- pa3q:36:elite-"}
MODELS=${MODELS:-"LFM2.5-1.2B-Instruct-8da4w-32k.pte LFM2.5-1.2B-Instruct-Q4_K_M.gguf Qwen3-1.7B-Q8_0.gguf"}
ARMS=${ARMS:-"driven-search intent-search bare driven-full intent-full"}
TOTAL=$(python3 -c "import json;print(len(json.load(open('$HERE/decisions.json'))['rows']))")

done_rows() {  # how many rows a (prefix, model, arm) file already holds
  f="$OUT/$1decisions-${2%.*}-$3.jsonl"
  [ -f "$f" ] && grep -vc '"header": true' "$f" || echo 0
}

for phone in $PHONES; do
  dev=${phone%%:*}; rest=${phone#*:}; ver=${rest%%:*}; prefix=${rest#*:}
  for arm in $ARMS; do
    case "$arm" in *-full) chunk=40 ;; *) chunk=80 ;; esac
    for model in $MODELS; do
      have=$(done_rows "$prefix" "$model" "$arm")
      from=0
      while [ "$from" -lt "$TOTAL" ]; do
        # Chunks are run in order and each appends, so the rows already held say which
        # chunk is next; a partial chunk is rerun whole and its duplicate ids are
        # dropped by the grader.
        if [ "$from" -ge "$have" ]; then echo "$dev $ver $prefix $model $arm $from $chunk" >> "$JOBS"; fi
        from=$((from + chunk))
      done
    done
  done
done

echo "== $(wc -l < "$JOBS" | tr -d ' ') matrices to run, $PAR at a time"
if [ -z "${SKIP_BUILD:-}" ]; then
  echo "== building the app and its test APK once"
  (cd "$ROOT" && ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --console=plain -q)
fi
xargs -P "$PAR" -L 1 sh -c '
  dev=$0; ver=$1; prefix=$2; model=$3; arm=$4; from=$5; chunk=$6
  echo "-> $prefix $model $arm @$from+$chunk $(date +%H:%M)"
  if MODELS=$model ARMS=$arm FROM=$from ROWS=$chunk '"$HERE"'/run_decisions_ftl.sh "$dev" "$ver" "$prefix" >/dev/null 2>&1; then
    echo "ok $prefix $model $arm @$from+$chunk $(date +%H:%M)"
  else
    echo "FAILED $prefix $model $arm @$from+$chunk $(date +%H:%M)"
  fi
' < "$JOBS"
rm -f "$JOBS"
echo "== all matrices returned $(date +%H:%M)"
