#!/bin/sh
# The public benchmarks on the phone over adb: every pushed model, both engines, no
# time box worth speaking of. Results land in tools/eval/results/ unprefixed, which is
# the Dimensity's column.
#
#   tools/eval/bench/run_local.sh [adb-serial]        BENCH_MODEL=<substring> narrows;
#                                                     PREFIX=qdc- names another phone's column
#
# The instrumentation is started detached rather than with -w: a wireless-debugging
# session that drops takes an attached run with it, and one did at prompt 54. The
# script polls for the test process instead, and pulls whatever reports exist.
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
MODEL=${BENCH_MODEL:-}
PREFIX=${PREFIX:-}

$ADB push "$HERE/benchmarks.json" "$EVAL/benchmarks.json" >/dev/null
$ADB push "$APK" /data/local/tmp/owtest.apk >/dev/null
$ADB shell pm install -r -t --user 0 /data/local/tmp/owtest.apk
for class in ExecuTorchBenchmarkEval LlamaCppBenchmarkEval; do
  echo "== $class $(date +%H:%M)"
  $ADB shell "nohup am instrument -r -e budget 600 ${MODEL:+-e model $MODEL} -e class io.github.alpharomercoma.openweights.core.engine.eval.$class $PKG/$RUNNER >/data/local/tmp/bench-$class.log 2>&1 &"
  sleep 20
  while $ADB shell pidof $PKG >/dev/null 2>&1; do sleep 60; done
  $ADB shell "grep -E 'INSTRUMENTATION_(RESULT|STATUS: stack)' /data/local/tmp/bench-$class.log | head -3" || true
done
TMP=$(mktemp -d)
$ADB pull "/sdcard/Android/data/$PKG/files/eval-results/." "$TMP/" | tail -1
for f in "$TMP"/*.bench*.json; do [ -e "$f" ] && cp "$f" "$OUT/$PREFIX$(basename "$f")"; done
echo "== done $(date +%H:%M)"
