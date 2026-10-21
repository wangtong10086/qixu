"""Validate root Binder service-stop results on the disposable probe only."""
import json
import os
from pathlib import Path
import shlex
import subprocess
import time
from device import root

PACKAGE = 'dev.local.fuxicontrol.probe'
SERVICE = PACKAGE + '.LifecycleService'
CLI = '/data/adb/fuxicontrol/fuxictl rpc '

def rpc(command, **args):
    return json.loads(root(CLI + shlex.quote(json.dumps(dict(command=command, **args)))))

def state():
    return next(row for row in rpc('services.list', package=PACKAGE)['services'] if row['class'] == SERVICE)

before = rpc('config.get')
try:
    rpc('app.patch', package=PACKAGE, patch=dict(autostart='allow', background='normal', screenOffCleanup='off'))
    assert not rpc('service.stop', package=PACKAGE, **{'class': SERVICE})['stopped']
    print('PASS inactive service returns stopped=false', flush=True)
    root('am start-foreground-service -n ' + PACKAGE + '/' + SERVICE + ' --ez foreground true')
    time.sleep(2)
    assert state()['foreground']
    command = CLI + shlex.quote(json.dumps(dict(command='service.stop', package=PACKAGE, **{'class': SERVICE})))
    denied = subprocess.run([os.environ.get('ADB', 'adb'), 'shell', 'su -c ' + shlex.quote(command)], capture_output=True, text=True)
    assert denied.returncode == 1 and '当前应用受保护' in json.loads(denied.stderr)['error'], denied
    assert state()['running']
    print('PASS active foreground service is protected', flush=True)
    # The ROM's am reports success using exit 255; confirm its message and actual component state.
    stopped = subprocess.run([os.environ.get('ADB', 'adb'), 'shell', 'su -c ' + shlex.quote('am stop-service -n ' + PACKAGE + '/' + SERVICE)], capture_output=True, text=True)
    assert stopped.returncode in (0, 255) and 'Service stopped' in stopped.stdout + stopped.stderr, stopped
    assert not state()['running']
    root('am start-service -n ' + PACKAGE + '/' + SERVICE)
    time.sleep(2)
    assert state()['running'] and not state()['foreground']
    assert rpc('service.stop', package=PACKAGE, **{'class': SERVICE})['stopped']
    assert not state()['running']
    assert not rpc('service.stop', package=PACKAGE, **{'class': SERVICE})['stopped']
    print('PASS background service stops once and reports false on second stop', flush=True)
finally:
    root('am force-stop ' + PACKAGE)
    if PACKAGE in before['apps']:
        rpc('app.replace', package=PACKAGE, patch=before['apps'][PACKAGE])
    else:
        rpc('app.inherit', package=PACKAGE)
    after = rpc('config.get')
    assert before['apps'] == after['apps'] and before['defaults'] == after['defaults']
    print('PASS original policy restored', flush=True)
