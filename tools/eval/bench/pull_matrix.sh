#!/bin/sh
# Wait for a Test Lab matrix and copy its benchmark reports home under a prefix, for
# a matrix whose gcloud client is gone (killed, or the machine slept).
#   tools/eval/bench/pull_matrix.sh <matrix-id> <prefix>
set -eu
M=${1:?matrix id}; PREFIX=${2:?prefix}
PROJECT=$(gcloud config get-value project 2>/dev/null)
HERE=$(cd "$(dirname "$0")" && pwd); OUT="$HERE/../results"; PKG=io.github.alpharomercoma.openweights.core.engine.test
while true; do
  TOKEN=$(gcloud auth print-access-token 2>/dev/null)
  J=$(curl -sS -H "Authorization: Bearer $TOKEN" "https://testing.googleapis.com/v1/projects/$PROJECT/testMatrices/$M")
  STATE=$(printf '%s' "$J" | python3 -c "import json,sys; print(json.load(sys.stdin).get('state',''))")
  case "$STATE" in FINISHED|ERROR|CANCELLED|INVALID) break ;; esac
  sleep 120
done
GCS=$(printf '%s' "$J" | python3 -c "import json,sys; print(json.load(sys.stdin)['resultStorage']['googleCloudStorage']['gcsPath'])")
echo "$M $STATE $GCS"
TMP=$(mktemp -d)
gcloud storage cp -r "${GCS%/}/*/artifacts/sdcard/Android/data/$PKG/files/eval-results/*.json" "$TMP/" 2>/dev/null || true
for f in "$TMP"/*.json; do [ -e "$f" ] && cp "$f" "$OUT/$PREFIX$(basename "$f")" && echo "   $OUT/$PREFIX$(basename "$f")"; done
