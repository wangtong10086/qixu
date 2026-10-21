"""Screen-off acceptance on an idle device with the probe installed.

Changes only the probe policy; restores policy and wakes the screen in finally.
The secure lock screen may require the user to unlock afterward.
"""
import sys,json,shlex,time
from pathlib import Path
from device import root,adb
P='dev.local.fuxicontrol.probe';C=P+'.LifecycleService'
def rpc(command,**args):
 return json.loads(root('/data/adb/fuxicontrol/fuxictl rpc '+shlex.quote(json.dumps(dict(command=command,**args)))))
def records():
 return [r for r in rpc('audit')['records'] if r['event'].startswith('screen-cleanup') and r['data'].get('package')==P]
def row():
 return next(x for x in rpc('services.list',package=P)['services'] if x['class']==C)
def key(k): adb('shell','input','keyevent',k)
def patch(**p):rpc('app.patch',package=P,patch=p)
top = '\n'.join(line for line in adb('shell','dumpsys','activity','activities').splitlines() if 'topResumedActivity=' in line)
if top and not any(name+'/' in top for name in (P,'dev.local.fuxicontrol','com.miui.home','com.android.launcher3')):
 raise RuntimeError('Device is in use; stop: '+top)
before=rpc('config.get')
key('KEYCODE_WAKEUP')
try:
 patch(autostart='allow',background='normal',screenOffCleanup='force-stop',screenOffDelaySeconds=10,cleanupOnTaskRemoved=False)
 root('am start-foreground-service -n '+P+'/'+C+' --ez foreground true')
 time.sleep(2)
 assert row()['running'] and row()['foreground'],row()
 print('PASS foreground service inventory',flush=True)
 key('KEYCODE_SLEEP');time.sleep(14)
 r=records(); print(json.dumps(r[-2:]),flush=True)
 assert row()['running'] and row()['foreground']
 assert r[-1]['event']=='screen-cleanup-skipped' and r[-1]['data']['reason']=='foreground-service',r
 print('PASS foreground service survives explicit force-stop policy',flush=True)
 key('KEYCODE_WAKEUP')
 root('am force-stop '+P)
 root('am start-service -n '+P+'/'+C)
 time.sleep(2)
 assert row()['running'] and not row()['foreground'],row()
 key('KEYCODE_SLEEP');time.sleep(3)
 assert P in rpc('status')['screenCleanup']['pending']
 key('KEYCODE_WAKEUP');time.sleep(1)
 assert P not in rpc('status')['screenCleanup']['pending']
 time.sleep(7)
 assert rpc('status')['screenCleanup']['interactive']
 assert row()['running']
 print('PASS waking cancels cleanup',flush=True)
 key('KEYCODE_SLEEP');time.sleep(14)
 r=records(); print(json.dumps(r[-2:]),flush=True)
 assert not row()['running']
 assert r[-1]['event']=='screen-cleanup-requested' and r[-1]['data']['action']=='force-stop',r
 print('PASS explicit force-stop ends probe background service',flush=True)
finally:
 key('KEYCODE_WAKEUP')
 root('am force-stop '+P)
 if P in before['apps']:rpc('app.replace',package=P,patch=before['apps'][P])
 else:rpc('app.inherit',package=P)
 after=rpc('config.get')
 assert before['apps']==after['apps'] and before['defaults']==after['defaults']
 print('PASS original policy restored',flush=True)
 assert rpc('status')['lastError']==''
