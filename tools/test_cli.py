"""Check desired-state mutations and validation in observation mode, restoring all edits."""
import json
from pathlib import Path
import shlex
import subprocess
import time
from device import adb,root

project=Path(__file__).resolve().parents[1]
def rpc(value): return json.loads(root('/data/adb/fuxicontrol/fuxictl rpc '+shlex.quote(json.dumps(value))))
original=rpc({'command':'config.get'})
if original['mode']!='observe': raise RuntimeError('Run this fixture only in observation mode')
name='dev.local.fuxicontrol.probe'
try:
    preview=rpc({'command':'defaults.patch','patch':{'cpuNice':9},'dryRun':True})
    if rpc({'command':'config.get'})!=original: raise RuntimeError('Dry run mutated configuration')
    print('PASS dry run leaves configuration unchanged',flush=True)
    invalid=json.loads(json.dumps(original));invalid['apps']['dev.local.notinstalled']={'permissions':{'android.permission.INTERNET':'deny'}}
    try: rpc({'command':'config.replace','config':invalid})
    except subprocess.CalledProcessError: pass
    else: raise RuntimeError('Normal permission accepted for a future package')
    if rpc({'command':'config.get'})!=original: raise RuntimeError('Rejected input mutated configuration')
    print('PASS future-package permission validation before persistence',flush=True)
    rpc({'command':'defaults.patch','patch':{'cpuNice':9}})
    rpc({'command':'app.replace','package':name,'patch':{'cpuNice':12,'uclampMax':512}})
    rpc({'command':'app.replace','package':name,'patch':{'cpuNice':11}})
    own=rpc({'command':'app.get','package':name})
    if own['overrides']!={'cpuNice':11} or own['effective']['uclampMax']!=1024: raise RuntimeError('Replacing overrides retained a deleted field')
    rpc({'command':'app.inherit','package':name})
    own=rpc({'command':'app.get','package':name})
    if own['effective']['cpuNice']!=9 or own['effective']['sources']['cpuNice']!='default': raise RuntimeError('Default inheritance failed')
    wallet=rpc({'command':'app.get','package':'com.google.android.apps.walletnfcrel'})
    if wallet['effective']['cpuNice']!=0 or wallet['effective']['autostart']!='allow': raise RuntimeError('Protected wallet inherited ordinary restrictions')
    print('PASS override replacement, inheritance and protected Wallet baseline',flush=True)
    # Own disposable APK has no user data; reinstall exercises actual package observation.
    adb('uninstall',name)
    adb('install','--no-incremental','--no-streaming',str(project/'probe/build/outputs/apk/debug/probe-debug.apk'))
    deadline=time.monotonic()+15
    while name not in [item['package'] for item in rpc({'command':'apps'})['apps']]:
        if time.monotonic()>deadline: raise RuntimeError('Package catalog did not refresh')
        time.sleep(0.25)
    own=rpc({'command':'app.get','package':name})
    if own['overrides'] or own['effective']['cpuNice']!=9: raise RuntimeError('Reinstalled probe did not inherit defaults')
    print('PASS reinstalled application inherits current template',flush=True)
    before=rpc({'command':'config.get'})
    try: rpc({'command':'defaults.patch','patch':{'cpuNice':8},'expectedRevision':before['revision']-1})
    except subprocess.CalledProcessError: pass
    else: raise RuntimeError('Stale revision overwrote configuration')
    if rpc({'command':'config.get'})!=before: raise RuntimeError('Stale write changed configuration')
    print('PASS stale edits cannot overwrite a newer configuration',flush=True)
finally:
    rpc({'command':'config.replace','config':original})
    print('Restored original observation configuration',flush=True)
