#!/usr/bin/env python3
"""Điều khiển giao diện trình giả lập qua uiautomator (không cần thư viện).
  ui.py tap "chữ" [--optional]   chạm vào nút có chữ (cuộn tìm nếu cần)
  ui.py texts                    in mọi chữ trên màn hình
  ui.py chrome_fre               bỏ qua màn hình chào lần đầu của Chrome
"""
import re, subprocess, sys, time

NODE = re.compile(r'<node [^>]*?text="([^"]*)"[^>]*?content-desc="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')

def sh(*a):
    return subprocess.run(["adb", "shell", *a], capture_output=True, text=True).stdout

def dump():
    for _ in range(3):
        sh("uiautomator", "dump", "/sdcard/ui.xml")
        x = sh("cat", "/sdcard/ui.xml")
        if "<node" in x:
            return x
        time.sleep(1)
    return ""

def nodes(xml):
    for m in NODE.finditer(xml):
        t = (m.group(1) or m.group(2)).replace("&quot;", '"').replace("&amp;", "&")
        x1, y1, x2, y2 = map(int, m.groups()[2:])
        yield t, (x1 + x2) // 2, (y1 + y2) // 2

def tap(text, optional=False, scrolls=6):
    for i in range(scrolls + 1):
        for t, x, y in nodes(dump()):
            if text in t:
                sh("input", "tap", str(x), str(y))
                print(f"TAP '{t}' @ {x},{y}")
                return True
        if i < scrolls:
            sh("input", "swipe", "540", "1700", "540", "700", "300")
            time.sleep(1)
    print(f"NOT FOUND '{text}'")
    if not optional:
        print("--- screen texts:"); texts()
    return False

def texts():
    for t, x, y in nodes(dump()):
        if t.strip():
            print(t)

def chrome_fre():
    for _ in range(5):
        hit = False
        for t, x, y in nodes(dump()):
            if t in ("Accept & continue", "Use without an account", "No thanks", "No, thanks", "Got it", "Continue", "Skip"):
                sh("input", "tap", str(x), str(y)); print(f"FRE TAP '{t}'"); hit = True; time.sleep(2); break
        if not hit:
            return

if __name__ == "__main__":
    c = sys.argv[1]
    if c == "tap":
        ok = tap(sys.argv[2], optional="--optional" in sys.argv)
        sys.exit(0 if ok or "--optional" in sys.argv else 1)
    elif c == "texts":
        texts()
    elif c == "chrome_fre":
        chrome_fre()
