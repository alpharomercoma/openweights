#!/bin/sh
# The backend-parity matrix on a Firebase Test Lab device, where there is no adb session.
#
#   tools/eval/run_matrix_ftl.sh <model-id> <os-version> <prefix> <engine> [gs-bucket]
#
# e.g.  tools/eval/run_matrix_ftl.sh mustang 36 tensor- executorch
#       tools/eval/run_matrix_ftl.sh e2s 36 exynos- llamacpp
#
# One engine per run, because Test Lab caps a physical-device run at 45 minutes and each
# engine's class takes eight to ten minutes on the Dimensity alone. The models come from
# the bucket rather than from this machine: `--other-files` accepts gs:// sources, and
# fifteen gigabytes uploaded once is fifteen gigabytes not uploaded per run. The results
# go to the same bucket, on purpose: Test Lab copies every pushed file into its results
# bucket first, and a copy between storage locations gives up after thirty seconds on a
# file this size (every run failed that way against its default US multi-region bucket
# from a regional one). Same bucket, same location, no copy across it. Reports are
# written by the evals to the test package's external files directory, which is a path
# Test Lab can pull, and land in tools/eval/results/ under <prefix> so compare.py knows
# which phone they came from.
set -eu

MODEL=${1:?model id, e.g. mustang}
VERSION=${2:?os version id, e.g. 36}
PREFIX=${3:?results prefix, e.g. tensor-}
ENGINE=${4:?executorch or llamacpp}
BUCKET=${5:-gs://openweights-eval-models}
PKG=io.github.alpharomercoma.openweights.core.engine.test
EVAL=/data/local/tmp/openweights/eval
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
OUT="$HERE/results"
APK="$ROOT/core/engine/build/outputs/apk/androidTest/accelerated/debug/engine-accelerated-debug-androidTest.apk"

case "$ENGINE" in
  executorch) CLASS=ExecuTorchParityEval; PATTERN='\.pte$|\.tokenizer\.json$' ;;
  llamacpp)   CLASS=LlamaCppParityEval;   PATTERN='\.gguf$' ;;
  # The thread sweep, on the one GGUF it needs. See SpeedProbe.kt.
  probe)      CLASS=SpeedProbe;           PATTERN='Qwen3-1.7B-Q8_0\.gguf$' ;;
  *) echo "engine must be executorch, llamacpp or probe" >&2; exit 2 ;;
esac

echo "== building the accelerated test APK (both engines live in it)"
(cd "$ROOT" && ./gradlew :core:engine:assembleAcceleratedDebugAndroidTest --console=plain -q)

echo "== the $ENGINE models in $BUCKET"
FILES=""
for uri in $(gcloud storage ls "$BUCKET/" | grep -E "$PATTERN"); do
  name=$(basename "$uri")
  FILES="${FILES:+$FILES,}$EVAL/$name=$uri"
  echo "   $name"
done
[ -n "$FILES" ] || { echo "no $ENGINE models in $BUCKET" >&2; exit 1; }

mkdir -p "$OUT"
LOG="$OUT/$PREFIX$ENGINE.ftl.log"
echo "== running $CLASS on $MODEL ($VERSION); log in $LOG"
gcloud firebase test android run --quiet --type instrumentation \
  --app "$APK" --test "$APK" \
  --device "model=$MODEL,version=$VERSION,locale=en,orientation=portrait" \
  --test-targets "class io.github.alpharomercoma.openweights.core.engine.eval.$CLASS" \
  --timeout 45m \
  --results-bucket "$BUCKET" --results-dir "runs/$(date +%Y%m%d-%H%M%S)-$MODEL-$ENGINE" \
  --other-files "$FILES" \
  --directories-to-pull "/sdcard/Android/data/$PKG/files/eval-results" \
  --results-history-name "openweights-parity-$MODEL" 2>&1 | tee "$LOG" | grep -E "Test is|OUTCOME|Passed|Failed|error|ERROR" || true

# The raw results directory is named in the log; the pulled reports sit under it beside
# the logcat, in a folder named for the device path they were pulled from.
RESULTS=$(grep -o 'storage/browser/[^]]*' "$LOG" | head -1 | sed 's|storage/browser/|gs://|')
echo "== pulling reports from $RESULTS"
TMP=$(mktemp -d)
gcloud storage cp -r "${RESULTS}*/artifacts/sdcard/Android/data/$PKG/files/eval-results/*.json" "$TMP/" 2>/dev/null \
  || gcloud storage cp -r "${RESULTS}" "$TMP/" >/dev/null 2>&1
for f in $(find "$TMP" -name '*.json' | grep -v instrumentation); do
  cp "$f" "$OUT/$PREFIX$(basename "$f")"
  echo "   $OUT/$PREFIX$(basename "$f")"
done
gcloud storage cp "${RESULTS}$MODEL-$VERSION-en-portrait/logcat" "$OUT/$PREFIX$ENGINE.logcat" >/dev/null 2>&1 || true

echo "== rendering comparison"
python3 "$HERE/compare.py" "$OUT" --out "$ROOT/docs/research/backend-parity.md"
echo "done: docs/research/backend-parity.md"
