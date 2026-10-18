"""Exercise offline recovery against policies applied only to the disposable probe."""
import json
from pathlib import Path
import shlex
import time
from device import adb,root

project=Path(__file__).resolve().parents[1]
def rpc(value): return json.loads(root('/data/adb/fuxicontrol/fuxictl rpc '+shlex.quote(json.dumps(value))))
original=rpc({'command':'config.get'});name='dev.local.fuxicontrol.probe'
if original['mode']!='observe': raise RuntimeError('Recovery fixture starts from observation mode')
config=json.loads(json.dumps(original));config.update(mode='enforce',apps={},rules=[],pushProxy={})
config['defaults'].update(autostart='allow',background='normal',cpuNice=0,uclampMax=1024,memoryHighMiB=0,memoryMaxMiB=0,permissions={},appOps={},uploadBps=0,downloadBps=0,network='allow',resident=False,wakeLocks=[],cleanupOnTaskRemoved=False)
config['apps']={name:{'cpuNice':9,'uclampMax':512,'memoryHighMiB':64,'uploadBps':512000,'permissions':{'android.permission.CAMERA':'deny'},'appOps':{'android:camera':'ignore'},'background':'restricted'}}
vector='CLASSPATH=/data/adb/modules/zygisk_vector/daemon.apk app_process /system/bin org.matrix.vector.daemon.Cli '
try:
    adb('shell','am','force-stop',name)
    root('pm grant '+name+' android.permission.CAMERA')
    beforeNetwork=root('/data/adb/fuxicontrol/networkctl query')
    rpc({'command':'config.replace','config':config})
    adb('shell','am','start','-W','-n',name+'/.Main')
    adb('shell','input','keyevent','KEYCODE_HOME');time.sleep(2)
    rpc({'command':'runtime.reconcile'})
    pid=root('pidof '+name).strip();uid=rpc({'command':'app.get','package':name})['effective']['uid']
    journal=json.loads(root('cat /data/adb/fuxicontrol/resources.json'))
    own=journal['task:'+pid];memory='/sys/fs/cgroup/apps/uid_'+str(uid)+'/memory.high'
    if json.loads(root('CLASSPATH=/data/adb/fuxicontrol/controller.apk app_process /system/bin dev.local.fuxicontrol.service.ResourceProbe inspect '+pid))['uclampMax']!=512:
        raise RuntimeError('Recovery fixture did not actually apply CPU limits')
    if root('cat '+memory).strip()!='67108864': raise RuntimeError('Recovery fixture did not actually apply memory.high')
    root('sh /data/adb/fuxicontrol/stop.sh')
    root('sh /data/adb/fuxicontrol/start.sh')
    deadline=time.monotonic()+15
    while True:
        state=rpc({'command':'status'})
        if state['hooks'].get('startup') and not state['lastError']: break
        if time.monotonic()>deadline: raise RuntimeError('Enforcing daemon did not reconnect to its system hook')
        time.sleep(0.2)
    print('PASS enforcing daemon restart waits for its foreground snapshot',flush=True)
    output=root('sh /data/adb/fuxicontrol/recover.sh')
    (project/'private/recovery-output.txt').write_text(output)
    restored=json.loads(root('CLASSPATH=/data/adb/fuxicontrol/controller.apk app_process /system/bin dev.local.fuxicontrol.service.ResourceProbe inspect '+pid))
    if restored['uclampMax']!=own['beforeClamp']: raise RuntimeError('Offline recovery did not restore scheduler ceiling')
    if root('cat '+memory).strip()!=journal[memory]['before']: raise RuntimeError('Offline recovery did not restore memory limit')
    if 'android.permission.CAMERA: granted=true' not in root('dumpsys package '+name): raise RuntimeError('Offline recovery did not restore runtime permission')
    if beforeNetwork!=root('/data/adb/fuxicontrol/networkctl query'): raise RuntimeError('Offline recovery changed original Android network attachment')
    modules=json.loads(root(vector+'modules ls --json'))['data']
    status={item['PACKAGE']:item['STATUS'] for item in modules}
    if status['dev.local.fuxicontrol']!='disabled' or status['github.tornaco.android.thanos']!='enabled': raise RuntimeError('Recovery touched the wrong Vector module')
    if json.loads(root('cat /data/adb/fuxicontrol/policy.json'))['mode']!='disabled': raise RuntimeError('Offline recovery did not disable desired policy')
    print('PASS offline scheduler, memory, permission, network and own-module recovery',flush=True)
finally:
    root('chmod 0700 /data/adb/service.d/95-fuxicontrol.sh')
    root(vector+'modules enable dev.local.fuxicontrol --json')
    root('sh /data/adb/fuxicontrol/start.sh')
    rpc({'command':'config.replace','config':original})
    adb('shell','am','force-stop',name)
    adb('shell','am','start','-W','-n','dev.local.fuxicontrol/.ui.MainActivity')
    print('Restored controller observation mode, own boot activation and unchanged Thanox',flush=True)
