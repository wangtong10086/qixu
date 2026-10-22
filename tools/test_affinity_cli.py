"""Device CLI file round-trip and conflict checks, restricted to the disposable probe."""
import json
import shlex
import subprocess
from pathlib import Path
from device import root

PACKAGE = 'dev.local.fuxicontrol.probe'
CTL = '/data/adb/fuxicontrol/fuxictl '
DIRECTORY = '/data/local/tmp/qixu-affinity-cli-test'

def cli(*args):
    return json.loads(root(CTL + shlex.join(args)))

def rpc(command, **fields):
    return cli('rpc', json.dumps(dict(command=command, **fields)))

before = rpc('config.get')
saved = before['apps'].get(PACKAGE)
checks = []
root('mkdir ' + DIRECTORY)
try:
    root('printf %s '+shlex.quote('{"cpuAffinity":{"foreground":"little"}}')+' > '+DIRECTORY+'/policy.json')
    preview = cli('app', 'apply', PACKAGE, DIRECTORY+'/policy.json', '--dry-run')
    assert preview['dryRun'] and rpc('status')['revision'] == before['revision']
    checks.append('fileDryRun')
    changed = cli('app', 'apply', PACKAGE, DIRECTORY+'/policy.json')
    assert changed['config']['apps'][PACKAGE]['cpuAffinity']['foreground'] == 'little'
    cli('app', 'export', PACKAGE, DIRECTORY+'/export.json')
    exported = json.loads(root('cat '+DIRECTORY+'/export.json'))
    assert exported == changed['config']['apps'][PACKAGE]
    checks.append('fileApplyExport')
    try:
        cli('app', 'apply', PACKAGE, DIRECTORY+'/policy.json', '--expected-revision', str(before['revision']))
        raise AssertionError('Stale revision was accepted')
    except subprocess.CalledProcessError:
        assert rpc('status')['revision'] == changed['config']['revision']
    checks.append('staleRevisionRejected')
    result = cli('app', 'unset', PACKAGE, 'cpuAffinity')
    assert 'cpuAffinity' not in result['config']['apps'][PACKAGE]
    checks.append('unsetInheritance')
    result = cli('app', 'apply', PACKAGE, DIRECTORY+'/export.json', '--replace')
    assert result['config']['apps'][PACKAGE] == exported
    checks.append('replaceRoundTrip')
finally:
    if saved is None: rpc('app.inherit', package=PACKAGE)
    else: rpc('app.replace', package=PACKAGE, patch=saved)
    assert rpc('config.get')['apps'].get(PACKAGE) == saved
    root('rm -r '+DIRECTORY)
    Path('private/affinity-cli-acceptance.json').write_text(json.dumps({'checks':checks,'restored':True},indent=2))
print(json.dumps(checks))
