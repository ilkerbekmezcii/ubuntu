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
grep -Fq 'ENV DOSYASI' "$OUT/window-initial.xml"
grep -Fq 'MFA' "$OUT/window-initial.xml"

printf 'TENANT_ID=00000000-0000-0000-0000-000000000000\nCLIENT_ID=00000000-0000-0000-0000-000000000000\n' > "$OUT/test.env"
adb push "$OUT/test.env" /sdcard/Download/test.env >/dev/null

python3 - <<'PY'
import re, subprocess, time, unicodedata, xml.etree.ElementTree as ET

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

def norm(value):
    return unicodedata.normalize('NFKD', value).encode('ascii','ignore').decode('ascii').lower()

def find(root, text, contains=False, field='either'):
    wanted=norm(text)
    for n in root.iter('node'):
        values=[]
        if field in ('either','text'): values.append(n.attrib.get('text',''))
        if field in ('either','desc'): values.append(n.attrib.get('content-desc',''))
        for value in values:
            value=norm(value)
            if (wanted in value if contains else wanted == value):
                return n
    return None

root=ET.parse('emulator-test/window-initial.xml').getroot()
btn=find(root,'.env dosyasi sec')
if btn is None:
    raise RuntimeError('ENV button not found')
tap_node(btn)
time.sleep(2)

root=dump('/sdcard/picker.xml','emulator-test/picker.xml')
roots=find(root,'Show roots',field='desc')
if roots is None:
    raise RuntimeError('DocumentsUI roots button not found')
tap_node(roots)
time.sleep(1)
root=dump('/sdcard/roots.xml','emulator-test/roots.xml')
downloads=find(root,'Downloads',field='text')
if downloads is None:
    raise RuntimeError('Downloads root not found')
tap_node(downloads)
time.sleep(2)
root=dump('/sdcard/downloads-grid.xml','emulator-test/downloads-grid.xml')

list_view=find(root,'List view',field='desc')
if list_view is not None:
    tap_node(list_view)
    time.sleep(1)
    root=dump('/sdcard/downloads-list.xml','emulator-test/downloads-list.xml')

file_node=find(root,'test.env',field='text')
if file_node is None:
    raise RuntimeError('test.env not found in Downloads')
# Tap the file name itself; in list mode this selects/opens the document rather than previewing it.
tap_node(file_node)
PY

sleep 4
adb shell dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp' > "$OUT/focus-after-select.txt" || true
adb shell uiautomator dump /sdcard/window-after-select.xml >/dev/null || true
adb pull /sdcard/window-after-select.xml "$OUT/window-after-select.xml" >/dev/null || true
adb exec-out screencap -p > "$OUT/after-select.png" || true
adb exec-out run-as "$PKG" pwd > "$OUT/app-pwd.txt"
adb exec-out run-as "$PKG" ls -la > "$OUT/app-files.txt" || true
adb exec-out run-as "$PKG" ls -la shared_prefs > "$OUT/shared-prefs-list.txt"
adb exec-out run-as "$PKG" cat shared_prefs/auth_store.xml > "$OUT/auth_store.xml"
grep -Fq 'name="tenant_id"' "$OUT/auth_store.xml"
grep -Fq 'name="client_id"' "$OUT/auth_store.xml"

adb shell am force-stop "$PKG" || true
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
