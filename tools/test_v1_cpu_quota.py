"""Device acceptance: owner and isolated processes share a background quota, then restore."""
import argparse
import json,shlex,time
from pathlib import Path
from device import root,adb
PACKAGE='dev.local.fuxicontrol.probe'
parser=argparse.ArgumentParser()
parser.add_argument('--quota-us',type=int,default=50000,help='Aggregate CPU time per 100000 us period')
quota_us=parser.parse_args().quota_us
def rpc(command,**args):
    return json.loads(root('/data/adb/fuxicontrol/fuxictl rpc '+shlex.quote(json.dumps(dict(command=command,**args)))))
def poll(check):
    until=time.monotonic()+25
    while time.monotonic()<until:
        result=check()
        if result:return result
        time.sleep(.5)
    raise RuntimeError('Quota state did not converge')
def state():return rpc('app.get',package=PACKAGE)['runtime'].get('cpuQuota',{})
def matching_state(predicate):
    value=state()
    return value if predicate(value) else None
original=rpc('config.get')['apps'].get(PACKAGE)
report={}
try:
    rpc('app.replace',package=PACKAGE,patch={'protected':True,'autostart':'allow','background':'normal','cpuNice':0,'uclampMax':1024,
        'network':'allow','cpuQuota':{'periodUs':100000,'foregroundUs':0,'backgroundUs':quota_us,'allowProtected':True}})
    adb('shell','am','force-stop',PACKAGE)
    root('rm -f /data/user/0/'+PACKAGE+'/files/isolated.json')
    adb('shell','am','start','-W','-n',PACKAGE+'/.Main','--ez','resources','true','--ez','isolated','true')
    uid=rpc('app.get',package=PACKAGE)['effective']['uid']
    poll(lambda:state().get('phase')=='foreground')
    poll(lambda:root('test ! -f /data/user/0/'+PACKAGE+'/files/isolated.json || echo ready').strip())
    isolated_uid=json.loads(root('cat /data/user/0/'+PACKAGE+'/files/isolated.json'))['uid']
    adb('shell','input','keyevent','3')
    report['background']=poll(lambda:matching_state(lambda value:value.get('applied')))
    pids=[row.split()[1] for row in root('ps -A -o UID,PID').splitlines()[1:] if int(row.split()[0]) in {uid,isolated_uid}]
    assert len(pids)>=3,pids
    def isolated_membership():
        for pid in pids:
            groups=root('cat /proc/'+pid+'/cgroup').splitlines()
            if not any('cpu' in row.split(':')[1].split(',') and row.split(':')[2]=='/qixu_uid_'+str(uid) for row in groups):return False
        return True
    poll(isolated_membership)
    report['isolatedSharesOwnerBudget']=True
    def sample():
        text=root('cat /proc/uptime; '+ '; '.join('cat /proc/'+pid+'/stat' for pid in pids))
        lines=text.splitlines();ticks=0
        for line in lines[1:]:
            f=line[line.rindex(')')+2:].split();ticks+=int(f[11])+int(f[12])
        return float(lines[0].split()[0]),ticks
    a=sample();time.sleep(12);b=sample()
    report['wallSeconds']=b[0]-a[0];report['cpuSeconds']=(b[1]-a[1])/100
    assert report['cpuSeconds']<=report['wallSeconds']*(quota_us/100000)+.06,report
    assert root('cat /dev/cpuctl/qixu_uid_'+str(uid)+'/cpu.cfs_quota_us').strip()==str(quota_us)
    adb('shell','am','start','-W','-n',PACKAGE+'/.Main')
    report['foreground']=poll(lambda:matching_state(lambda value:value.get('phase')=='foreground' and not value.get('applied')))
    assert root('test ! -d /dev/cpuctl/qixu_uid_'+str(uid)+' && echo restored').strip()=='restored'
finally:
    if original is None:rpc('app.inherit',package=PACKAGE)
    else:rpc('app.replace',package=PACKAGE,patch=original)
    adb('shell','am','force-stop',PACKAGE)
    assert rpc('config.get')['apps'].get(PACKAGE)==original
    Path('private/v1-cpu-acceptance.json').write_text(json.dumps(report,indent=2))
    print(json.dumps(report,indent=2))
