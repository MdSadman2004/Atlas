#!/usr/bin/env bash
# Boot a headless Android emulator for testing Atlas when no physical device is attached.
# Usage: bash D:/Atlas/scripts/emulator-up.sh
set -uo pipefail

SDK=${SDK:-D:/Android/SDK}
EMU=${EMU:-D:/Studio/emulator/emulator.exe}
ADB=${ADB:-D:/Studio/platform-tools/adb.exe}
AVD=${AVD:-atlas_test}
IMAGE=${IMAGE:-system-images;android-34;google_apis;x86_64}
export ANDROID_SDK_ROOT=$SDK
export ANDROID_AVD_HOME=${ANDROID_AVD_HOME:-D:/Android/avd}
export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-17.0.20.8-hotspot"
mkdir -p "$ANDROID_AVD_HOME"

if ! "$ADB" devices | grep -q "^emulator-"; then
  if ! "$SDK/cmdline-tools/latest/bin/avdmanager.bat" list avd 2>/dev/null | grep -q "$AVD"; then
    echo "== creating AVD $AVD =="
    echo no | "$SDK/cmdline-tools/latest/bin/avdmanager.bat" create avd \
      -n "$AVD" -k "$IMAGE" -d pixel_6 --force
  fi
  echo "== booting $AVD (headless) =="
  nohup "$EMU" -avd "$AVD" -no-window -no-audio -no-boot-anim \
    -gpu swiftshader_indirect -no-snapshot -netdelay none -netspeed full \
    > D:/Atlas/emulator.log 2>&1 &
  "$ADB" wait-for-device
  for _ in $(seq 1 90); do
    boot=$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
    [ "$boot" = "1" ] && break
    sleep 5
  done
fi

echo "== devices =="
"$ADB" devices -l
echo "boot_completed=$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
echo "api=$("$ADB" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
