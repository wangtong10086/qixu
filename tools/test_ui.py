"""Read-only smoke test of this project's foreground UI, on an unlocked phone."""
from pathlib import Path
import re
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET
from device import adb

project=Path(__file__).resolve().parents[1]
adb("shell","am","start","-W","-n","dev.local.fuxicontrol/.ui.MainActivity")
def dump():
    time.sleep(0.5)
    focus=adb("shell","dumpsys","window")
    focused=next(line for line in focus.splitlines() if "mCurrentFocus=" in line)
    if "dev.local.fuxicontrol/" not in focused:
        raise RuntimeError("Controller is no longer foreground; leaving the user's screen alone")
    remote="/data/local/tmp/fuxi-ui-"+uuid.uuid4().hex+".xml"
    output=adb("shell","timeout","12","uiautomator","dump","--compressed",remote)
    if "dumped to:" not in output: raise RuntimeError(output)
    local=project/"private"/"ui-latest.xml"
    adb("pull",remote,str(local));adb("shell","rm",remote)
    return list(ET.parse(local).iter("node"))
def tap(nodes,label):
    node=next(node for node in nodes if node.get("text")==label)
    left,top,right,bottom=map(int,re.findall(r"\d+",node.get("bounds")))
    adb("shell","input","tap",str((left+right)//2),str((top+bottom)//2))
    return dump()
def texts(nodes): return "\n".join(n.get("text","") for n in nodes)
nodes=dump()
if '"service": true' not in texts(nodes): raise RuntimeError("Service connection not shown")
nodes=tap(nodes,"能力")
if '"requiredVectorApi": 102' not in texts(nodes): raise RuntimeError("Capabilities query failed")
for page,expected in [("默认模板",'"autostart"'),("自动化","JSON 配置"),("推送","JSON 配置"),("应用","搜索应用或包名")]:
    nodes=tap(nodes,page)
    if expected not in texts(nodes): raise RuntimeError("Page did not load: "+page)
    print("PASS "+page)
nodes=tap(nodes,"状态")
if '"service": true' not in texts(nodes): raise RuntimeError("Final status query failed")
print("PASS authenticated service connection, capabilities and all five pages")
