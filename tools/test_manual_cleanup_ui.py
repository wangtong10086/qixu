"""UI acceptance; stops only the disposable probe, restores its policy afterward."""
import json,re,shlex,time,xml.etree.ElementTree as ET
from pathlib import Path
from device import adb,root
PACKAGE='dev.local.fuxicontrol';PROBE=PACKAGE+'.probe'
OUTPUT=Path(__file__).resolve().parents[1]/'private/cleanup-acceptance'
OUTPUT.mkdir(exist_ok=True)
def rpc(command,**args):return json.loads(root('/data/adb/fuxicontrol/fuxictl rpc '+shlex.quote(json.dumps(dict(command=command,**args)))))
def nodes():
    focus=next(line for line in adb('shell','dumpsys','window').splitlines() if 'mCurrentFocus=' in line)
    assert PACKAGE+'/' in focus,'Stop: another application is foreground'
    adb('shell','uiautomator','dump','/data/local/tmp/qixu-cleanup-ui.xml')
    content=adb('shell','cat','/data/local/tmp/qixu-cleanup-ui.xml');adb('shell','rm','/data/local/tmp/qixu-cleanup-ui.xml')
    return list(ET.fromstring(content).iter('node'))
def tap(label):
    for _ in range(8):
        page=nodes();n=next((n for n in page if n.get('text')==label or n.get('content-desc')==label),None)
        if n is not None:break
        time.sleep(.5)
    assert n is not None,'Missing control: '+label
    x,y,r,b=map(int,re.findall(r'\d+',n.get('bounds')))
    adb('shell','input','tap',str((x+r)//2),str((y+b)//2));time.sleep(.8)
def gone(pid):return not root(f'test ! -d /proc/{pid} || echo alive').strip()
def wait_exit(pid):
    end=time.monotonic()+15
    while not gone(pid):
        assert time.monotonic()<end,'Process did not exit'
        time.sleep(.5)
def shot(name):
    nodes();(OUTPUT/(name+'.png')).write_bytes(adb('exec-out','screencap','-p',binary=True))
before=rpc('config.get');saved=before['apps'].get(PROBE)
try:
    rpc('app.replace',package=PROBE,patch=dict(autostart='allow',background='normal',resident=False))
    root('am force-stop '+PROBE)
    adb('shell','am','start','-W','-n',PROBE+'/.Main','--ez','resources','true');time.sleep(1)
    worker=int(root('pidof '+PROBE+':worker').strip());main=int(root('pidof '+PROBE).strip())
    root('am start -W -n '+PACKAGE+'/.ui.ResourceActivity --es package '+PROBE);time.sleep(2)
    tap('进程');shot('process-list')
    tap(PROBE+':worker');shot('process-detail')
    tap('结束进程');wait_exit(worker)
    assert not gone(main),'Sibling process exited'
    shot('process-ended');tap('返回')
    tap('强制停止');wait_exit(main);shot('app-stopped')
    print('PASS GUI child-only termination and whole-app stop without confirmation',flush=True)
finally:
    root('am force-stop '+PROBE)
    if saved is None:rpc('app.inherit',package=PROBE)
    else:rpc('app.replace',package=PROBE,patch=saved)
    after=rpc('config.get');assert before['apps']==after['apps'] and before['defaults']==after['defaults']
    print('PASS original policies restored',flush=True)
