#!/bin/sh
# The backend-parity matrix, end to end: push models, run both engines' evals, pull the
# reports, and render the comparison. Rerunnable by design — this same script after any
# backend change is the regression test.
#
#   tools/eval/run_matrix.sh <models-dir> [adb-serial]
#
# <models-dir> holds the matrix: each ExecuTorch model as <name>.pte with
# <name>.tokenizer.json beside it, each llama.cpp model as <name>.gguf. Every file is
# pushed to the phone's eval directory and evaluated by whichever engine owns its format.
# Results land in tools/eval/results/ and the rendered comparison in
# docs/research/backend-parity.md (compare.py).
set -eu

MODELS_DIR=${1:?usage: run_matrix.sh <models-dir> [adb-serial]}
SERIAL=${2:-}
ADB="adb ${SERIAL:+-s $SERIAL}"
PKG=io.github.alpharomercoma.openweights.core.engine.test
RUNNER=androidx.test.runner.AndroidJUnitRunner
EVAL=/data/local/tmp/openweights/eval
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
OUT="$HERE/results"

echo "== pushing models from $MODELS_DIR"
$ADB shell mkdir -p "$EVAL"
for f in "$MODELS_DIR"/*.pte "$MODELS_DIR"/*.tokenizer.json "$MODELS_DIR"/*.gguf; do
  [ -e "$f" ] || continue
  name=$(basename "$f")
  size=$($ADB shell stat -c %s "$EVAL/$name" 2>/dev/null || echo 0)
  local_size=$(stat -f %z "$f" 2>/dev/null || stat -c %s "$f")
  if [ "$size" = "$local_size" ]; then echo "   have $name"; else $ADB push "$f" "$EVAL/$name"; fi
done

echo "== building the accelerated test APK (both engines live in it)"
(cd "$ROOT" && ./gradlew :core:engine:assembleAcceleratedDebugAndroidTest --console=plain -q)

echo "== installing"
$ADB push "$ROOT/core/engine/build/outputs/apk/androidTest/accelerated/debug/engine-accelerated-debug-androidTest.apk" /data/local/tmp/owtest.apk
$ADB shell pm install -r -t --user 0 /data/local/tmp/owtest.apk

run_class() {
  echo "== running $1"
  $ADB shell am instrument -w -r -e class "$1" "$PKG/$RUNNER" | tee "$OUT/$2.instrument.log" | grep -E "INSTRUMENTATION_STATUS_CODE|Error|FAILURES" | tail -5
}

mkdir -p "$OUT"
run_class io.github.alpharomercoma.openweights.core.engine.eval.ExecuTorchParityEval executorch
run_class io.github.alpharomercoma.openweights.core.engine.eval.LlamaCppParityEval llamacpp

echo "== pulling reports"
for f in $($ADB shell run-as "$PKG" ls files/eval-results 2>/dev/null); do
  f=$(echo "$f" | tr -d '\r')
  $ADB shell run-as "$PKG" cat "files/eval-results/$f" > "$OUT/$f"
  echo "   $OUT/$f"
done

echo "== rendering comparison"
python3 "$HERE/compare.py" "$OUT" --out "$ROOT/docs/research/backend-parity.md"
echo "done: docs/research/backend-parity.md"
