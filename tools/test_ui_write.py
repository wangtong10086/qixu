"""Exercise the signed UI's save and inherit actions using only our disposable package."""
import json
import shlex
from device import adb,root

def rpc(value): return json.loads(root('/data/adb/fuxicontrol/fuxictl rpc '+shlex.quote(json.dumps(value))))
original=rpc({'command':'config.get'})
if original['mode']!='observe': raise RuntimeError('UI mutation fixture requires observation mode')
from test_ui import dump,tap,texts
name='dev.local.fuxicontrol.probe'
try:
    rpc({'command':'app.replace','package':name,'patch':{'cpuNice':9}})
    nodes=tap(dump(),'应用')
    nodes=tap(nodes,'搜索应用或包名')
    adb('shell','input','text','probe')
    adb('shell','input','keyevent','KEYCODE_BACK')
    nodes=tap(dump(),'Fuxi validation probe')
    for attempt in range(2):
        nodes=tap(nodes,'保存')
        if 'Configuration changed' in texts(nodes): raise RuntimeError('UI did not track its own new revision')
        if rpc({'command':'app.get','package':name})['overrides']!={'cpuNice':9}: raise RuntimeError('UI save failed')
    print('PASS signed UI saves twice using the updated revision',flush=True)
    nodes=tap(nodes,'恢复继承')
    if rpc({'command':'app.get','package':name})['overrides']: raise RuntimeError('UI inherit failed')
    print('PASS signed UI clears application overrides',flush=True)
finally:
    rpc({'command':'config.replace','config':original})
    adb('shell','am','force-stop','dev.local.fuxicontrol')
    adb('shell','am','start','-W','-n','dev.local.fuxicontrol/.ui.MainActivity')
    print('Restored original observation configuration',flush=True)
