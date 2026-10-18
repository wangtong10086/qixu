"""Read-only device acceptance for the signed Qixu UI; edits are discarded."""
from pathlib import Path
import json, re, time, uuid, xml.etree.ElementTree as ET
from device import adb, root

project=Path(__file__).resolve().parents[1]
shots=project/'docs/qixu'
shots.mkdir(exist_ok=True)

def dump():
    time.sleep(.6)
    resumed=adb('shell','dumpsys','activity','activities')
    if 'dev.local.fuxicontrol/' not in next(x for x in resumed.splitlines() if 'topResumedActivity=' in x):
        raise RuntimeError('App is not foreground; do not operate another app')
    remote='/data/local/tmp/qixu-'+uuid.uuid4().hex+'.xml'
    adb('shell','uiautomator','dump',remote)
    content=adb('shell','cat',remote);adb('shell','rm',remote)
    return list(ET.fromstring(content).iter('node'))

def tap(label):
    nodes=dump()
    def find(items):
        return next((n for n in items if n.get('package')=='dev.local.fuxicontrol' and (n.get('text')==label or n.get('content-desc')==label)),None)
    n=find(nodes)
    if n is None:
        # Search/filter chip rows can overflow horizontally; use their actual bounds.
        for row in sorted((item for item in nodes if item.get('scrollable')=='true'), key=lambda item: int(re.findall(r'\d+',item.get('bounds'))[3])-int(re.findall(r'\d+',item.get('bounds'))[1])):
            x,y,r,b=map(int,re.findall(r'\d+',row.get('bounds')))
            if r-x > 2*(b-y):
                for start,end in ((r-20,x+20),(x+20,r-20)):
                    adb('shell','input','swipe',str(start),str((y+b)//2),str(end),str((y+b)//2),'350')
                    n=find(dump())
                    if n is not None:break
            else:
                adb('shell','input','swipe',str((x+r)//2),str(b-30),str((x+r)//2),str(y+30),'350')
                n=find(dump())
            if n is not None:break
    if n is None:raise AssertionError('UI control not found: '+label)
    x,y,r,b=map(int,re.findall(r'\d+',n.get('bounds')))
    adb('shell','input','tap',str((x+r)//2),str((y+b)//2))
    return dump()

def check(text):
    assert any(text in n.get('text','') for n in dump()),text

def capture(name):
    print('CHECK '+name,flush=True)
    (shots/(name+'.png')).write_bytes(adb('exec-out','screencap','-p',binary=True))

before=root('/data/adb/fuxicontrol/fuxictl rpc \'{"command":"config.get"}\'')
adb('shell','am','force-stop','dev.local.fuxicontrol')
adb('shell','am','start','-W','-n','dev.local.fuxicontrol/.ui.MainActivity')
check('已连接');capture('overview')
tap('应用');check('搜索应用、包名或权限');capture('apps')
catalog=json.loads(root('/data/adb/fuxicontrol/fuxictl apps'))['apps']
expected=sum(app['package'] in json.loads(before)['apps'] for app in catalog)
tap('有例外');check(f'{expected} 个应用');tap('全部')
tap('搜索应用、包名或权限');adb('shell','input','text','design');adb('shell','input','keyevent','4')
tap('Fuxi Design');check('启动与后台');capture('app-policy')
tap('返回');tap('策略');tap('默认模板');check('默认值实时作用');capture('template')
# Change a draft only, check unsaved navigation, and discard it.
tap('禁止');tap('允许');tap('保存更改');check('确认保存更改');check('有效策略将变化');capture('save-summary');tap('取消')
tap('设置');check('放弃未保存');tap('放弃更改');check('诊断与高级');capture('settings')
tap('系统能力');check('requiredVectorApi');tap('返回')
tap('策略');tap('自动化');check('高级编辑器');tap('返回')
tap('推送');check('高级编辑器');tap('返回')
tap('概览');check('正在执行')
after=root('/data/adb/fuxicontrol/fuxictl rpc \'{"command":"config.get"}\'')
assert before==after,'Read-only UI test changed real policy'
print('PASS overview, app filters/search/detail, template form/summary, unsaved discard, diagnostics, automation and push; policy unchanged')

# Optional real-write test targets only the separate design-preview application.
import sys, shlex
if '--write-preview' in sys.argv:
    def rpc(value):
        return json.loads(root('/data/adb/fuxicontrol/fuxictl rpc '+shlex.quote(json.dumps(value))))
    name='dev.local.fuxicontrol.design'
    saved=rpc({'command':'config.get'})
    overrides=saved['apps'].get(name)
    try:
        tap('应用');tap('Fuxi Design');tap('禁止');tap('允许')
        tap('保存更改');tap('确认保存')
        assert rpc({'command':'app.get','package':name})['overrides']['autostart']=='allow'
        # The first match is the bottom action; the per-field button is scrolled below it.
        nodes=dump()
        candidates=[n for n in nodes if n.get('text')=='恢复继承']
        n=max(candidates,key=lambda n:int(re.findall(r'\d+',n.get('bounds'))[1]))
        x,y,r,b=map(int,re.findall(r'\d+',n.get('bounds')))
        adb('shell','input','tap',str((x+r)//2),str((y+b)//2))
        tap('确认保存')
        assert rpc({'command':'app.get','package':name})['overrides']=={}
        print('PASS real signed UI save and restore-inherit on design-preview package',flush=True)
    finally:
        current=rpc({'command':'config.get'})
        if current['apps'].get(name)!=overrides:
            request={'command':'app.inherit' if overrides is None else 'app.replace','package':name,'expectedRevision':current['revision']}
            if overrides is not None:request['patch']=overrides
            rpc(request)
        restored=rpc({'command':'config.get'})
        saved.pop('revision');restored.pop('revision')
        assert saved==restored,'Test did not restore policy content'
        adb('shell','am','force-stop','dev.local.fuxicontrol')
        adb('shell','am','start','-W','-n','dev.local.fuxicontrol/.ui.MainActivity')
