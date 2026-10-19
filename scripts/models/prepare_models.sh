#!/usr/bin/env bash
# Tạo mô hình Opus-MT Nhật→Anh dạng ONNX int8 vào assets (bỏ qua nếu đã có trong cache).
set -euo pipefail
OUT=app/src/main/assets/mt/ja-en
if [ -f "$OUT/decoder.onnx" ] && [ -f "$OUT/reference.json" ]; then echo "Đã có mô hình trong cache"; exit 0; fi
python3 -m pip install --quiet torch --index-url https://download.pytorch.org/whl/cpu
python3 -m pip install --quiet "transformers==4.46.3" "optimum[onnxruntime]==1.23.3" sentencepiece sacremoses huggingface_hub
python3 scripts/models/export_opus.py Helsinki-NLP/opus-mt-ja-en "$OUT"
