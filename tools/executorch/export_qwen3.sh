#!/usr/bin/env bash
# Export Qwen3-1.7B to a .pte the app can open.
#
# Runs on a Linux host with ExecuTorch checked out and installed; the export is an
# ahead-of-time compile and does not happen on the phone. XNNPACK is deliberate: it is the
# CPU backend, it needs no vendor SDK, and it exercises every part of the app's ExecuTorch
# path. Switching to the MediaTek NPU later changes the config and adds the NeuroPilot SDK
# — see docs/research/mediatek-npu.md for whether that is worth doing at all.
#
#   ./export_qwen3.sh /path/to/executorch /path/to/output
#
# Produces, named the way ModelStore and ExecuTorchEngine expect to find them:
#   Qwen3-1.7B.pte
#   Qwen3-1.7B.tokenizer.json
set -euo pipefail

EXECUTORCH="${1:?usage: export_qwen3.sh <executorch-checkout> <output-dir>}"
OUTPUT="${2:?usage: export_qwen3.sh <executorch-checkout> <output-dir>}"

MODEL_CLASS="qwen3_1_7b"
# 8-bit dynamic activations, 4-bit weights. The recipe ExecuTorch ships for this model.
CONFIG="examples/models/qwen3/config/qwen3_xnnpack_q8da4w.yaml"
PARAMS="examples/models/qwen3/config/1_7b_config.json"
REPO="Qwen/Qwen3-1.7B"

# The name the app pairs a model and its tokenizer by. ExecuTorchEngine looks for the
# tokenizer as a sibling of the .pte with the same stem, because a .pte says nothing about
# which tokenizer produced it and the wrong one yields fluent nonsense rather than an error.
STEM="Qwen3-1.7B"

mkdir -p "$OUTPUT"
cd "$EXECUTORCH"

echo "==> exporting $MODEL_CLASS with $CONFIG"
python -m extension.llm.export.export_llm \
  --config "$CONFIG" \
  +base.model_class="$MODEL_CLASS" \
  +base.params="$PARAMS" \
  +export.output_name="$OUTPUT/$STEM.pte"

echo "==> fetching the tokenizer that matches those weights"
curl -sSfL "https://huggingface.co/$REPO/resolve/main/tokenizer.json" \
  -o "$OUTPUT/$STEM.tokenizer.json"

echo
echo "built:"
ls -lh "$OUTPUT/$STEM.pte" "$OUTPUT/$STEM.tokenizer.json"
echo
echo "push both to the phone's model directory:"
echo "  adb push $OUTPUT/$STEM.pte $OUTPUT/$STEM.tokenizer.json \\"
echo "    /sdcard/Android/data/io.github.alpharomercoma.openweights/files/models/"
