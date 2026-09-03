#!/bin/sh
# A phone behind Samsung Remote Test Lab's RDB bridge: push one family, run it on both engines, pull,
# then the next family, so a two-hour session that ends early still leaves whole families.
#
#   MODELS_DIR=<dir> PREFIX=exynos2500- [FAMILY=<stem substring>] tools/eval/bench/run_rtl.sh [adb-serial]
#
# Files over 2 GB must be pre-split (split -b 1500m X X.part-) because the bridge drops
# at the end of them; set Developer options > Stay awake first, and never let the shell
# touch the network, which ends the session.
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
SER=${1:-localhost:58244}; ADB="adb -s $SER"
S=${MODELS_DIR:?directory holding the matrix models}
PKG=io.github.alpharomercoma.openweights.core.engine.test; RUNNER=androidx.test.runner.AndroidJUnitRunner
EVAL=/data/local/tmp/openweights/eval; HERE=$(cd "$(dirname "$0")" && pwd); OUT="$HERE/../results"
APK=$HERE/../../../core/engine/build/outputs/apk/androidTest/accelerated/debug/engine-accelerated-debug-androidTest.apk
$ADB shell mkdir -p $EVAL
$ADB push "$HERE/benchmarks.json" $EVAL/benchmarks.json | tail -1
$ADB push "$APK" /data/local/tmp/owtest.apk | tail -1
$ADB shell pm install -r -t --user 0 /data/local/tmp/owtest.apk

push() { name=$(basename "$1"); size=$($ADB shell stat -c %s $EVAL/$name 2>/dev/null || echo 0); local_size=$(stat -f %z "$1")
  [ "$size" = "$local_size" ] && { echo "have $name"; return; }
  echo "push $name $(date +%H:%M)"
  # The bridge has dropped at the end of every file over 2 GB, twice on the same one:
  # anything that size goes over as halves and is joined on the phone. 2026-09-04: it also
  # dropped 6 min into the 1.99 GB SmolLM3 .pte ("65544-byte write failed"), which had gone
  # over whole the session before, so the threshold is 1.5 GB rather than the observed 2.
  if [ "$local_size" -gt 1500000000 ]; then
    for part in "$1".part-*; do $ADB push "$part" $EVAL/$(basename "$part") | tail -1; done
    $ADB shell "cd $EVAL && cat $name.part-* > $name && rm -f $name.part-*"
  else
    $ADB push "$1" $EVAL/$name | tail -1
  fi; }
run() { class=$1; filter=$2; echo "== $class $filter $(date +%H:%M)"
  $ADB shell "touch /data/local/tmp/bench-start"
  $ADB shell "nohup am instrument -r -e budget 600 -e model $filter -e class io.github.alpharomercoma.openweights.core.engine.eval.$class $PKG/$RUNNER >/data/local/tmp/bench.log 2>&1 &"
  sleep 20; while $ADB shell pidof $PKG >/dev/null 2>&1; do sleep 60; done
  # Only reports written by this run: earlier families' files are still on the phone.
  TMP=$(mktemp -d)
  for f in $($ADB shell "find /sdcard/Android/data/$PKG/files/eval-results -name '*.bench*.json' -newer /data/local/tmp/bench-start"); do
    $ADB pull "$f" "$TMP/" >/dev/null 2>&1 && cp "$TMP/$(basename "$f")" "$OUT/${PREFIX:-exynos2500-}$(basename "$f")" && echo "   $(basename "$f")"
  done; }
# family: <pte stem> <pte filter> <gguf name> <gguf filter>
for fam in "Qwen3-1.7B-INT8-INT4-ExecuTorch-XNNPACK Qwen3 Qwen3-1.7B-Q8_0.gguf Qwen3" \
           "react-native-executorch-lfm-2.5-lfm_2_5_1_2b_xnnpack_8da4w lfm LFM2.5-1.2B-Instruct-Q4_K_M.gguf LFM2.5" \
           "react-native-executorch-llama-3.2-llama_3_2_3b_xnnpack_spinquant llama-3.2 Llama-3.2-3B-Instruct-Q4_K_M.gguf Llama-3.2" \
           "Gemma3-1B-IT-INT8-INT4-ExecuTorch-XNNPACK Gemma3 gemma-3-1b-it-Q4_K_M.gguf gemma-3" \
           "SmolLM3-3B-INT8-INT4 SmolLM3-3B SmolLM3-Q4_K_M.gguf SmolLM3-Q4"; do
  set -- $fam
  # Resume a session that ended early: FAMILY=SmolLM3 runs only the families whose stem matches.
  [ -n "$FAMILY" ] && case "$1" in *"$FAMILY"*) : ;; *) continue ;; esac
  push $S/$1.pte; push $S/$1.tokenizer.json; run ExecuTorchBenchmarkEval $2
  push $S/$3; run LlamaCppBenchmarkEval $4
  $ADB shell "dumpsys battery | grep temperature"
done
echo "== done $(date +%H:%M)"
