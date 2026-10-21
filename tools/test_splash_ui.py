"""Read-only acceptance of the splash status page on an idle, unlocked phone."""
from pathlib import Path
import argparse
import re
import time
import xml.etree.ElementTree as ET

from device import adb

PACKAGE = 'dev.local.fuxicontrol'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--app-label', choices=('知乎', '哔哩哔哩', '淘宝'), default='知乎')
APP_LABEL = parser.parse_args().app_label
OUTPUT = Path(__file__).resolve().parents[1] / 'private/splash-acceptance'
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


assert any(name + '/' in foreground() for name in (PACKAGE, 'com.zhihu.android', 'tv.danmaku.bili', 'com.taobao.taobao', 'com.android.launcher3', 'com.miui.home')), 'Device is not idle'
# NEW_TASK | CLEAR_TASK starts from the overview rather than a retained detail page.
adb('shell', 'am', 'start', '-W', '-f', '0x10008000', '-n', PACKAGE + '/.ui.MainActivity')
tap('策略')
tap('开屏广告')
deadline = time.monotonic() + 15
while True:
    page = nodes()
    if any(n.get('text') == '知乎' for n in page) or time.monotonic() >= deadline:
        break
    time.sleep(.5)
for _ in range(2):
    scroll(True)
    page += nodes()
for label in ('哔哩哔哩', '知乎', 'QQ音乐', '京东', '小红书', '高德地图', '淘宝'):
    assert any(n.get('text') == label for n in page), 'Missing app: ' + label
assert len({n.get('content-desc') for n in page if n.get('content-desc', '').endswith('图标')}) >= 7
scroll(False)
(OUTPUT / 'status-page.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
for _ in range(4):
    if any(n.get('text') == APP_LABEL for n in nodes()): break
    scroll(True)
tap(APP_LABEL)
assert any(n.get('text') == '启动与后台' for n in nodes())
tap('返回')
assert any(n.get('text') == '开屏广告' for n in nodes())
print('PASS status page, seven app icons, detail navigation and return')
