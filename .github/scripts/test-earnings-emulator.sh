#!/usr/bin/env bash
set -euo pipefail

APK="android-earnings/app/build/outputs/apk/debug/app-debug.apk"
PKG="com.ilker.microsoftreport"
ACT=".MainActivity"
OUT="emulator-test"

mkdir -p "$OUT"
adb wait-for-device
adb install -r "$APK"
adb logcat -c
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/$ACT"
sleep 3

adb shell uiautomator dump /sdcard/window.xml >/dev/null
adb pull /sdcard/window.xml "$OUT/window-initial.xml" >/dev/null
adb exec-out screencap -p > "$OUT/initial.png"

grep -Fq 'Microsoft Earnings' "$OUT/window-initial.xml"
grep -Fq 'Bugün' "$OUT/window-initial.xml"
grep -Fq 'Bu Ay' "$OUT/window-initial.xml"
grep -Fq '.env dosyası seç' "$OUT/window-initial.xml"
grep -Fq 'Microsoft ile giriş / MFA' "$OUT/window-initial.xml"

printf 'TENANT_ID=00000000-0000-0000-0000-000000000000\nCLIENT_ID=00000000-0000-0000-0000-000000000000\n' > "$OUT/test.env"
adb push "$OUT/test.env" /sdcard/Download/test.env >/dev/null

python3 - <<'PY'
import re, subprocess, time, xml.etree.ElementTree as ET

def adb(*args):
    subprocess.check_call(['adb', *args])

def dump(remote, local):
    adb('shell','uiautomator','dump',remote)
    adb('pull',remote,local)
    return ET.parse(local).getroot()

def tap_node(node):
    m=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.attrib.get('bounds',''))
    if not m:
        raise RuntimeError('node has no usable bounds')
    x=(int(m.group(1))+int(m.group(3)))//2
    y=(int(m.group(2))+int(m.group(4)))//2
    adb('shell','input','tap',str(x),str(y))

def find(root, text, contains=False):
    for n in root.iter('node'):
        values=(n.attrib.get('text',''), n.attrib.get('content-desc',''))
        if any((text in v if contains else text == v) for v in values):
            return n
    return None

root=ET.parse('emulator-test/window-initial.xml').getroot()
btn=find(root,'.env dosyası seç')
if btn is None:
    raise RuntimeError('ENV button not found')
tap_node(btn)
time.sleep(2)

root=dump('/sdcard/picker.xml','emulator-test/picker.xml')
file_node=find(root,'test.env',contains=True)
if file_node is None:
    downloads=find(root,'Downloads')
    if downloads is not None:
        tap_node(downloads)
        time.sleep(2)
        root=dump('/sdcard/picker2.xml','emulator-test/picker2.xml')
        file_node=find(root,'test.env',contains=True)
if file_node is None:
    raise RuntimeError('test.env not found in picker')
tap_node(file_node)
PY

sleep 2
adb shell am force-stop "$PKG" || true
adb shell run-as "$PKG" cat shared_prefs/auth_store.xml > "$OUT/auth_store.xml"
grep -Fq 'name="tenant_id"' "$OUT/auth_store.xml"
grep -Fq 'name="client_id"' "$OUT/auth_store.xml"

adb shell am start -W -n "$PKG/$ACT" >/dev/null
sleep 2
adb shell uiautomator dump /sdcard/window-after-env.xml >/dev/null
adb pull /sdcard/window-after-env.xml "$OUT/window-after-env.xml" >/dev/null
adb exec-out screencap -p > "$OUT/after-env.png"
adb logcat -d > "$OUT/logcat.txt"

if grep -Fq 'FATAL EXCEPTION' "$OUT/logcat.txt"; then
  echo 'App crash detected'
  tail -n 250 "$OUT/logcat.txt"
  exit 1
fi

echo 'EMULATOR_SMOKE_TEST_OK'
