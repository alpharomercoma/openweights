#!/bin/sh
# The public benchmarks on the phone over adb: every pushed model, both engines, no
# time box worth speaking of. Results land in tools/eval/results/ unprefixed, which is
# the Dimensity's column.
#
#   tools/eval/bench/run_local.sh [adb-serial]
set -eu
SERIAL=${1:-}
ADB="adb ${SERIAL:+-s $SERIAL}"
PKG=io.github.alpharomercoma.openweights.core.engine.test
RUNNER=androidx.test.runner.AndroidJUnitRunner
EVAL=/data/local/tmp/openweights/eval
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
OUT="$HERE/../results"
APK="$ROOT/core/engine/build/outputs/apk/androidTest/accelerated/debug/engine-accelerated-debug-androidTest.apk"

$ADB push "$HERE/benchmarks.json" "$EVAL/benchmarks.json" >/dev/null
$ADB push "$APK" /data/local/tmp/owtest.apk >/dev/null
$ADB shell pm install -r -t --user 0 /data/local/tmp/owtest.apk
$ADB shell "rm -f /sdcard/Android/data/$PKG/files/eval-results/*.bench*.json"
for class in ExecuTorchBenchmarkEval LlamaCppBenchmarkEval; do
  echo "== $class $(date +%H:%M)"
  $ADB shell am instrument -w -r -e budget 600 -e class io.github.alpharomercoma.openweights.core.engine.eval.$class "$PKG/$RUNNER" \
    | tee "$OUT/bench-$class.instrument.log" | grep -E "^OK|FAILURES|Error" | tail -3
done
$ADB pull "/sdcard/Android/data/$PKG/files/eval-results/." "$OUT/" | tail -1
echo "== done $(date +%H:%M)"
