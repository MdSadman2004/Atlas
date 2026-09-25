#!/usr/bin/env bash
# Install Atlas on the connected Android device and (optionally) preload the CommandCode key.
# Usage:  bash D:/Atlas/scripts/install.sh [--key] [--serial <serial>]
set -euo pipefail

ADB=${ADB:-D:/Studio/platform-tools/adb.exe}
APK=${APK:-D:/Atlas/app/build/outputs/apk/debug/app-debug.apk}
PKG=com.atlas.agent
ACT=$PKG/.ui.MainActivity
SERIAL=""
WITH_KEY=0

while [ $# -gt 0 ]; do
  case "$1" in
    --key) WITH_KEY=1; shift ;;
    --serial) SERIAL="-s $2"; shift 2 ;;
    *) echo "unknown arg: $1"; exit 2 ;;
  esac
done

echo "== devices =="
$ADB devices -l

echo "== install =="
$ADB $SERIAL install -r "$APK"

if [ "$WITH_KEY" = "1" ]; then
  echo "== preload API key from D:/.hermes/.env (never printed) =="
  TMP="$LOCALAPPDATA/Temp/atlas_settings.xml"
  python - "$TMP" <<'PY'
import os, re, sys, html
out = sys.argv[1]
key = os.environ.get("ATLAS_KEY", "").strip()
if not key:
    for ln in open('D:/.hermes/.env', encoding='utf-8'):
        m = re.match(r'\s*COMMANDCODE_API_KEY\s*=\s*(.*)$', ln)
        if m:
            key = m.group(1).strip().strip('"').strip("'")
            break
if not key:
    print("no key: set ATLAS_KEY in the environment or COMMANDCODE_API_KEY in D:/.hermes/.env", file=sys.stderr)
    sys.exit(1)
xml = (
    "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
    "<map>\n"
    f'    <string name="api_key">{html.escape(key)}</string>\n'
    '    <string name="base_url">https://api.commandcode.ai/provider/v1</string>\n'
    '    <string name="model">deepseek/deepseek-v4.1-flash</string>\n'
    '    <string name="approvals">off</string>\n'
    "</map>\n"
)
open(out, "w", encoding="utf-8").write(xml)
print("prefs written to", out)
PY
  $ADB $SERIAL shell am force-stop $PKG
  $ADB $SERIAL shell "run-as $PKG mkdir -p shared_prefs"
  $ADB $SERIAL shell "run-as $PKG sh -c 'cat > shared_prefs/atlas_settings.xml'" < "$TMP"
  echo "prefs injected: $($ADB $SERIAL shell "run-as $PKG ls -l shared_prefs/atlas_settings.xml" | tr -d '\r')"
  rm -f "$TMP"
fi

echo "== launch =="
$ADB $SERIAL shell am start -n $ACT
sleep 3
$ADB $SERIAL shell dumpsys activity activities | grep -i "$PKG" | head -5
