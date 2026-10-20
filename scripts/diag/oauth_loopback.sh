#!/usr/bin/env bash
# Tái hiện tình huống thật: ứng dụng mở listener 127.0.0.1:1455 rồi mở Chrome (ứng dụng xuống nền),
# sau đó kết nối tới cổng từ TIẾN TRÌNH KHÁC (adb shell, như Chrome) và từ chính Chrome.
# Ghi lại: socket LISTEN và hàng đợi, trạng thái tiến trình (đóng băng hay không), phản hồi HTTP.
set -x
PKG=vn.mangatranslate.app
R=res
adb wait-for-device
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
{ adb shell getprop ro.build.version.release; adb shell getprop ro.build.version.sdk
  adb shell pm list packages | grep -i -E "chrome|browser|webview"
  adb shell device_config get activity_manager_native_boot use_freezer; } > $R/device.txt 2>&1
# Bỏ màn hình chào của Chrome nếu có thể.
adb shell 'echo "_ --disable-fre --no-default-browser-check --no-first-run" > /data/local/tmp/chrome-command-line'
adb logcat -c

probe() {  # $1 = nhãn
  local pid; pid=$(adb shell pidof $PKG | tr -d '\r')
  echo "===== PROBE $1 @ $(date +%T) pid=$pid"
  echo "--- tiến trình:"
  adb shell dumpsys activity processes $PKG | grep -E -i "ProcessRecord|procState|curAdj|setAdj|frozen|isFreezeExempt|cached" | head -20
  adb shell su 0 cat /proc/$pid/wchan 2>&1; echo
  adb shell su 0 grep -E "^State" /proc/$pid/status 2>&1
  adb shell su 0 sh -c "cat /sys/fs/cgroup/uid_*/pid_$pid/cgroup.freeze /sys/fs/cgroup/apps/uid_*/pid_$pid/cgroup.freeze" 2>&1 | head -3
  echo "--- socket cổng 1455 (05AF) [st 0A=LISTEN 01=ESTABLISHED 03=SYN_RECV 08=CLOSE_WAIT; tx_queue:rx_queue]:"
  adb shell su 0 cat /proc/net/tcp /proc/net/tcp6 | grep -i ":05AF" | head -20
  echo "--- HTTP từ tiến trình khác (nc, chờ tối đa 8 s):"
  local t0=$(date +%s%N)
  timeout 20 adb shell "printf 'GET /health HTTP/1.1\r\nHost: 127.0.0.1:1455\r\nConnection: close\r\n\r\n' | timeout 12 nc 127.0.0.1 1455; echo NC_RC=\$?" 2>&1 | head -12
  echo "(NC_RC=124 hoặc không có dòng HTTP = kết nối được nhưng KHÔNG có phản hồi)"
  echo "thời gian: $(( ($(date +%s%N)-t0)/1000000 )) ms"
}

adb shell am start -W -n $PKG/.ui.MainActivity
sleep 4
python3 scripts/diag/ui.py tap "Thử ChatGPT (thử nghiệm)"
sleep 3
python3 scripts/diag/ui.py tap "Continue with ChatGPT"
sleep 5
adb shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity" | head -3
probe "5s sau khi mở trình duyệt"
python3 scripts/diag/ui.py chrome_fre
sleep 25
probe "30s"
sleep 60
probe "90s"
sleep 90
probe "180s"

echo "===== CHROME mở http://127.0.0.1:1455/health"
adb shell am start -a android.intent.action.VIEW -d "http://127.0.0.1:1455/health" com.android.chrome
sleep 3; python3 scripts/diag/ui.py chrome_fre
sleep 35
adb shell screencap -p /sdcard/chrome_health.png; adb pull /sdcard/chrome_health.png $R/ >/dev/null
python3 scripts/diag/ui.py texts | head -40
probe "sau khi Chrome thử"

echo "===== CHROME mở callback giả (state sai, để kiểm tra phản hồi)"
adb shell am start -a android.intent.action.VIEW -d "http://127.0.0.1:1455/auth/callback?state=SAI&code=GIA" com.android.chrome
sleep 35
adb shell screencap -p /sdcard/chrome_cb.png; adb pull /sdcard/chrome_cb.png $R/ >/dev/null
python3 scripts/diag/ui.py texts | head -40

echo "===== Đưa ứng dụng trở lại trước"
adb shell am start -n $PKG/.ui.MainActivity
sleep 5
probe "ứng dụng ở trước"
python3 scripts/diag/ui.py tap "Nhật ký" --optional
python3 scripts/diag/ui.py texts | head -80
adb shell screencap -p /sdcard/app.png; adb pull /sdcard/app.png $R/ >/dev/null
adb logcat -d -v time > $R/logcat_full.txt
grep -E -i "MTV-OAuth|freez|mangatranslate|AndroidRuntime|ForegroundService" $R/logcat_full.txt | head -300 > $R/logcat.txt
exit 0
