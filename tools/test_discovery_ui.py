"""Read-only discovery acceptance on an unlocked device with Qixu 0.3 installed.

The optional preview lifecycle check requires an installed Fuxi Design app and only starts/stops it.
Local captures contain installed-app metadata and stay under ignored private/.
"""
import json
from pathlib import Path
import re
import shlex
import time
import uuid
import xml.etree.ElementTree as ET
from device import adb, root

PACKAGE = 'dev.local.fuxicontrol'
shots = Path(__file__).resolve().parents[1] / 'private/discovery-acceptance'
shots.mkdir(parents=True, exist_ok=True)

def rpc(command, **fields):
    return json.loads(root('/data/adb/fuxicontrol/fuxictl rpc ' + shlex.quote(json.dumps(dict(command=command, **fields)))))

def dump():
    time.sleep(.6)
    foreground = next(line for line in adb('shell', 'dumpsys', 'activity', 'activities').splitlines() if 'topResumedActivity=' in line)
    assert PACKAGE + '/' in foreground, 'Another app is foreground; stop input'
    remote = '/data/local/tmp/qixu-' + uuid.uuid4().hex + '.xml'
    adb('shell', 'uiautomator', 'dump', remote)
    content = adb('shell', 'cat', remote)
    adb('shell', 'rm', remote)
    return list(ET.fromstring(content).iter('node'))

def tap_node(node):
    x, y, r, b = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((x+r)//2), str((y+b)//2))
    return dump()

def tap(label):
    nodes = dump()
    match = next((n for n in nodes if n.get('package') == PACKAGE and (n.get('text') == label or n.get('content-desc') == label)), None)
    if match is None:
        scroll = next(n for n in nodes if n.get('scrollable') == 'true' and n.get('class') == 'android.view.View')
        x,y,r,b = map(int, re.findall(r'\d+', scroll.get('bounds')))
        adb('shell','input','swipe',str((x+r)//2),str(b-50),str((x+r)//2),str(y+50),'350')
        match = next(n for n in dump() if n.get('package') == PACKAGE and (n.get('text') == label or n.get('content-desc') == label))
    return tap_node(match)

def search(value):
    tap_node(next(n for n in dump() if n.get('class') == 'android.widget.EditText' and n.get('package') == PACKAGE))
    adb('shell','input','text',value)
    adb('shell','input','keyevent','4')

def check(text):
    assert any(text in n.get('text','') for n in dump()), text

def capture(name):
    (shots/(name+'.png')).write_bytes(adb('exec-out','screencap','-p',binary=True))
    print('PASS '+name, flush=True)

before = rpc('config.get')
index = rpc('apps.index')['apps']
for scope in ('declared','granted'):
    expected = {app['package'] for app in index if any('CAMERA' in permission['name'] and (scope != 'granted' or permission['granted']) for permission in app['permissions'])}
    actual = {app['package'] for app in rpc('apps.index',query='CAMERA',scope=scope)['apps']}
    assert expected == actual, scope
    print('PASS '+scope+' permission index: '+str(len(actual)),flush=True)

adb('shell','am','force-stop',PACKAGE)
adb('shell','am','start','-W','-n',PACKAGE+'/.ui.MainActivity')
check('已连接');check('运行中的应用');capture('overview')
processes = rpc('apps.running')['apps'][PACKAGE]['processes']
pids = {int(pid) for pid in adb('shell','pidof',PACKAGE).split()}
assert pids.issubset({process['pid'] for process in processes})
print('PASS real manager PID in running snapshot',flush=True)
tap('应用');search('CAMERA');tap('声明权限')
check(str(len(rpc('apps.index',query='CAMERA',scope='declared')['apps']))+' 个应用');capture('declared-permissions')
tap('已授予权限')
check(str(len(rpc('apps.index',query='CAMERA',scope='granted')['apps']))+' 个应用');capture('granted-permissions')
tap('清除');tap('综合');search('fuxicontrol')
check('栖序')
assert any(n.get('content-desc') == '栖序图标' for n in dump()), 'Real icon not loaded'
capture('app-name-search')
tap('栖序');check('启动与后台')
assert any(n.get('content-desc') == '栖序图标' for n in dump()), 'Detail icon missing'
capture('app-detail-icon');tap('返回')
tap('清除');tap('后台');capture('running-apps')
tap('策略');search('CAMERA')
check('匹配应用')
assert any(n.get('content-desc','').endswith('图标') for n in dump()), 'Policy result icon missing'
capture('policy-search')
tap('清除');tap('概览')
after=rpc('config.get')
# Observer events may advance the catalog revision independently of policy content.
assert {k:v for k,v in before.items() if k!='revision'} == {k:v for k,v in after.items() if k!='revision'}, 'Read-only acceptance changed policy'
assert all(rpc('status')['hooks'].values())
print('PASS UI search, permission scopes, running view, policy search; policy unchanged',flush=True)

if '--preview-lifecycle' in __import__('sys').argv:
    preview=PACKAGE+'.design'
    try:
        adb('shell','am','start','-W','-n',preview+'/.DesignActivity')
        assert preview in rpc('apps.running')['apps'], 'Preview process missing after launch'
    finally:
        adb('shell','am','force-stop',preview)
        adb('shell','am','start','-W','-n',PACKAGE+'/.ui.MainActivity')
    assert preview not in rpc('apps.running')['apps'], 'Stopped preview retained as live'
    after=rpc('config.get')
    assert {k:v for k,v in before.items() if k!='revision'} == {k:v for k,v in after.items() if k!='revision'}, 'Preview test changed policy'
    print('PASS preview start/stop reflected in real process list; policy unchanged',flush=True)
