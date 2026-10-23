"""Measure actual foreground resource restoration; only drives the disposable probe."""
import argparse,json,re,shlex,subprocess,time
from pathlib import Path
from device import root,adb
P='dev.local.fuxicontrol.probe'
a=argparse.ArgumentParser();a.add_argument('--output',default='private/foreground-latency.json');a.add_argument('--rounds',type=int,default=3);a.add_argument('--busy',action='store_true');o=a.parse_args()
def rpc(c,**kw):return json.loads(root('/data/adb/fuxicontrol/fuxictl rpc '+shlex.quote(json.dumps(dict(command=c,**kw)))))
def guard():
 s=adb('shell','dumpsys activity activities | grep topResumedActivity')
 m=re.search(r'u0 ([^/ ]+)/',s)
 if not m or m[1] not in {P,'com.miui.home','dev.local.fuxicontrol'}:raise RuntimeError('Another app is foreground; stopped')
def wait(fn):
 end=time.monotonic()+30
 while time.monotonic()<end:
  v=fn()
  if v:return v
  time.sleep(.2)
 raise AssertionError('State did not converge')
saved=rpc('config.get')['apps'].get(P);report=[]
try:
 guard();rpc('app.replace',package=P,patch={'autostart':'allow','protected':False,'background':'normal','cpuNice':10,'uclampMax':512,'cpuAffinity':{'foreground':'system','background':'little'},'cpuQuota':{'periodUs':100000,'foregroundUs':0,'backgroundUs':50000}})
 adb('shell','am','start','-W','-n',P+'/.Main','--ez','affinity','true')
 pid=wait(lambda:adb('shell','pidof '+P+':affinity || true').strip());uid=rpc('app.get',package=P)['effective']['uid']
 tid=root('for t in /proc/'+pid+'/task/*; do [ "$(cat "$t/comm")" = RenderProbe ] && basename "$t"; done; true').strip()
 for i in range(o.rounds):
  guard();adb('shell','input','keyevent','3')
  wait(lambda:root('cat /proc/'+tid+'/status').find('Cpus_allowed_list:\t0-2')>=0)
  wait(lambda:root('cat /dev/cpuctl/qixu_uid_'+str(uid)+'/cpu.cfs_quota_us 2>/dev/null || true').strip()=='50000')
  capture=Path(o.output+'.'+str(i)+'.txt');capture.parent.mkdir(parents=True,exist_ok=True)
  script='for i in $(seq 1 160); do read up rest < /proc/uptime; mask=$(sed -n "s/^Cpus_allowed_list:[[:space:]]*//p" /proc/'+tid+'/status); clamp=$(sed -n "s/^uclamp.max[[:space:]]*:[[:space:]]*//p" /proc/'+tid+'/sched); quota=$(cat /dev/cpuctl/qixu_uid_'+str(uid)+'/cpu.cfs_quota_us 2>/dev/null); echo "$up $mask $clamp ${quota:--1}"; sleep .02; done'
  with capture.open('w') as f:
   proc=subprocess.Popen(['adb','shell','su -c '+shlex.quote(script)],stdout=f)
   time.sleep(.2);guard()
   busy=None
   if o.busy:
    cmd='/data/adb/fuxicontrol/fuxictl rpc '+shlex.quote(json.dumps({'command':'runtime.reconcile'}))
    busy=subprocess.Popen(['adb','shell','su -c '+shlex.quote(cmd)],stdout=subprocess.DEVNULL)
   began=float(root('cat /proc/uptime').split()[0]);adb('shell','am','start','-W','-n',P+'/.Main');proc.wait(timeout=30)
   if busy is not None:assert busy.wait(timeout=30)==0
  rows=[x.split() for x in capture.read_text().splitlines()];restored=next((float(x[0]) for x in rows if len(x)==4 and float(x[0])>=began and x[1:] == ['0-7','1024','-1']),None)
  report.append({'round':i,'startUptime':began,'restoredUptime':restored,'delaySeconds':None if restored is None else round(restored-began,3)})
  print(report[-1],flush=True)
  assert restored is not None,report
finally:
 if saved is None:rpc('app.inherit',package=P)
 else:rpc('app.replace',package=P,patch=saved)
 adb('shell','am','force-stop',P)
 Path(o.output).write_text(json.dumps(report,indent=2))
