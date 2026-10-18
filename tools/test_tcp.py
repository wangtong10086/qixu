"""Regression test TCP after changing the egress congestion/drop return code."""
import json
from pathlib import Path
import shlex
import time
from device import adb,root

project=Path(__file__).resolve().parents[1]
def rpc(value): return json.loads(root('/data/adb/fuxicontrol/fuxictl rpc '+shlex.quote(json.dumps(value))))
original=rpc({'command':'config.get'});name='dev.local.fuxicontrol.probe'
config=json.loads(json.dumps(original));config.update(mode='enforce',apps={},rules=[],pushProxy={})
config['defaults'].update(autostart='allow',background='normal',cpuNice=0,uclampMax=1024,memoryHighMiB=0,memoryMaxMiB=0,permissions={},appOps={},uploadBps=0,downloadBps=0,network='allow',resident=False,wakeLocks=[],cleanupOnTaskRemoved=False)
pid=root('CLASSPATH=/data/adb/fuxicontrol/controller.apk app_process /system/bin dev.local.fuxicontrol.service.NetworkFixture >/data/adb/fuxicontrol/tcp-fixture.log 2>&1 </dev/null & echo $!').strip()
try:
    for mode,bps in [('upload',512000),('download',2000000)]:
        config['apps']={name:{'uploadBps':bps if mode=='upload' else 0,'downloadBps':bps if mode=='download' else 0}}
        rpc({'command':'config.replace','config':config})
        uid=rpc({'command':'app.get','package':name})['effective']['uid']
        adb('shell','am','force-stop',name)
        root('rm -f /data/user/0/'+name+'/files/result.json')
        adb('shell','am','start','-W','-n',name+'/.Main','--es','test',mode)
        deadline=time.monotonic()+40
        while not root('test ! -f /data/user/0/'+name+'/files/result.json || echo ready').strip():
            if time.monotonic()>deadline: raise RuntimeError('TCP probe did not complete')
            time.sleep(1)
        result=json.loads(root('cat /data/user/0/'+name+'/files/result.json'))
        stats=rpc({'command':'network.stats','uid':uid})
        if result['bytes']<=0: raise RuntimeError('TCP made no progress')
        if result['bps']>bps+65536*8000/result['elapsedMs']+bps*0.05: raise RuntimeError('TCP exceeded the cap plus burst allowance')
        print(json.dumps({'case':result,'stats':stats}),flush=True)
        (project/'private'/('tcp-'+mode+'-congestion.json')).write_text(json.dumps({'result':result,'stats':stats},indent=2))
finally:
    rpc({'command':'config.replace','config':original})
    if 'dev.local.fuxicontrol.service.NetworkFixture' in root('cat /proc/'+pid+'/cmdline'): root('kill '+pid)
    adb('shell','am','force-stop',name)
    print('Restored original policy and Android network entry points',flush=True)
