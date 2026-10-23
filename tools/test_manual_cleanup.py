"""Only stops the disposable probe. Never executes global quick cleanup on user apps."""
import json
import shlex
import time
from pathlib import Path
from device import root, adb

PACKAGE = 'dev.local.fuxicontrol.probe'
def rpc(command, **args):
    return json.loads(root('/data/adb/fuxicontrol/fuxictl rpc '+shlex.quote(json.dumps(dict(command=command, **args)))))
def wait(predicate, seconds=25):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        value=predicate()
        if value:return value
        time.sleep(.4)
    raise AssertionError('Timed out')
def finish(command, **args):
    result=rpc(command, **args)
    job=result['id']
    result=wait(lambda:(r if (r:=rpc('cleanup.status'))['state']!='running' else None))
    assert result['id']==job and result['state']=='completed', result
    print(json.dumps(result,ensure_ascii=False),flush=True)
    return result['results'][0]
def alive(pid):return root(f'test ! -d /proc/{pid} || echo alive').strip()=='alive'
def launch():
    root('am force-stop '+PACKAGE)
    adb('shell','am','start','-W','-n',PACKAGE+'/.Main','--ez','resources','true')
    pid=int(wait(lambda:root('pidof '+PACKAGE+':worker || true').strip()))
    main=int(root('pidof '+PACKAGE).strip())
    start=int(root(f'cat /proc/{pid}/stat').rsplit(')',1)[1].split()[19])
    return main,pid,start

top='\n'.join(line for line in adb('shell','dumpsys','activity','activities').splitlines() if 'topResumedActivity=' in line)
assert not top or any(p+'/' in top for p in [PACKAGE,'dev.local.fuxicontrol','com.miui.home','com.android.launcher3']), top
before=rpc('config.get');saved=before['apps'].get(PACKAGE)
try:
    rpc('app.replace',package=PACKAGE,patch=dict(autostart='allow',background='normal',resident=False,
        cpuQuota=dict(periodUs=100000,foregroundUs=0,backgroundUs=0),
        manualCleanup=dict(mode='cached',backgroundSeconds=604800,cpuPercent=0,rssMiB=0)))
    main,pid,start=launch()
    assert finish('cleanup.app',package=PACKAGE)['state']=='skipped'
    assert alive(main) and alive(pid)
    adb('shell','input','keyevent','KEYCODE_HOME');time.sleep(2)
    preview=rpc('cleanup.quick',dryRun=True)
    target=next(row for row in preview['apps'] if row['package']==PACKAGE)
    assert not target['eligible'] and target['action']=='force-stop', target
    assert finish('cleanup.process',package=PACKAGE,pid=pid,startTicks=start+1)['state']=='exited'
    assert alive(pid),'Stale identity killed a process'
    assert finish('cleanup.process',package=PACKAGE,pid=pid,startTicks=start)['state']=='requested'
    wait(lambda:not alive(pid));assert alive(main),'Process cleanup killed sibling'
    assert finish('cleanup.process',package=PACKAGE,pid=pid,startTicks=start)['state']=='skipped'
    assert finish('cleanup.app',package=PACKAGE)['state'] in ('exited','requested')
    wait(lambda:not alive(main))
    print('PASS foreground protection, quick threshold, stale identity, child-only termination, app stop',flush=True)
    main,pid,start=launch();adb('shell','input','keyevent','KEYCODE_HOME');time.sleep(1)
    rpc('app.patch',package=PACKAGE,patch=dict(resident=True))
    assert finish('cleanup.app',package=PACKAGE)['state']=='confirmation-required'
    assert alive(main)
    assert finish('cleanup.app',package=PACKAGE,overrideProtection=True)['state'] in ('exited','requested')
    wait(lambda:not alive(main))
    assert finish('cleanup.app',package='dev.local.fuxicontrol',overrideProtection=True)['state']=='skipped'
    print('PASS resident confirmation and one-time override; manager remains protected',flush=True)
finally:
    root('am force-stop '+PACKAGE)
    if saved is None:rpc('app.inherit',package=PACKAGE)
    else:rpc('app.replace',package=PACKAGE,patch=saved)
    after=rpc('config.get')
    assert before['apps']==after['apps'] and before['defaults']==after['defaults']
    print('PASS original policies restored',flush=True)
