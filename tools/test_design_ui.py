"""Exercise preview-only UI flows and capture their real rendered states."""
import argparse,subprocess,time,re
from pathlib import Path
import xml.etree.ElementTree as ET
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);a=p.parse_args()
out=Path(__file__).resolve().parents[1]/'docs/compose-previews'
pkg='dev.local.fuxicontrol.design'
def adb(*v,binary=False):return subprocess.check_output(['adb','-s',a.serial,*v],text=not binary)
def tree():
    adb('shell','uiautomator','dump','--compressed','/data/local/tmp/fuxi-design-test.xml')
    t=ET.fromstring(adb('shell','cat','/data/local/tmp/fuxi-design-test.xml'))
    assert any(n.get('package')==pkg for n in t.iter('node')),'Preview no longer foreground'
    return t
def center(n):
    x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')));return ((x1+x2)//2,(y1+y2)//2)
def tap(text):
    n=next(n for n in tree().iter('node') if n.get('text')==text)
    adb('shell','input','tap',*map(str,center(n)))
def has(text):return any(n.get('text')==text for n in tree().iter('node'))
def capture(name):
    tree();(out/(name+'.png')).write_bytes(adb('exec-out','screencap','-p',binary=True))
def launch(v,page):
    adb('shell','am','start','-S','-W','-n',pkg+'/.DesignActivity','--es','variant',v,'--es','page',page);time.sleep(1)
launch('A','概览');tap('A · 原生简洁');capture('version-picker');tap('C · 高效管理');assert has('C · 高效管理');print('PASS version switch',flush=True)
tap('应用');tap('有例外');assert has('示例下载') and not has('示例音乐');capture('filter');tap('示例下载');print('PASS application filter and detail',flush=True)
tap('保存更改');assert has('保存应用策略？');capture('save-confirmation');tap('取消')
# The upload input is below the initial fold; scroll within the actual scrollable node.
t=tree();scroll=next(n for n in t.iter('node') if n.get('scrollable')=='true')
x1,y1,x2,y2=map(int,re.findall(r'\d+',scroll.get('bounds')))
adb('shell','input','swipe',str((x1+x2)//2),str(y2-150),str((x1+x2)//2),str(y1+150),'400')
t=tree();field=next(n for n in t.iter('node') if n.get('class')=='android.widget.EditText')
adb('shell','input','tap',*map(str,center(field)))
adb('shell','input','keyevent','KEYCODE_MOVE_END')
for _ in range(3):adb('shell','input','keyevent','KEYCODE_DEL')
adb('shell','input','text','0');adb('shell','input','keyevent','KEYCODE_BACK');tap('保存更改')
assert has('请输入大于 0 的整数');capture('invalid-rate');print('PASS invalid rate stays editable',flush=True)
tap('概览');assert has('放弃未保存的更改？');capture('unsaved');tap('继续编辑')
tap('恢复继承');tap('恢复继承');assert has('上传限速');print('PASS unsaved navigation and restore',flush=True)
launch('B','设置');tap('交互状态');tap('服务断开');assert has('服务已断开 · 当前只读');capture('disconnected');tap('重连');assert not has('服务已断开 · 当前只读');print('PASS disconnected read-only and reconnect',flush=True)
launch('B','设置');tap('交互状态');tap('配置版本冲突');tap('保存模板');assert has('配置已被更新');capture('conflict');tap('重新加载');print('PASS revision conflict',flush=True)
launch('A','默认模板');tap('保存模板');assert has('确认模板更改');capture('template-confirmation');tap('确认保存');print('PASS template confirmation',flush=True)
launch('A','应用');capture('A-apps')
launch('A','概览')
adb('shell','rm','/data/local/tmp/fuxi-design-test.xml')
