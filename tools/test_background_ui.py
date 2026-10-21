"""Read-only acceptance of the background service page on an idle, unlocked phone."""
from pathlib import Path
import re
import time
import xml.etree.ElementTree as ET

from device import adb

PACKAGE = 'dev.local.fuxicontrol'
OUTPUT = Path(__file__).resolve().parents[1] / 'private/background-acceptance'
OUTPUT.mkdir(parents=True, exist_ok=True)


def foreground():
    return next((line for line in adb('shell', 'dumpsys', 'activity', 'activities').splitlines()
                 if 'topResumedActivity=' in line), '')


def nodes():
    assert PACKAGE + '/' in foreground(), 'Another app is foreground; stop UI test'
    adb('shell', 'uiautomator', 'dump', '/data/local/tmp/qixu-splash-ui.xml')
    xml = adb('shell', 'cat', '/data/local/tmp/qixu-splash-ui.xml')
    adb('shell', 'rm', '/data/local/tmp/qixu-splash-ui.xml')
    return list(ET.fromstring(xml).iter('node'))


def tap(label):
    deadline = time.monotonic() + 15
    while True:
        page = nodes()
        targets = [n for n in page if n.get('text') == label or n.get('content-desc') == label]
        buttons = []
        for target in targets:
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', target.get('bounds')))
            for node in page:
                if node.get('clickable') != 'true' or node.get('enabled') != 'true':
                    continue
                left, top, right, bottom = map(int, re.findall(r'\d+', node.get('bounds')))
                if left <= x1 <= x2 <= right and top <= y1 <= y2 <= bottom:
                    buttons.append((left, top, right, bottom))
        if buttons:
            left, top, right, bottom = min(buttons, key=lambda b: (b[2]-b[0])*(b[3]-b[1]))
            break
        assert time.monotonic() < deadline, 'No enabled button: ' + label
        time.sleep(.5)
    assert PACKAGE + '/' in foreground(), 'Another app is foreground; stop UI test'
    adb('shell', 'input', 'tap', str((left + right) // 2), str((top + bottom) // 2))
    time.sleep(.7)


def scroll(down):
    node = next(n for n in nodes() if n.get('scrollable') == 'true')
    left, top, right, bottom = map(int, re.findall(r'\d+', node.get('bounds')))
    start, end = (bottom - 80, top + 80) if down else (top + 80, bottom - 80)
    assert PACKAGE + '/' in foreground(), 'Another app is foreground; stop UI test'
    adb('shell', 'input', 'swipe', str((left + right) // 2), str(start), str((left + right) // 2), str(end), '350')
    time.sleep(.5)


assert any(name+'/' in foreground() for name in (PACKAGE,'com.miui.home','com.android.launcher3')), 'Device is not idle'
adb('shell','am','start','-W','-f','0x10008000','-n',PACKAGE+'/.ui.MainActivity')
tap('应用')
tap('搜索应用、包名或权限')
adb('shell','input','text','probe')
adb('shell','input','keyevent','KEYCODE_ENTER')
time.sleep(2)
page=nodes()
assert any(n.get('text')=='Fuxi validation probe' for n in page)
tap('Fuxi validation probe')
tap('后台服务')
page=nodes()
assert any(n.get('text')=='搜索服务名称或进程' for n in page)
(OUTPUT/'services-page.png').write_bytes(adb('exec-out','screencap','-p',binary=True))
tap('返回')
assert any(n.get('text')=='启动与后台' for n in nodes())
print('PASS service inventory navigation and return',flush=True)
