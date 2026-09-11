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

PKG='com.ilker.microsoftreport'
DOCS='com.android.documentsui'

def adb(*args):
    subprocess.check_call(['adb', *args])

def adb_text(*args):
    return subprocess.check_output(['adb', *args], text=True, errors='ignore')

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

def resumed_package():
    text=adb_text('shell','dumpsys','activity','activities')
    for line in text.splitlines():
        if 'mResumedActivity' in line or 'topResumedActivity' in line:
            if PKG in line: return PKG
            if DOCS in line: return DOCS
    return ''

def returned_to_app():
    time.sleep(0.8)
    return resumed_package()==PKG

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
parents={child:parent for parent in root.iter() for child in parent}
row=file_node
while row is not None and row.attrib.get('resource-id')!='com.android.documentsui:id/item_root':
    row=parents.get(row)
if row is None:
    raise RuntimeError('test.env item row not found')

# DocumentsUI behavior differs between emulator/API versions. Try the visible title,
# file icon, and row body first; none of these touch the preview button at the right.
fx1,fy1,fx2,fy2=bounds(file_node)
rx1,ry1,rx2,ry2=bounds(row)
candidates=[
    ((fx1+fx2)//2,(fy1+fy2)//2),
    (max(rx1+90,1),(ry1+ry2)//2),
    (max(rx1+220,1),(ry1+ry2)//2),
]
for x,y in candidates:
    print(f'Trying document tap at {x},{y}')
    adb('shell','input','tap',str(x),str(y))
    if returned_to_app():
        print('Document returned to app by touch')
        break
else:
    # Keyboard/accessibility fallback. item_root is focusable even when DocumentsUI
    # reports clickable=false. TAB until that row owns focus, then ENTER.
    for i in range(20):
        adb('shell','input','keyevent','61')  # KEYCODE_TAB
        time.sleep(0.15)
        focused=dump('/sdcard/focus.xml','emulator-test/focus.xml')
        focused_nodes=[n for n in focused.iter('node') if n.attrib.get('focused')=='true']
        hit=False
        for n in focused_nodes:
            if n.attrib.get('resource-id')=='com.android.documentsui:id/item_root':
                if any(c.attrib.get('text')=='test.env' for c in n.iter('node')):
                    hit=True
                    break
        if hit:
            print(f'test.env row focused after {i+1} TAB presses; pressing ENTER')
            adb('shell','input','keyevent','66')  # KEYCODE_ENTER
            if returned_to_app():
                print('Document returned to app by keyboard')
                break
    else:
        # Last fallback for headless emulator keyboard navigation: move into the list
        # and activate the current row.
        adb('shell','input','keyevent','20')
        adb('shell','input','keyevent','66')
        if not returned_to_app():
            raise RuntimeError('DocumentsUI did not return test.env to the app')

if resumed_package()!=PKG:
    raise RuntimeError('App did not resume after selecting test.env')
PY

FOUND=0
for _ in $(seq 1 40); do
  if adb shell run-as "$PKG" ls shared_prefs/auth_store.xml >/dev/null 2>&1; then
    FOUND=1
    break
  fi
  sleep 0.5
done

adb shell dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' > "$OUT/focus-after-select.txt" || true
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
