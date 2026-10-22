"""Real-device CPU/memory/process-network test. Only modifies the disposable probe policy."""
import json
import shlex
import time
from pathlib import Path
from device import root, adb

PACKAGE='dev.local.fuxicontrol.probe'
def rpc(command, **args):
    return json.loads(root('/data/adb/fuxicontrol/fuxictl rpc '+shlex.quote(json.dumps(dict(command=command,**args)))))
def poll(check, timeout=30):
    deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        value=check()
        if value:return value
        time.sleep(1)
    raise RuntimeError('Timed out waiting for resource evidence')
def proc(name):
    snapshot=rpc('resources.snapshot')
    if snapshot.get('error'):raise RuntimeError(snapshot['error'])
    return next((p for p in snapshot.get('apps',{}).get(PACKAGE,{}).get('processes',[]) if p['name']==name),None)
def inspect(pid):
    return json.loads(root('CLASSPATH=/data/adb/fuxicontrol/controller.apk app_process /system/bin dev.local.fuxicontrol.service.ResourceProbe inspect '+str(pid)))
original=rpc('config.get');saved=original['apps'].get(PACKAGE)
Path('private/resource-test-original.json').write_text(json.dumps(original,indent=2))
fixture=None
try:
    policy={'autostart':'allow','background':'normal','cpuNice':0,'uclampMax':1024,'network':'allow','uploadBps':2000000,'downloadBps':2000000,
            'processes':{PACKAGE+':worker':{'foreground':{'cpuNice':9,'uclampMax':512,'memoryHighMiB':96,'memoryMaxMiB':192},'background':{'cpuNice':14,'uclampMax':256,'memoryHighMiB':96,'memoryMaxMiB':192},'exemptActiveTasks':False},
                         PACKAGE+':network':{'foreground':{'uploadBps':512000,'downloadBps':1000000},'background':{'uploadBps':512000,'downloadBps':1000000}}}}
    rpc('app.replace',package=PACKAGE,patch=policy)
    adb('shell','am','force-stop',PACKAGE)
    adb('shell','am','start','-W','-n',PACKAGE+'/.Main','--ez','resources','true')
    worker=poll(lambda:proc(PACKAGE+':worker'))
    fg=poll(lambda:(v if (v:=inspect(worker['pid']))['nice']==9 and v['uclampMax']==512 else None))
    group=root('sed -n '+shlex.quote('s/^0:://p')+' /proc/'+str(worker['pid'])+'/cgroup').strip()
    assert root('cat /sys/fs/cgroup'+group+'/memory.high').strip()==str(96*1048576)
    sample=poll(lambda:(p if (p:=proc(PACKAGE+':worker')) and p.get('cpuPercent',0) and p['writeBytes']>0 else None))
    print(json.dumps({'foreground':fg,'sample':sample}),flush=True)
    detail=rpc('resources.detail',package=PACKAGE,process=PACKAGE+':worker')
    assert detail['processes'][0]['pssBytes']>0
    adb('shell','input','keyevent','3')
    bg=poll(lambda:(v if (v:=inspect(worker['pid']))['nice']>=14 and v['uclampMax']==256 else None))
    print(json.dumps({'background':bg,'pss':detail}),flush=True)
    rpc('process.policy.inherit',package=PACKAGE,process=PACKAGE+':worker')
    poll(lambda:root('cat /sys/fs/cgroup'+group+'/memory.high').strip()=='max')
    print('process memory restored',flush=True)
    fixture=root('CLASSPATH=/data/adb/fuxicontrol/controller.apk app_process /system/bin dev.local.fuxicontrol.service.NetworkFixture >/data/adb/fuxicontrol/resource-fixture.log 2>&1 </dev/null & echo $!').strip()
    for mode,cap in [('upload',512000),('download',1000000)]:
        adb('shell','am','force-stop',PACKAGE)
        root('rm -f /data/user/0/'+PACKAGE+'/files/result.json')
        adb('shell','am','start','-W','-n',PACKAGE+'/.Main','--es','test',mode)
        network=poll(lambda:proc(PACKAGE+':network'))
        poll(lambda:root('test ! -f /data/user/0/'+PACKAGE+'/files/result.json || echo ready').strip(),45)
        result=json.loads(root('cat /data/user/0/'+PACKAGE+'/files/result.json'))
        assert result['bytes']>0
        assert result['bps']<=cap*1.10+65536*8000/result['elapsedMs'],result
        measured=poll(lambda:(p if (p:=proc(PACKAGE+':network')) and p.get(mode+'Bytes',0)>0 else None))
        print(json.dumps({'network':result,'process':measured}),flush=True)
    assert rpc('resources.history',scope='system',identity='',since=0)['samples']
finally:
    if saved is None:rpc('app.inherit',package=PACKAGE)
    else:rpc('app.replace',package=PACKAGE,patch=saved)
    adb('shell','am','force-stop',PACKAGE)
    if fixture and 'dev.local.fuxicontrol.service.NetworkFixture' in root('cat /proc/'+fixture+'/cmdline'):root('kill '+fixture)
    restored=rpc('config.get')['apps'].get(PACKAGE)
    assert restored==saved,'Probe policy was not restored'
    print('Probe policy restored',flush=True)
