"""So sánh bộ dịch offline miễn phí Nhật → Việt trên cùng các câu thử.

Chạy trên CI (cần Internet để tải mô hình). Ghi kết quả ra tệp văn bản.
Không có bản dịch đáp án nào trong tệp này — chỉ có câu đầu vào.
"""
import json
import sys
import time
import traceback
import urllib.request

import torch

torch.set_num_threads(4)
OUT = sys.argv[1] if len(sys.argv) > 1 else "mt_eval.txt"

SENTENCES = [
    "さわらないで",
    "黒崎",
    "神奈川 GK 負傷!!",
    "腕を骨折",
    "大丈夫かよ",
    "こんなブザマはさらさなかったのによ",
    "黒崎おいっ大丈夫かよォ……",
    "さわらないで――",
    "オーイ救急班―",
    "こりゃみごとに折れてるな――……",
    "あまりの鹿児島のパワーに腕を骨折――!!",
    "残念だなァ……",
    "いててて……クソ受け身がとれてたら……",
]

lines = []


def emit(s=""):
    print(s, flush=True)
    lines.append(s)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


def repo_size(repo):
    """Tổng dung lượng tệp trọng số của một repo Hugging Face (MB)."""
    try:
        with urllib.request.urlopen(f"https://huggingface.co/api/models/{repo}?blobs=true", timeout=30) as r:
            info = json.load(r)
        files = {s["rfilename"]: s.get("size") or 0 for s in info.get("siblings", [])}
        return files
    except Exception as e:  # noqa: BLE001
        return {"lỗi": str(e)}


def weights_mb(files):
    exts = (".safetensors", ".bin", ".onnx", ".pt")
    st = sum(v for k, v in files.items() if k.endswith(".safetensors"))
    if st:
        return st / 1e6
    return sum(v for k, v in files.items() if k.endswith(exts)) / 1e6


def run_model(title, repo, translate_fn_factory):
    emit(f"\n=== {title} [{repo}] ===")
    files = repo_size(repo)
    emit(f"Dung lượng trọng số (fp32/fp16 trên HF): {weights_mb(files):.0f} MB")
    try:
        t0 = time.time()
        fn = translate_fn_factory()
        emit(f"Nạp mô hình: {time.time() - t0:.1f}s")
        for s in SENTENCES:
            t1 = time.time()
            out = fn(s)
            emit(f"«{s}» → «{out}»  ({time.time() - t1:.2f}s)")
    except Exception:  # noqa: BLE001
        emit("LỖI: " + traceback.format_exc()[-1500:])


def marian(repo):
    from transformers import MarianMTModel, MarianTokenizer

    def factory():
        tok = MarianTokenizer.from_pretrained(repo)
        model = MarianMTModel.from_pretrained(repo).eval()

        def fn(s):
            with torch.no_grad():
                ids = tok([s], return_tensors="pt", padding=True)
                gen = model.generate(**ids, num_beams=4, max_new_tokens=96)
            return tok.decode(gen[0], skip_special_tokens=True)

        return fn

    return factory


def pivot(repo1, repo2):
    def factory():
        f1 = marian(repo1)()
        f2 = marian(repo2)()

        def fn(s):
            en = f1(s)
            return f"{f2(en)}   [qua tiếng Anh: «{en}»]"

        return fn

    return factory


def nllb(repo):
    from transformers import AutoModelForSeq2SeqLM, AutoTokenizer

    def factory():
        tok = AutoTokenizer.from_pretrained(repo, src_lang="jpn_Jpan")
        model = AutoModelForSeq2SeqLM.from_pretrained(repo).eval()
        vie = tok.convert_tokens_to_ids("vie_Latn")

        def fn(s):
            with torch.no_grad():
                ids = tok(s, return_tensors="pt")
                gen = model.generate(**ids, forced_bos_token_id=vie, num_beams=4, max_new_tokens=96)
            return tok.batch_decode(gen, skip_special_tokens=True)[0]

        return fn

    return factory


