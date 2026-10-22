"""Device acceptance: aggregate 1% background quota and foreground restoration on a disposable app."""
import json,shlex,time
from pathlib import Path
from device import root,adb
PACKAGE='dev.local.fuxicontrol.probe'
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
original=rpc('config.get')['apps'].get(PACKAGE)
report={}
try:
    rpc('app.replace',package=PACKAGE,patch={'protected':True,'autostart':'allow','background':'normal','cpuNice':0,'uclampMax':1024,
        'network':'allow','cpuQuota':{'periodUs':100000,'foregroundUs':0,'backgroundUs':1000,'allowProtected':True}})
    adb('shell','am','start','-W','-n',PACKAGE+'/.Main','--ez','resources','true')
    uid=rpc('app.get',package=PACKAGE)['effective']['uid']
    poll(lambda:state().get('phase')=='foreground')
    adb('shell','input','keyevent','3')
    report['background']=poll(lambda:state() if state().get('applied') else None)
    pids=root('pidof '+PACKAGE+' '+PACKAGE+':worker').split()
    assert len(pids)>=2,pids
    def sample():
        text=root('cat /proc/uptime; '+ '; '.join('cat /proc/'+pid+'/stat' for pid in pids))
        lines=text.splitlines();ticks=0
        for line in lines[1:]:
            f=line[line.rindex(')')+2:].split();ticks+=int(f[11])+int(f[12])
        return float(lines[0].split()[0]),ticks
    a=sample();time.sleep(12);b=sample()
    report['wallSeconds']=b[0]-a[0];report['cpuSeconds']=(b[1]-a[1])/100
    assert report['cpuSeconds']<=report['wallSeconds']*.01+.06,report
    assert root('cat /dev/cpuctl/qixu_uid_'+str(uid)+'/cpu.cfs_quota_us').strip()=='1000'
    adb('shell','am','start','-W','-n',PACKAGE+'/.Main')
    report['foreground']=poll(lambda:state() if state().get('phase')=='foreground' and not state().get('applied') else None)
    assert root('test ! -d /dev/cpuctl/qixu_uid_'+str(uid)+' && echo restored').strip()=='restored'
finally:
    if original is None:rpc('app.inherit',package=PACKAGE)
    else:rpc('app.replace',package=PACKAGE,patch=original)
    adb('shell','am','force-stop',PACKAGE)
    assert rpc('config.get')['apps'].get(PACKAGE)==original
    Path('private/v1-cpu-acceptance.json').write_text(json.dumps(report,indent=2))
    print(json.dumps(report,indent=2))
