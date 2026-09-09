#!/bin/sh
# One chunk of the retrieve-or-answer suite on a Firebase Test Lab phone.
#
#   tools/eval/bench/run_decisions_ftl.sh <model-id> <os-version> <prefix>
#     MODELS=a.pte,b.gguf  ARMS=driven-search  SETS=retrievalqa  FROM=0  ROWS=40  ECHO=1
#
# The suite is an app instrumentation (it drives the real TurnRunner), so both the app
# and its test APK go up; the model files, the prompt dump and the rows come from the
# eval bucket, which is also the results bucket for the reason run_matrix_ftl.sh gives
# (a cross-location copy of a gigabyte dies at thirty seconds). A physical device gets
# forty-five minutes a matrix, so the caller chunks the rows with FROM and ROWS and the
# pulled JSON lines are appended to one file per (phone, model, arm) under
# tools/eval/results/decisions/, where grade_decisions.py reads every chunk as one run.
set -eu
DEVICE=${1:?model id, e.g. mustang}
VERSION=${2:?os version id, e.g. 36}
PREFIX=${3:?results prefix, e.g. tensor-}
BUCKET=${BUCKET:-gs://openweights-eval-models}
PKG=io.github.alpharomercoma.openweights.debug
EVAL=/data/local/tmp/openweights/eval
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
OUT="$HERE/../results/decisions"
APP="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
APK="$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
MODELS=${MODELS:-LFM2.5-1.2B-Instruct-8da4w-32k.pte,LFM2.5-1.2B-Instruct-Q4_K_M.gguf,Qwen3-1.7B-Q8_0.gguf}
METHOD=${ECHO:+instructionEcho}
METHOD=${METHOD:-decisions}
mkdir -p "$OUT"

# Only the model files this chunk runs go to the phone; a tokenizer rides with its pte.
FILES="$EVAL/prompt_dump.json=$BUCKET/prompt_dump.json,$EVAL/decisions.json=$BUCKET/decisions.json"
for m in $(echo "$MODELS" | tr , ' '); do
  FILES="$FILES,$EVAL/$m=$BUCKET/$m"
  case "$m" in *.pte) t="${m%.pte}.tokenizer.json"; FILES="$FILES,$EVAL/$t=$BUCKET/$t" ;; esac
done

# gcloud's dict flag splits on commas; the ^:^ prefix makes the colon the separator so a
# comma-separated value survives.
ENV_VARS="^:^models=$MODELS${ARMS:+:arms=$ARMS}${SETS:+:sets=$SETS}${FROM:+:from=$FROM}${ROWS:+:rows=$ROWS}"
TAG="$PREFIX$METHOD-$(echo "${MODELS}" | tr , + | cut -c1-40)-${ARMS:-all}-${SETS:-all}@${FROM:-0}+${ROWS:-all}"
LOG="$OUT/$TAG.ftl.log"
echo "== $TAG on $DEVICE ($VERSION); log in $LOG"
gcloud firebase test android run --quiet --type instrumentation \
  --app "$APP" --test "$APK" \
  --device "model=$DEVICE,version=$VERSION,locale=en,orientation=portrait" \
  --test-targets "class io.github.alpharomercoma.openweights.ui.chat.DecisionSuiteOnDeviceTest#$METHOD" \
  --timeout 45m --environment-variables "$ENV_VARS" \
  --results-bucket "$BUCKET" --results-dir "decisions/$(date +%Y%m%d-%H%M%S)-$$-$DEVICE" \
  --other-files "$FILES" \
  --directories-to-pull "/sdcard/Android/data/$PKG/files/eval-results" \
  --results-history-name "openweights-decisions-$DEVICE" 2>&1 | tee "$LOG" | grep -E "Test is|OUTCOME|Passed|Failed|error|ERROR" || true

RESULTS=$(grep -o 'storage/browser/[^]]*' "$LOG" | head -1 | sed 's|storage/browser/|gs://|')
echo "== pulling rows from $RESULTS"
TMP=$(mktemp -d)
tries=0
until gcloud storage cp -r "${RESULTS}*/artifacts/sdcard/Android/data/$PKG/files/eval-results/*.jsonl" "$TMP/" >/dev/null 2>&1 \
      && [ -n "$(find "$TMP" -name '*.jsonl')" ]; do
  tries=$((tries + 1))
  if [ "$tries" -ge 8 ]; then break; fi
  sleep 30
done
PULLED=0
for f in $(find "$TMP" -name '*.jsonl'); do
  # Appended, not copied: the next chunk of the same (phone, model, arm) lands in the
  # same file, and the grader reads a file with several headers as one run.
  cat "$f" >> "$OUT/$PREFIX$(basename "$f")"
  echo "   +$(wc -l < "$f" | tr -d ' ') lines -> $OUT/$PREFIX$(basename "$f")"
  PULLED=$((PULLED + 1))
done
gcloud storage cp "${RESULTS}$DEVICE-$VERSION-en-portrait/logcat" "${LOG%.ftl.log}.logcat" >/dev/null 2>&1 || true
[ "$PULLED" -gt 0 ] || { echo "no rows came back from $RESULTS" >&2; exit 1; }