def m2m(repo):
    from transformers import M2M100ForConditionalGeneration, M2M100Tokenizer

    def factory():
        tok = M2M100Tokenizer.from_pretrained(repo)
        tok.src_lang = "ja"
        model = M2M100ForConditionalGeneration.from_pretrained(repo).eval()

        def fn(s):
            with torch.no_grad():
                ids = tok(s, return_tensors="pt")
                gen = model.generate(**ids, forced_bos_token_id=tok.get_lang_id("vi"), num_beams=4, max_new_tokens=96)
            return tok.batch_decode(gen, skip_special_tokens=True)[0]

        return fn

    return factory


def llm(repo):
    from transformers import AutoModelForCausalLM, AutoTokenizer

    def factory():
        tok = AutoTokenizer.from_pretrained(repo)
        model = AutoModelForCausalLM.from_pretrained(repo, torch_dtype=torch.float32).eval()

        def fn(s):
            msgs = [
                {"role": "system", "content": "You translate Japanese manga dialogue into natural Vietnamese. "
                                              "Keep character names in romaji. Output only the Vietnamese translation."},
                {"role": "user", "content": s},
            ]
            prompt = tok.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True)
            ids = tok(prompt, return_tensors="pt")
            with torch.no_grad():
                gen = model.generate(**ids, max_new_tokens=64, do_sample=False)
            return tok.decode(gen[0][ids["input_ids"].shape[1]:], skip_special_tokens=True).strip()

        return fn

    return factory


def main():
    emit("##### SO SÁNH BỘ DỊCH OFFLINE Nhật → Việt (CPU, CI) #####")
    emit(f"torch {torch.__version__}")

    # Mô hình dịch Nhật–Việt khác trên Hugging Face (để biết còn lựa chọn nào).
    for q in ("ja-vi", "jpn-vie", "japanese-vietnamese"):
        try:
            with urllib.request.urlopen(f"https://huggingface.co/api/models?search={q}&limit=40&sort=downloads", timeout=30) as r:
                found = [m["id"] for m in json.load(r)]
            emit(f"Tìm '{q}' trên HF: {found}")
        except Exception as e:  # noqa: BLE001
            emit(f"Tìm '{q}' lỗi: {e}")
    # Bản ONNX có sẵn cho Android (onnxruntime)?
    for repo in ("Xenova/opus-mt-ja-vi", "Helsinki-NLP/opus-mt-ja-vi"):
        files = repo_size(repo)
        emit(f"Tệp trong {repo}: " + ", ".join(f"{k} ({v / 1e6:.1f} MB)" for k, v in files.items() if isinstance(v, int)))

    run_model("Opus-MT Nhật→Việt TRỰC TIẾP", "Helsinki-NLP/opus-mt-ja-vi", marian("Helsinki-NLP/opus-mt-ja-vi"))
    run_model("Opus-MT QUA TIẾNG ANH (mô phỏng cách dịch trung gian)", "Helsinki-NLP/opus-mt-ja-en",
              pivot("Helsinki-NLP/opus-mt-ja-en", "Helsinki-NLP/opus-mt-en-vi"))
    run_model("M2M100 418M (MIT)", "facebook/m2m100_418M", m2m("facebook/m2m100_418M"))
    run_model("NLLB-200 distilled 600M (CC-BY-NC)", "facebook/nllb-200-distilled-600M", nllb("facebook/nllb-200-distilled-600M"))
    run_model("Qwen2.5 0.5B Instruct (LLM nhỏ)", "Qwen/Qwen2.5-0.5B-Instruct", llm("Qwen/Qwen2.5-0.5B-Instruct"))
    run_model("Qwen2.5 1.5B Instruct (LLM nhỏ)", "Qwen/Qwen2.5-1.5B-Instruct", llm("Qwen/Qwen2.5-1.5B-Instruct"))
    emit("##### HẾT #####")


if __name__ == "__main__":
    main()
