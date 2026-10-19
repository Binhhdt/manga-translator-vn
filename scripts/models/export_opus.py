"""Chuyển Opus-MT (Helsinki-NLP, Apache-2.0) sang ONNX int8 cho Android và tự kiểm tra.

Đầu ra: <out>/encoder.onnx, decoder.onnx, source.spm, vocab.json, config.json, reference.json
reference.json: id token + bản dịch tham chiếu, tạo bằng ĐÚNG thuật toán greedy mà ứng dụng Kotlin dùng
(không dùng KV-cache), để kiểm thử bản Kotlin trên thiết bị.
"""
import json
import os
import shutil
import subprocess
import sys

import numpy as np
import onnxruntime as ort
import sentencepiece as spm
from huggingface_hub import snapshot_download
from onnxruntime.quantization import QuantType, quantize_dynamic

REPO = sys.argv[1]
OUT = sys.argv[2]
TEST = [
    "さわらないで", "黒崎", "神奈川 GK 負傷!!", "腕を骨折", "大丈夫かよ", "こんなブザマはさらさなかったのによ",
    "黒崎おいっ大丈夫かよォ…", "オーイ救急班―", "こりゃみごとに折れてるな―…",
    "あまりの鹿児島のパワーに腕を骨折―!!", "残念だなァ…", "いててて…クソ受け身がとれてたら…",
]

os.makedirs(OUT, exist_ok=True)
src = snapshot_download(REPO, allow_patterns=["*.json", "*.spm", "pytorch_model.bin", "*.safetensors"])
raw = "/tmp/onnx_raw"
shutil.rmtree(raw, ignore_errors=True)
subprocess.check_call(["optimum-cli", "export", "onnx", "--model", src, "--task", "text2text-generation", raw])
for name in ("encoder_model", "decoder_model"):
    quantize_dynamic(f"{raw}/{name}.onnx", f"{OUT}/{name.split('_')[0]}.onnx", weight_type=QuantType.QInt8)
for f in ("source.spm", "vocab.json", "config.json"):
    shutil.copy(f"{src}/{f}", f"{OUT}/{f}")

cfg = json.load(open(f"{OUT}/config.json"))
vocab = json.load(open(f"{OUT}/vocab.json", encoding="utf-8"))
inv = {v: k for k, v in vocab.items()}
sp = spm.SentencePieceProcessor(model_file=f"{OUT}/source.spm")
enc = ort.InferenceSession(f"{OUT}/encoder.onnx")
dec = ort.InferenceSession(f"{OUT}/decoder.onnx")
print("encoder inputs", [i.name for i in enc.get_inputs()], "outputs", [o.name for o in enc.get_outputs()][:3])
print("decoder inputs", [i.name for i in dec.get_inputs()], "outputs", [o.name for o in dec.get_outputs()][:3])
unk = vocab.get("<unk>", cfg.get("unk_token_id", 1))
eos, pad, start = cfg["eos_token_id"], cfg["pad_token_id"], cfg["decoder_start_token_id"]


def encode(text):
    pieces = sp.encode(text, out_type=str)
    return pieces, [vocab.get(p, unk) for p in pieces] + [eos]


def translate(text, max_len=128):
    _, ids = encode(text)
    a = np.array([ids], dtype=np.int64)
    m = np.ones_like(a)
    hidden = enc.run(["last_hidden_state"], {"input_ids": a, "attention_mask": m})[0]
    out = [start]
    for _ in range(max_len):
        logits = dec.run(["logits"], {"input_ids": np.array([out], dtype=np.int64),
                                      "encoder_hidden_states": hidden, "encoder_attention_mask": m})[0][0, -1]
        logits[pad] = -np.inf
        nxt = int(np.argmax(logits))
        if nxt == eos:
            break
        out.append(nxt)
    pieces = [inv.get(i, "") for i in out[1:]]
    return "".join(pieces).replace("▁", " ").strip(), out[1:]


ref = {"repo": REPO, "eos": eos, "pad": pad, "start": start, "unk": unk, "cases": []}
for t in TEST:
    pieces, ids = encode(t)
    text, out_ids = translate(t)
    ref["cases"].append({"source": t, "pieces": pieces, "ids": ids, "output_ids": out_ids, "translation": text})
    print(f"«{t}» {pieces} → «{text}»")
json.dump(ref, open(f"{OUT}/reference.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
for f in sorted(os.listdir(OUT)):
    print(f, round(os.path.getsize(f"{OUT}/{f}") / 1e6, 1), "MB")
