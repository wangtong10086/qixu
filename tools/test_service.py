"""USB-device acceptance: local RPC offline, daemon restart and explicit stop.

Temporarily disables Wi-Fi/mobile data and restores their previous state.
Does not change application policy.
"""
import sys,json,shlex,time,subprocess
from pathlib import Path
from device import root
base='/data/adb/fuxicontrol/'
def rpc(command):return json.loads(root(base+'fuxictl rpc '+shlex.quote(json.dumps({'command':command}))))
before=rpc('config.get')
(Path(__file__).resolve().parents[1]/'private/service-test-policy.json').write_text(json.dumps(before))
print('Before:',rpc('status')['lastError'],flush=True)
# An intentional stop must suppress recovery.
root('sh '+base+'stop.sh');time.sleep(7)
assert not root('test ! -e '+base+'supervisor.pid || cat '+base+'supervisor.pid').strip()
root('sh '+base+'start.sh')
print('PASS intentional stop and explicit start',flush=True)
wifi=root('settings get global wifi_on').strip()
data=root('settings get global mobile_data').strip()
assert wifi in ('0','1','2') and data in ('0','1'),(wifi,data)
try:
 root('svc wifi disable; svc data disable')
 time.sleep(3)
 print('Offline:',root('cmd wifi status').splitlines()[0], 'mobile_data='+root('settings get global mobile_data').strip(),flush=True)
 assert rpc('config.get')==before
 pid=root('cat '+base+'service.pid').strip()
 root('kill -9 '+pid)
 deadline=time.monotonic()+40
 while time.monotonic()<deadline:
  time.sleep(2)
  newpid=root('cat '+base+'service.pid').strip()
  if newpid!=pid and root('test ! -f '+base+'service.ready || cat '+base+'service.ready').strip()==newpid:break
 else:raise AssertionError('Daemon did not recover')
 status=rpc('status');assert status['service']
 assert rpc('config.get')==before
 print('PASS offline RPC and automatic restart:',pid,'->',newpid,flush=True)
finally:
 root('svc wifi '+('enable' if wifi!='0' else 'disable')+'; svc data '+('enable' if data=='1' else 'disable'))
 print('Network switches restored',flush=True)
time.sleep(8)
s=rpc('status');print('Final',json.dumps({'mode':s['mode'],'hooks':len(s['hooks']),'lastError':s['lastError']}),flush=True)
assert not s['lastError'] and all(s['hooks'].values())
j=json.loads(root('cat '+base+'resources.json'))
assert j['bootId']==root('cat /proc/sys/kernel/random/boot_id').strip()
print('PASS boot-scoped journal',flush=True)
