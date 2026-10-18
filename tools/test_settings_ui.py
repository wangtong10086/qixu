"""Read-only settings and physical back-gesture acceptance; edits are discarded."""
import json
from pathlib import Path
import re
import time
import uuid
import xml.etree.ElementTree as ET
from device import adb, root

PACKAGE='dev.local.fuxicontrol'
shots=Path(__file__).resolve().parents[1]/'private/settings-acceptance'
shots.mkdir(parents=True,exist_ok=True)

def config():
    data=json.loads(root('/data/adb/fuxicontrol/fuxictl rpc \'{"command":"config.get"}\''))
    data.pop('revision')
    return data

def dump():
    time.sleep(.6)
    active=next(line for line in adb('shell','dumpsys','activity','activities').splitlines() if 'topResumedActivity=' in line)
    assert PACKAGE+'/' in active,'Another app is foreground; stop input'
    path='/data/local/tmp/qixu-'+uuid.uuid4().hex+'.xml'
    adb('shell','uiautomator','dump',path)
    xml=adb('shell','cat',path);adb('shell','rm',path)
    return list(ET.fromstring(xml).iter('node'))

def tap(label):
    nodes=dump()
    def find(items):return next((n for n in items if n.get('package')==PACKAGE and (n.get('text')==label or n.get('content-desc')==label)),None)
    n=find(nodes)
    for _ in range(20):
        if n is not None: break
        scroll=next(n for n in nodes if n.get('scrollable')=='true')
        x,y,r,b=map(int,re.findall(r'\d+',scroll.get('bounds')))
        adb('shell','input','swipe',str((x+r)//2),str(b-40),str((x+r)//2),str(y+40),'350')
        n=find(dump())
    assert n is not None,label
    x,y,r,b=map(int,re.findall(r'\d+',n.get('bounds')))
    adb('shell','input','tap',str((x+r)//2),str((y+b)//2))
    time.sleep(.6)

def check(text):assert any(text in n.get('text','') for n in dump()),text

def back(side):
    width,height=map(int,re.findall(r'(\d+)x(\d+)',adb('shell','wm','size'))[-1])
    start,end=(1,width//3) if side=='left' else (width-2,width*2//3)
    adb('shell','input','swipe',str(start),str(height//2),str(end),str(height//2),'250')

def capture(name):
    nodes=dump()
    assert not any(n.get('package')==PACKAGE and n.get('text','').lstrip().startswith(('{','[')) for n in nodes),'Raw JSON visible'
    (shots/(name+'.png')).write_bytes(adb('exec-out','screencap','-p',binary=True))
    print('PASS '+name,flush=True)

before=config()
adb('shell','am','force-stop',PACKAGE)
adb('shell','am','start','-W','-n',PACKAGE+'/.ui.MainActivity')
tap('设置')
for label,content in [('系统能力','资源控制'),('最近事件','最近'),('网络统计','受控流量'),('CLI','本机或 adb root'),('开源许可','Lucide icons')]:
    for side in ('left','right'):
        tap(label);check(content);capture(label+'-'+side)
        back(side);check('诊断与高级')
        print('PASS '+side+' edge returns '+label+' to settings',flush=True)

tap('配置管理');check('配置项目')
tap('应用例外');check('搜索应用、包名或权限');capture('app-overrides');back('right');check('配置项目')
tap('默认模板');check('启动与后台');back('left');check('配置项目')
tap('默认模板')
tap('禁止' if before['defaults']['autostart']=='deny' else '允许')
tap('允许' if before['defaults']['autostart']=='deny' else '禁止')
back('right');check('放弃未保存的更改');tap('继续编辑');check('启动与后台')
back('left');tap('放弃更改');check('配置项目')
print('PASS nested parent and unsaved-edit gestures',flush=True)
for label,button in [('自动化','添加规则'),('推送','添加应用')]:
    tap(label);capture(label+'-form')
    tap(button);check('选择应用')
    nodes=dump();field=next(n for n in nodes if n.get('class')=='android.widget.EditText')
    x,y,r,b=map(int,re.findall(r'\d+',field.get('bounds')))
    adb('shell','input','tap',str((x+r)//2),str((y+b)//2));dump()
    adb('shell','input','text','dev.local.fuxicontrol');adb('shell','input','keyevent','4')
    tap('栖序');capture(label+'-draft')
    back('left');check('放弃未保存的更改');tap('放弃更改');check('配置项目')
back('right');check('诊断与高级')
assert config()==before,'Settings acceptance changed policy'
print('PASS visual settings, both edge gestures, nested navigation and discarded form drafts; policy unchanged',flush=True)
