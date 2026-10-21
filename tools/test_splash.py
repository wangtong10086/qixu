"""Device acceptance for a splash adapter; requires an idle, unlocked phone.

Only starts/stops the selected app. Restores the original ad switch in finally.
Screenshots and diagnostics remain in private/ and must not be published.
"""
import json
import argparse
from pathlib import Path
import shlex
import time

from device import adb, root

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--package', choices=('com.zhihu.android', 'tv.danmaku.bili', 'com.taobao.taobao'), default='com.zhihu.android')
PACKAGE = parser.parse_args().package
MANAGER = 'dev.local.fuxicontrol'
ALLOWED = {PACKAGE, MANAGER, 'com.android.launcher3', 'com.miui.home'}
OUTPUT = Path(__file__).resolve().parents[1] / 'private/splash-acceptance'
OUTPUT.mkdir(parents=True, exist_ok=True)


def rpc(command):
    return json.loads(root('/data/adb/fuxicontrol/fuxictl rpc ' + shlex.quote(json.dumps({'command': command}))))


def state():
    return next(app for app in rpc('ads.status')['apps'] if app['package'] == PACKAGE)


def idle():
    top = next((line for line in adb('shell', 'dumpsys', 'activity', 'activities').splitlines()
                if 'topResumedActivity=' in line), '')
    if not any(package + '/' in top for package in ALLOWED):
        raise RuntimeError('Unexpected foreground or locked device; stop: ' + top)


def wait_for(predicate, description):
    until = time.monotonic() + 15
    while time.monotonic() < until:
        value = state()
        if predicate(value):
            print('PASS ' + description, flush=True)
            return value
        time.sleep(.5)
    raise AssertionError(description + ': ' + json.dumps(value, ensure_ascii=False))


def switch(enabled):
    root('/data/adb/fuxicontrol/fuxictl ads ' + ('enable ' if enabled else 'disable ') + PACKAGE)


before = rpc('config.get')
original = before['apps'].get(PACKAGE, {}).get('blockSplashAds', False)
assert original, 'This acceptance requires an already enabled adapter; enable it explicitly first'
assert state()['matched'], 'Installed version is not supported'
idle()
try:
    previous_hits = state().get('report', {}).get('blocked', 0)
    adb('shell', 'am', 'force-stop', PACKAGE)
    wait_for(lambda app: not app['connected'], 'stopped app has no live watch')
    component = adb('shell', 'cmd', 'package', 'resolve-activity', '--brief', PACKAGE).strip().splitlines()[-1]
    idle()
    adb('shell', 'am', 'start', '-W', '-n', component)
    wait_for(lambda app: app['connected'], 'cold-start app connected')
    hit = wait_for(lambda app: app.get('report', {}).get('blocked', 0) > previous_hits, 'new real splash entry intercepted')
    time.sleep(3)
    idle()
    (OUTPUT / (PACKAGE + '-enabled.png')).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    switch(False)
    assert not state()['enabled'], 'Disable did not reach controller'
    idle()
    adb('shell', 'am', 'force-stop', PACKAGE)
    wait_for(lambda app: not app['connected'], 'disconnect detected without policy change')
    count = state()['report']['blocked']
    idle()
    adb('shell', 'am', 'start', '-W', '-n', component)
    time.sleep(5)
    assert state()['report']['blocked'] == count, 'Disabled adapter intercepted an entry'
    print('PASS disabled cold launch adds no blocked report', flush=True)
finally:
    switch(original)

idle()
adb('shell', 'am', 'force-stop', PACKAGE)
wait_for(lambda app: not app['connected'], 'disabled process exited')
idle()
adb('shell', 'am', 'start', '-W', '-n', component)
wait_for(lambda app: app['connected'] and app['enabled'], 'original enabled state restored')
after = rpc('config.get')
assert {k: v for k, v in before.items() if k != 'revision'} == {k: v for k, v in after.items() if k != 'revision'}, 'Policy content changed'
(OUTPUT / 'status.json').write_text(json.dumps(rpc('ads.status'), ensure_ascii=False, indent=2))
assert rpc('status')['lastError'] == ''
print('PASS policy restored and local service healthy', flush=True)
