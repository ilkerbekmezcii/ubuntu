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

printf 'TENANT_ID=00000000-0000-0000-0000-000000000000\nCLIENT_ID=11111111-1111-1111-1111-111111111111\n' > "$OUT/test.env"
adb push "$OUT/test.env" /sdcard/Download/test.env >/dev/null

python3 - <<'PY'
import re, subprocess, time, unicodedata, xml.etree.ElementTree as ET

def adb(*args):
    subprocess.check_call(['adb', *args])

def dump(remote, local):
    adb('shell','uiautomator','dump',remote)
    adb('pull',remote,local)
    return ET.parse(local).getroot()

def bounds(node):
    m=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.attrib.get('bounds',''))
    if not m:
        raise RuntimeError('node has no usable bounds')
    return tuple(map(int,m.groups()))

def tap_node(node):
    x1,y1,x2,y2=bounds(node)
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))

def norm(value):
    return unicodedata.normalize('NFKD', value).encode('ascii','ignore').decode('ascii').lower()

def find(root, text, contains=False, field='either'):
    wanted=norm(text)
    for n in root.iter('node'):
        vals=[]
        if field in ('either','text'): vals.append(n.attrib.get('text',''))
        if field in ('either','desc'): vals.append(n.attrib.get('content-desc',''))
        for value in vals:
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

# DocumentsUI's title TextView itself is not clickable. Tap the containing item row,
# well away from the preview icon at the right edge.
parents={child:parent for parent in root.iter() for child in parent}
row=file_node
while row is not None and row.attrib.get('resource-id')!='com.android.documentsui:id/item_root':
    row=parents.get(row)
if row is None:
    raise RuntimeError('test.env item row not found')
x1,y1,x2,y2=bounds(row)
x=max(x1+80, min(x2-300, (x1+x2)//2))
y=(y1+y2)//2
print(f'Tapping document row at {x},{y}')
adb('shell','input','tap',str(x),str(y))
PY

# The file parse runs on a background executor. Give it time to persist encrypted settings.
FOUND=0
for _ in $(seq 1 30); do
  if adb shell run-as "$PKG" ls shared_prefs/auth_store.xml >/dev/null 2>&1; then
    FOUND=1
    break
  fi
  sleep 0.5
done

adb shell dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp' > "$OUT/focus-after-select.txt" || true
adb shell uiautomator dump /sdcard/window-after-select.xml >/dev/null || true
adb pull /sdcard/window-after-select.xml "$OUT/window-after-select.xml" >/dev/null || true
adb exec-out screencap -p > "$OUT/after-select.png" || true
adb exec-out run-as "$PKG" pwd > "$OUT/app-pwd.txt"
adb exec-out run-as "$PKG" ls -la > "$OUT/app-files.txt" || true
adb exec-out run-as "$PKG" ls -la shared_prefs > "$OUT/shared-prefs-list.txt" || true

if [ "$FOUND" -ne 1 ]; then
  echo 'Encrypted auth_store.xml was not created after selecting .env'
  exit 1
fi

adb exec-out run-as "$PKG" cat shared_prefs/auth_store.xml > "$OUT/auth_store.xml"
grep -Fq 'name="tenant_id"' "$OUT/auth_store.xml"
grep -Fq 'name="client_id"' "$OUT/auth_store.xml"
if grep -Fq '00000000-0000-0000-0000-000000000000' "$OUT/auth_store.xml" || grep -Fq '11111111-1111-1111-1111-111111111111' "$OUT/auth_store.xml"; then
  echo 'OAuth identifiers were stored in plaintext'
  exit 1
fi

# Relaunch to prove the stored encrypted configuration can be read without crashing.
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

grep -Fq 'Microsoft Earnings' "$OUT/window-after-env.xml"
echo 'EMULATOR_SMOKE_TEST_OK'
