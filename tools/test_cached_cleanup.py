"""Screen-off acceptance on an idle device with the probe installed.

Changes only the probe policy; restores policy and wakes the screen in finally.
The secure lock screen may require the user to unlock afterward.
"""
import sys,json,shlex,time,subprocess
from device import root,adb
P='dev.local.fuxicontrol.probe';C=P+'.LifecycleService'
def rpc(command,**args):return json.loads(root('/data/adb/fuxicontrol/fuxictl rpc '+shlex.quote(json.dumps(dict(command=command,**args)))))
top = '\n'.join(line for line in adb('shell','dumpsys','activity','activities').splitlines() if 'topResumedActivity=' in line)
if top and not any(name+'/' in top for name in (P,'dev.local.fuxicontrol','com.miui.home','com.android.launcher3')):
 raise RuntimeError('Device is in use; stop: '+top)
before=rpc('config.get')
try:
 adb('shell','input','keyevent','KEYCODE_WAKEUP')
 rpc('app.patch',package=P,patch=dict(autostart='allow',background='normal',screenOffCleanup='cached',screenOffDelaySeconds=10,cleanupOnTaskRemoved=False))
 print(root('am start-foreground-service -n '+P+'/'+C+' --ez foreground true'))
 time.sleep(2)
 # This ROM returns 255 even when am reports a successful stop.
 stopped=subprocess.run(['adb','shell','su -c '+shlex.quote('am stop-service -n '+P+'/'+C)],capture_output=True,text=True)
 assert stopped.returncode in (0,255) and 'Service stopped' in stopped.stdout+stopped.stderr, stopped
 assert not next(s for s in rpc('services.list',package=P)['services'] if s['class']==C)['running']
 assert root('pidof '+P+' || true').strip()
 adb('shell','input','keyevent','KEYCODE_SLEEP');time.sleep(14)
 r=[r for r in rpc('audit')['records'] if r['event'].startswith('screen-cleanup') and r['data'].get('package')==P]
 print(json.dumps(r[-1:]),flush=True)
 assert r[-1]['event']=='screen-cleanup-requested' and r[-1]['data']['action']=='cached'
 assert not root('pidof '+P+' || true').strip()
 print('PASS cached process exited after screen-off delay',flush=True)
finally:
 adb('shell','input','keyevent','KEYCODE_WAKEUP')
 root('am force-stop '+P)
 if P in before['apps']:rpc('app.replace',package=P,patch=before['apps'][P])
 else:rpc('app.inherit',package=P)
 after=rpc('config.get')
 assert before['apps']==after['apps'] and before['defaults']==after['defaults']
 print('PASS policy restored',flush=True)
