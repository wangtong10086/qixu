"""Root-device affinity acceptance. Restores the probe policy and optional global ceiling."""
import argparse
import json
import shlex
import time
import re
from pathlib import Path
from device import root, adb

PACKAGE='dev.local.fuxicontrol.probe'
PROCESS=PACKAGE+':affinity'
parser=argparse.ArgumentParser()
parser.add_argument('--global-ceiling',action='store_true',help='After scoped acceptance, also exercise the device-wide ceiling and restore it')
options=parser.parse_args()
report={}

def rpc(command,**fields):
    return json.loads(root('/data/adb/fuxicontrol/fuxictl rpc '+shlex.quote(json.dumps(dict(command=command,**fields)))))
def write(command,**fields):
    return rpc(command,expectedRevision=rpc('status')['revision'],**fields)
def guard():
    page=adb("shell","dumpsys activity activities | grep topResumedActivity")
    match=re.search(r"u0 ([^/ ]+)/",page)
    if not match or match.group(1) not in {PACKAGE,"com.miui.home","com.android.launcher3","dev.local.fuxicontrol"}:
        raise RuntimeError("Another app is foreground; test stopped before further input")
def poll(check,seconds=30):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        guard()
        value=check()
        if value:return value
        time.sleep(.6)
    raise AssertionError('Affinity state did not converge')
def threads():return rpc('threads.list',package=PACKAGE,process=PROCESS)['threads']
def find(name):return next((t for t in threads() if t['name']==name),None)
def mask(name,allowed):
    t=find(name)
    return t if t and set(t['allowed'])==set(allowed) else None

before=rpc('config.get')
saved=before['apps'].get(PACKAGE)
ceiling=before['scheduling']['backgroundSmallCoresOnly']
Path('private/affinity-before.json').write_text(json.dumps(before,indent=2))
topology=rpc('cpu.topology');little=topology['groups']['little'];big=topology['groups']['big'];prime=topology['groups']['prime']
assert little and big and prime,'This acceptance requires the Xiaomi 13 topology'
rule={'foreground':{'cpuAffinity':'big'},'background':{'cpuAffinity':'little'},'threads':[
    {'id':'render','match':{'type':'exact','name':'RenderProbe'},'foreground':{'cpuAffinity':'prime'}},
    {'id':'worker','match':{'type':'glob','name':'worker-*'},'foreground':{'cpuAffinity':'little'}},
    {'id':'renamed','match':{'type':'exact','name':'RenamedWorker'},'foreground':{'cpuAffinity':'prime'}},
    {'id':'main','match':{'type':'main'},'foreground':{'cpuAffinity':'big'}}]}
try:
    guard()
    base={'autostart':'allow','protected':False,'resident':False,'background':'normal','cpuNice':0,'uclampMax':1024,'cpuQuota':{'periodUs':100000,'foregroundUs':0,'backgroundUs':0},'cpuAffinity':{'foreground':'system','background':'little'},'processes':{PROCESS:rule}}
    write('app.replace',package=PACKAGE,patch=base)
    adb('shell','am','force-stop',PACKAGE)
    adb('shell','am','start','-W','-n',PACKAGE+'/.Main','--ez','affinity','true')
    poll(lambda:rpc('cpu.status',package=PACKAGE)['phase']=='foreground')
    report['render']=poll(lambda:mask('RenderProbe',prime))
    report['worker']=poll(lambda:mask('worker-1',little))
    report['main']=poll(lambda:next((t for t in threads() if t['main'] and set(t['allowed'])==set(big)),None))
    print('Initial foreground affinity verified',flush=True)
    root('am startservice -n '+PACKAGE+'/.AffinityWorker --ez spawn true')
    report['lateChild']=poll(lambda:mask('LateChild',big),30)
    root('am startservice -n '+PACKAGE+'/.AffinityWorker --ez rename true')
    report['renamed']=poll(lambda:mask('RenamedWorker',prime),35)
    print('New and renamed threads verified',flush=True)
    old_revision=rpc('status')['revision']
    preview=rpc('app.apply',package=PACKAGE,patch={'cpuAffinity':{'foreground':'little'}},dryRun=True,expectedRevision=old_revision)
    assert preview['schedulingPreview']['apps'][PACKAGE]['threads']>0
    assert rpc('status')['revision']==old_revision
    assert set(find('RenderProbe')['allowed'])==set(prime),'dry-run changed scheduling'
    report['preview']=preview['schedulingPreview']
    # A changed thread name and a newly created thread must both return to system scope.
    write('process.policy.inherit',package=PACKAGE,process=PROCESS)
    for name in ('RenderProbe','LateChild','RenamedWorker'):
        report['restored-'+name]=poll(lambda name=name:mask(name,topology['online']))
    write('process.policy.patch',package=PACKAGE,process=PROCESS,rule=rule)
    poll(lambda:mask('RenderProbe',prime))
    if options.global_ceiling:
        write('scheduling.patch',patch={'backgroundSmallCoresOnly':True})
        # Explicit system selection cannot escape the global floor, even with an active task.
        write('app.apply',package=PACKAGE,patch={'cpuAffinity':{'background':'system'},'processes':{PROCESS:{'background':{'cpuAffinity':'system'}}}})
    adb('shell','am','start-foreground-service','-n',PACKAGE+'/.LifecycleService','--ez','foreground','true')
    adb('shell','input','keyevent','3')
    poll(lambda:rpc('cpu.status',package=PACKAGE)['phase']=='background')
    report['background']=poll(lambda:(ts if (ts:=threads()) and all(set(t['allowed'])<=set(little) for t in ts) else None))
    uid=rpc('app.get',package=PACKAGE)['effective']['uid']
    assert uid in rpc('status')['activeTaskUids'],'Data-sync active task not observed'
    if options.global_ceiling:
        report['globalActiveTask']=True
        try:
            write('app.apply',package=PACKAGE,patch={'cpuAffinity':{'background':'prime'}},dryRun=True)
            raise AssertionError('Background-only prime should fail validation')
        except __import__('subprocess').CalledProcessError:
            pass
    adb('shell','am','start','-W','-n',PACKAGE+'/.Main')
    report['foregroundAgain']=poll(lambda:mask('RenderProbe',prime))
    # Repeat a cold start to check PID/TID reuse does not carry old settings.
    adb('shell','am','force-stop',PACKAGE)
    adb('shell','am','start','-W','-n',PACKAGE+'/.Main','--ez','affinity','true')
    report['newProcess']=poll(lambda:mask('RenderProbe',prime))
    assert report['newProcess']['identity']!=report['render']['identity']
    assert not rpc('status')['lastError'],rpc('status')
finally:
    if saved is None:write('app.inherit',package=PACKAGE)
    else:write('app.replace',package=PACKAGE,patch=saved)
    if rpc('config.get')['scheduling']['backgroundSmallCoresOnly']!=ceiling:write('scheduling.patch',patch={'backgroundSmallCoresOnly':ceiling})
    adb('shell','am','force-stop',PACKAGE)
    assert rpc('config.get')['apps'].get(PACKAGE)==saved,'Probe policy was not restored'
    report['probePolicyRestored']=True
    Path('private/affinity-acceptance'+('-global' if options.global_ceiling else '')+'.json').write_text(json.dumps(report,indent=2))
    print(json.dumps({'checks':list(report),'probePolicyRestored':True},ensure_ascii=False),flush=True)
