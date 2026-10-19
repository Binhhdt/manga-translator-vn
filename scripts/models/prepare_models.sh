#!/usr/bin/env bash
# Tạo mô hình Opus-MT Nhật→Anh dạng ONNX int8 vào assets (bỏ qua nếu đã có trong cache).
set -euo pipefail
OUT=app/src/main/assets/mt/ja-en
if [ -f "$OUT/decoder.onnx" ] && [ -f "$OUT/reference.json" ]; then echo "Đã có mô hình trong cache"; exit 0; fi
# Ghim phiên bản: torch ≥ 2.9 đổi cách xuất ONNX mặc định, không tương thích optimum 1.23.
python3 -m pip install --quiet "torch==2.5.1" --index-url https://download.pytorch.org/whl/cpu
python3 -m pip install --quiet "numpy<2" "transformers==4.46.3" "optimum==1.23.3" "onnx==1.17.0" \
  "onnxruntime==1.20.1" sentencepiece sacremoses "huggingface_hub<0.27"
python3 scripts/models/export_opus.py Helsinki-NLP/opus-mt-ja-en "$OUT"
