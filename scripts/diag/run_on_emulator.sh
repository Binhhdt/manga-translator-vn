#!/usr/bin/env bash
# Chạy bài chẩn đoán DiagnosticsTest trên trình giả lập rồi lấy kết quả ra diag_app.txt.
set -x
adb wait-for-device
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class vn.mangatranslate.app.DiagnosticsTest \
  vn.mangatranslate.app.test/androidx.test.runner.AndroidJUnitRunner > instrument.txt 2>&1 || true
adb shell run-as vn.mangatranslate.app cat files/diag.txt > diag_app.txt 2>&1 || true
adb logcat -d -v brief MangaOCR:D MangaRender:D MangaDiag:I TestRunner:I AndroidRuntime:E '*:S' > logcat.txt 2>&1 || true
echo "--- instrument ---"; cat instrument.txt
echo "--- diag (đầu) ---"; head -c 3000 diag_app.txt
exit 0
