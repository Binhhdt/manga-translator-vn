"""Chuyển Opus-MT (Helsinki-NLP, Apache-2.0) sang ONNX int8 cho Android và tự kiểm tra.

Đầu ra: <out>/encoder.onnx, decoder.onnx, source.spm, vocab.json, config.json, reference.json
reference.json: id token + bản dịch tham chiếu, tạo bằng ĐÚNG thuật toán mà ứng dụng Kotlin dùng
(không KV-cache, beam search BEAMS), để kiểm thử bản Kotlin trên thiết bị.
In thêm bảng so sánh fp32/int8 × greedy/beam để chọn cấu hình dựa trên số liệu.
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
BEAMS = int(os.environ.get("BEAMS", "4"))
# Đo thật: int8 per-channel làm mô hình chỉ sinh "...." → dùng int8 per-tensor.
PER_CHANNEL = os.environ.get("QUANT", "per_tensor") == "per_channel"
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
    quantize_dynamic(f"{raw}/{name}.onnx", f"{OUT}/{name.split('_')[0]}.onnx",
                     weight_type=QuantType.QInt8, per_channel=PER_CHANNEL)
for f in ("source.spm", "vocab.json", "config.json"):
    shutil.copy(f"{src}/{f}", f"{OUT}/{f}")

cfg = json.load(open(f"{OUT}/config.json"))
vocab = json.load(open(f"{OUT}/vocab.json", encoding="utf-8"))
inv = {v: k for k, v in vocab.items()}
sp = spm.SentencePieceProcessor(model_file=f"{OUT}/source.spm")
enc8 = ort.InferenceSession(f"{OUT}/encoder.onnx")
dec8 = ort.InferenceSession(f"{OUT}/decoder.onnx")
enc32 = ort.InferenceSession(f"{raw}/encoder_model.onnx")
dec32 = ort.InferenceSession(f"{raw}/decoder_model.onnx")
print("encoder inputs", [i.name for i in enc8.get_inputs()], "outputs", [o.name for o in enc8.get_outputs()][:3])
print("decoder inputs", [i.name for i in dec8.get_inputs()], "outputs", [o.name for o in dec8.get_outputs()][:3])
unk = vocab.get("<unk>", cfg.get("unk_token_id", 1))
eos, pad, start = cfg["eos_token_id"], cfg["pad_token_id"], cfg["decoder_start_token_id"]
SPACE = "▁"


def encode(text):
    pieces = sp.encode(text, out_type=str)
    return pieces, [vocab.get(p, unk) for p in pieces] + [eos]


def detok(ids):
    return "".join(inv.get(i, "") for i in ids).replace(SPACE, " ").strip()


def translate(text, e, d, beams=1, max_len=128):
    """Giống bản Kotlin: không KV-cache; beam search với log-softmax, điểm chia cho độ dài khi kết thúc."""
    _, ids = encode(text)
    a = np.array([ids], dtype=np.int64)
    m = np.ones_like(a)
    hidden = e.run(["last_hidden_state"], {"input_ids": a, "attention_mask": m})[0]
    hyps = [([start], 0.0)]
    done = []
    for _ in range(max_len):
        cand = []
        for seq, score in hyps:
            logits = d.run(["logits"], {"input_ids": np.array([seq], dtype=np.int64),
                                        "encoder_hidden_states": hidden, "encoder_attention_mask": m})[0][0, -1]
            logits = logits.astype(np.float64)
            logits[pad] = -np.inf
            logp = logits - np.logaddexp.reduce(logits)
            for t in np.argsort(-logp, kind="stable")[:beams]:
                cand.append((seq + [int(t)], score + float(logp[t])))
        cand.sort(key=lambda x: -x[1])
        hyps = []
        for seq, score in cand:
            if seq[-1] == eos:
                done.append((seq[1:-1], score / (len(seq) - 1)))
            else:
                hyps.append((seq, score))
            if len(hyps) == beams:
                break
        if len(done) >= beams or not hyps:
            break
    if not done:
        done = [(h[0][1:], h[1] / len(h[0])) for h in hyps]
    best = max(done, key=lambda x: x[1])[0]
    return detok(best), best


ref = {"repo": REPO, "eos": eos, "pad": pad, "start": start, "unk": unk, "beams": BEAMS,
       "per_channel": PER_CHANNEL, "cases": []}
print(f"| câu | fp32 greedy | fp32 beam4 | int8 greedy | int8 beam{BEAMS} (dùng trong app) |")
for t in TEST:
    pieces, ids = encode(t)
    g32 = translate(t, enc32, dec32)[0]
    b32 = translate(t, enc32, dec32, beams=4)[0]
    g8 = translate(t, enc8, dec8)[0]
    text, out_ids = translate(t, enc8, dec8, beams=BEAMS)
    ref["cases"].append({"source": t, "pieces": pieces, "ids": ids, "output_ids": out_ids, "translation": text})
    print(f"| {t} | {g32} | {b32} | {g8} | {text} |")
json.dump(ref, open(f"{OUT}/reference.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
for f in sorted(os.listdir(OUT)):
    print(f, round(os.path.getsize(f"{OUT}/{f}") / 1e6, 1), "MB")
