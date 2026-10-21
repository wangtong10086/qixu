"""Real-device component-state acceptance. Requires the installed probe APK.

Only changes the probe policy and component, restoring both in finally.
"""
import sys,json,shlex
from pathlib import Path
from device import root
P='dev.local.fuxicontrol.probe'; C=P+'.LifecycleService'
def rpc(command,**args):
 return json.loads(root('/data/adb/fuxicontrol/fuxictl rpc '+shlex.quote(json.dumps(dict(command=command,**args)))))
def row():
 return next(x for x in rpc('services.list',package=P)['services'] if x['class']==C)
original_setting=row()['setting']
assert original_setting==0, 'Probe component must start in default state'
before=rpc('config.get');out=Path('private/background-acceptance');out.mkdir(exist_ok=True)
(out/'config-before.json').write_text(json.dumps(before,ensure_ascii=False,indent=2))
try:
 rpc('service.policy',package=P,**{'class':C,'mode':'block','dryRun':True})
 assert row()['setting']==original_setting
 print('PASS dry run unchanged',flush=True)
 rpc('service.policy',package=P,**{'class':C,'mode':'block'})
 assert row()['setting']==2 and row()['blocked']
 print('PASS component disabled',flush=True)
 rpc('service.policy',package=P,**{'class':C,'mode':'system'})
 assert row()['setting']==0 and not row()['blocked']
 print('PASS original default restored',flush=True)
 root('pm disable '+P+'/'+C)
 rpc('service.policy',package=P,**{'class':C,'mode':'block'})
 rpc('service.policy',package=P,**{'class':C,'mode':'system'})
 assert row()['setting']==2
 print('PASS externally disabled component stays disabled',flush=True)
finally:
 root('pm default-state '+P+'/'+C)
 if P in before['apps']: rpc('app.replace',package=P,patch=before['apps'][P])
 else: rpc('app.inherit',package=P)
after=rpc('config.get')
assert before['apps']==after['apps'] and before['defaults']==after['defaults']
assert rpc('status')['lastError']==''
print('PASS policy restored and daemon healthy',flush=True)
