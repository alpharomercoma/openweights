#!/bin/sh
# The retrieve-or-answer suite on the phone over adb, through the app's own loop.
# Results land in tools/eval/results/decisions/ as one JSON line per row.
#
#   tools/eval/bench/run_decisions.sh [adb-serial]
#     MODELS=a.pte,b.gguf   ARMS=driven-search,driven-full   SETS=retrievalqa,popqa   ROWS=40
#     PREFIX=qdc-           names another phone's files
#     ECHO=1                runs the instruction-echo probe instead of the decisions
#
# The instrumentation runs attached (-w) from a shell this script keeps open: started
# detached with nohup, the am client died with the adb session before the test began
# (measured 2026-09-10). Once running, the test outlives an adb drop: the same day the
# wireless port went away mid-arm and the phone finished two arms on its own. Rows are
# written as they land, so a rerun resumes where the file stops.
#
# An unplugged phone with its screen off suspends its CPU, timeouts included: a search
# froze for two hours that way. The screen is woken at the start and kept awake for the
# length of the run; on the charger neither is needed.
set -eu
SERIAL=${1:-}
ADB="adb ${SERIAL:+-s $SERIAL}"
PKG=io.github.alpharomercoma.openweights.debug
TEST=$PKG.test
RUNNER=androidx.test.runner.AndroidJUnitRunner
EVAL=/data/local/tmp/openweights/eval
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
OUT="$HERE/../results/decisions"
mkdir -p "$OUT"
APP="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
APK="$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
METHOD=${ECHO:+instructionEcho}
METHOD=${METHOD:-decisions}

# A dozing phone throttles instrumentation two to five times; awake and unlocked for the run.
$ADB shell "settings put system screen_off_timeout 2147483647; input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard" >/dev/null 2>&1 || true
# And kept that way: the ROM relocks after a wake, and an unplugged phone then suspends.
(
  while kill -0 $$ 2>/dev/null; do
    $ADB shell dumpsys power 2>/dev/null | grep -q "mWakefulness=Awake" ||
      $ADB shell "input keyevent KEYCODE_WAKEUP; sleep 1; wm dismiss-keyguard" >/dev/null 2>&1
    sleep 30
  done
) &
AWAKE=$!
trap 'kill $AWAKE 2>/dev/null' EXIT
$ADB push "$HERE/decisions.json" "$EVAL/decisions.json" >/dev/null
if [ -n "${INSTALL:-}" ]; then
  $ADB push "$APP" /data/local/tmp/app.apk >/dev/null && $ADB shell pm install -r -t --user 0 /data/local/tmp/app.apk
  $ADB push "$APK" /data/local/tmp/owtest.apk >/dev/null && $ADB shell pm install -r -t --user 0 /data/local/tmp/owtest.apk
fi
$ADB shell "logcat -G 8M" >/dev/null 2>&1 || true
echo "== $METHOD $(date +%H:%M)"
$ADB shell "am instrument -w -r ${MODELS:+-e models $MODELS} ${ARMS:+-e arms $ARMS} ${SETS:+-e sets $SETS} ${ROWS:+-e rows $ROWS} -e class io.github.alpharomercoma.openweights.ui.chat.DecisionSuiteOnDeviceTest#$METHOD $TEST/$RUNNER" \
  | grep -E "INSTRUMENTATION_(RESULT|STATUS: stack|CODE)|Time:" || true
for f in $($ADB shell "ls /sdcard/Android/data/$PKG/files/eval-results/decisions-*.jsonl /sdcard/Android/data/$PKG/files/eval-results/echo-*.jsonl" 2>/dev/null); do
  $ADB pull "$f" "$OUT/${PREFIX:-}$(basename "$f")" >/dev/null && echo "   ${PREFIX:-}$(basename "$f")"
done
echo "== done $(date +%H:%M)"
