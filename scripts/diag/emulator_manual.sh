#!/usr/bin/env bash
# Tự dựng trình giả lập (không dùng action) và ghi lại mọi chẩn đoán khởi động vào res/.
set -x
mkdir -p res
SDK="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
export PATH="$SDK/cmdline-tools/latest/bin:$SDK/emulator:$SDK/platform-tools:$PATH"
{ echo "SDK=$SDK"; ls -l /dev/kvm; nproc; free -m; df -h /; } > res/env.txt 2>&1
IMG="system-images;android-${API:-30};google_apis;x86_64"
yes | sdkmanager --install "emulator" "platform-tools" "$IMG" > res/sdkmanager.txt 2>&1
export ANDROID_AVD_HOME="$HOME/.android/avd"
mkdir -p "$ANDROID_AVD_HOME"
echo no | avdmanager create avd -n diag -k "$IMG" -d pixel_4 --force -p "$ANDROID_AVD_HOME/diag.avd" > res/avd.txt 2>&1
# Phân vùng dữ liệu nhỏ hơn (mặc định 6 GB) để vừa đĩa máy CI.
printf "disk.dataPartition.size=2G\nhw.ramSize=3072\n" >> "$ANDROID_AVD_HOME/diag.avd/config.ini"
{ echo "--- AVD:"; ls -la "$ANDROID_AVD_HOME"; emulator -list-avds; df -h /; } >> res/avd.txt 2>&1
emulator -accel-check > res/accel.txt 2>&1
nohup emulator -avd diag -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 3072 > res/emulator.log 2>&1 &
adb start-server
booted=0
for i in $(seq 1 120); do
  if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then booted=1; break; fi
  sleep 10
done
echo "booted=$booted after $((i*10))s" >> res/env.txt
if [ "$booted" = "1" ]; then
  adb shell settings put global window_animation_scale 0
  adb shell settings put global transition_animation_scale 0
  adb shell settings put global animator_duration_scale 0
  bash "${RUN_SCRIPT:-scripts/diag/run_on_emulator.sh}" > res/run.txt 2>&1
  for f in diag_app.txt instrument.txt logcat.txt; do [ -f "$f" ] && cp "$f" res/; done
fi
exit 0
