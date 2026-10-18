"""Scoped enforcement test. Other applications receive no Fuxi restrictions; restore in finally."""
import json
from pathlib import Path
import shlex
import time
from device import adb,root

project=Path(__file__).resolve().parents[1]
def rpc(value): return json.loads(root('/data/adb/fuxicontrol/fuxictl rpc '+shlex.quote(json.dumps(value))))
original=rpc({'command':'config.get'})
name='dev.local.fuxicontrol.probe'
config=json.loads(json.dumps(original))
config.update(mode='enforce',apps={name:{'autostart':'deny','cpuNice':9,'uclampMax':512}},rules=[],pushProxy={name:{'enabled':True,'showContent':False,'startOnPush':True}})
config['defaults'].update(autostart='allow',background='normal',cpuNice=0,uclampMax=1024,memoryHighMiB=0,memoryMaxMiB=0,permissions={},appOps={},uploadBps=0,downloadBps=0,network='allow',resident=False,wakeLocks=[],cleanupOnTaskRemoved=False)
try:
    seen=rpc({'command':'status'})['hookLastSeen']
    rpc({'command':'config.replace','config':config})
    deadline=time.monotonic()+15
    while rpc({'command':'status'})['hookLastSeen']<=seen:
        if time.monotonic()>deadline: raise RuntimeError('System hook did not acknowledge the updated snapshot')
        time.sleep(0.2)
    adb('shell','am','force-stop',name)
    time.sleep(1)
    result=root(shlex.join(['am','broadcast','-a','com.google.android.c2dm.intent.RECEIVE','-n',name+'/.Boot','--include-stopped-packages','--es','google.message_id','fuxi-validation-'+str(time.time_ns()),'--es','alert','Fuxi validation only']))
    time.sleep(8)
    # list prints notification keys, never notification/message contents.
    notifications=root('cmd notification list')
    matching=[line for line in notifications.splitlines() if len(line.split('|'))>3 and line.split('|')[1]=='dev.local.fuxicontrol' and line.split('|')[3].startswith('proxy:')]
    if not matching:
        print('Controller-related notification keys:',[line for line in notifications.splitlines() if 'fuxicontrol' in line or 'proxy:' in line],flush=True)
        raise RuntimeError('Synthetic push notification was not posted')
    print('PASS synthetic push proxy notification')
    pids=root('pidof '+name).strip()
    if not pids: raise RuntimeError('Push did not start the background validation process')
    print('PASS configured push starts background process without an activity')
    status=rpc({'command':'status'})
    if status['lastError']: raise RuntimeError(status['lastError'])
    adb('shell','am','start','-W','-n',name+'/.Main','--ez','isolated','true')
    time.sleep(2)
    isolated=json.loads(root('cat /data/user/0/'+name+'/files/isolated.json'))
    print('PASS isolated process binding; uid differs from owner:',isolated['uid']!=isolated['owner'])
    notifications=root('cmd notification list')
    if any(len(line.split('|'))>3 and line.split('|')[1]=='dev.local.fuxicontrol' and line.split('|')[3].startswith('proxy:') for line in notifications.splitlines()): raise RuntimeError('Proxy notification not cleared on foreground')
    print('PASS foreground clears synthetic proxy notification')
    adb('shell','input','keyevent','KEYCODE_HOME')
    time.sleep(2)
    rpc({'command':'runtime.reconcile'})
    uid=isolated['uid']
    # Verify actual kernel nice value, rather than desired-state JSON.
    processes=root('pidof '+name).split()
    for pid in processes:
        stat=root('cat /proc/'+pid+'/stat')
        nice=int(stat[stat.rfind(')')+2:].split()[16])
        if nice<9: raise RuntimeError('CPU nice was not applied to probe')
    print('PASS background CPU nice applied')
    for line in root('ps -A -o PID,UID').splitlines()[1:]:
        fields=line.split()
        if len(fields)==2 and fields[1]==str(isolated['uid']):
            kernel=json.loads(root('CLASSPATH=/data/adb/fuxicontrol/controller.apk app_process /system/bin dev.local.fuxicontrol.service.ResourceProbe inspect '+fields[0]))
            if kernel['uclampMax']>512 or kernel['nice']<9: raise RuntimeError('Isolated UID did not inherit owner scheduling ceiling')
            print('PASS isolated UID inherits owner CPU settings')
            break
    else: raise RuntimeError('Isolated process exited before scheduling verification')
finally:
    rpc({'command':'config.replace','config':original})
    adb('shell','am','force-stop',name)
    adb('shell','am','start','-W','-n','dev.local.fuxicontrol/.ui.MainActivity')
    print('Restored original observation policy')
