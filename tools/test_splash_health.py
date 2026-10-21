"""Real Android exit-history acceptance, using only the disposable probe and an isolated store.

Build app release and probe debug first; requires an unlocked phone at the launcher.
The production registry never trusts a test package or certificate.
"""
import json
import os
from pathlib import Path
import shlex
import subprocess
import time
from device import adb, root

PROJECT = Path(__file__).resolve().parents[1]
PACKAGE = 'dev.local.fuxicontrol.probe'
REMOTE = '/data/local/tmp/qixu-splash-health-acceptance'
OUT = PROJECT / 'private/splash-health-acceptance'
OUT.mkdir(exist_ok=True)
SDK = Path(next(x.split('=', 1)[1] for x in (PROJECT / 'local.properties').read_text().splitlines() if x.startswith('sdk.dir=')))
ANDROID = SDK / 'platforms/android-36/android.jar'
CLASSES = PROJECT / 'app/build/intermediates/compile_app_classes_jar/release/bundleReleaseClassesToCompileJar/classes.jar'
javac = str(Path(os.environ['JAVA_HOME']) / 'bin/javac')
subprocess.run([javac, '--release', '17', '-cp', str(ANDROID)+':'+str(CLASSES)+':'+str(PROJECT/'core/build/classes/java/main'),
                '-d', str(OUT), str(PROJECT/'tools/device-tests/SplashHealthDeviceTest.java')], check=True)
subprocess.run([str(SDK/'build-tools/35.0.0/d8'), '--lib', str(ANDROID), '--min-api', '36',
                '--output', str(OUT), str(OUT/'SplashHealthDeviceTest.class')], check=True)


def idle():
    top = next((x for x in adb('shell', 'dumpsys', 'activity', 'activities').splitlines() if 'topResumedActivity=' in x), '')
    assert any(p+'/' in top for p in (PACKAGE, 'dev.local.fuxicontrol', 'com.miui.home', 'com.android.launcher3')), 'Unexpected foreground: '+top


def run(action, pid):
    result = root('CLASSPATH='+REMOTE+'/runner.dex:'+REMOTE+'/candidate.apk app_process /system/bin SplashHealthDeviceTest '+action+' '+str(pid))
    return json.loads(result)


def launch(crash=False):
    idle()
    adb('shell', 'am', 'start', '-W', '-n', PACKAGE+'/.SplashFailure', '--ez', 'crash', str(crash).lower())
    return int(adb('shell', 'pidof', PACKAGE).strip())


assert not adb('shell', 'pm', 'list', 'packages', PACKAGE).strip(), 'Probe already installed; do not replace it'
idle()
root('umask 077; mkdir '+REMOTE)
installed = False
try:
    for source, filename in [(OUT/'classes.dex', 'runner.dex'),
                             (PROJECT/'app/build/outputs/apk/release/app-release-unsigned.apk', 'candidate.apk')]:
        staging='/data/local/tmp/qixu-health-'+filename
        adb('push', str(source), staging)
        root('mv '+staging+' '+REMOTE+'/'+filename)
    adb('install', '--no-incremental', str(PROJECT/'probe/build/outputs/apk/debug/probe-debug.apk'))
    installed = True
    for index in range(2):
        pid = launch(True)
        assert not run('arm', pid)['suspended']
        deadline = time.monotonic()+20
        while root('pidof '+PACKAGE+' || true').strip():
            assert time.monotonic() < deadline, 'Probe did not crash'
            time.sleep(.5)
        adb('shell', 'am', 'force-stop', PACKAGE)
        print('PASS intentional probe crash '+str(index+1), flush=True)
    pid = launch()
    result = run('inspect', pid)
    assert result['suspended'], result
    assert run('inspect', pid)['suspended'], 'Suspension did not persist across controller instances'
    assert not run('retry', pid)['suspended']
    assert not run('inspect', pid)['suspended']
    (OUT/'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print('PASS real crash records, per-install suspension, persistence and retry', flush=True)
finally:
    if installed:
        adb('shell', 'am', 'force-stop', PACKAGE)
        adb('uninstall', PACKAGE)
    root('rm -rf '+shlex.quote(REMOTE))
