"""Stage and preview a verified local baseline; applying it still keeps observation mode."""
import argparse
import hashlib
import json
from pathlib import Path
import shlex
from device import adb,root

parser=argparse.ArgumentParser()
parser.add_argument("baseline",type=Path)
parser.add_argument("--apply",action="store_true")
options=parser.parse_args()
manifest=json.loads((options.baseline/"manifest.json").read_text())
for name,digest in manifest["sha256"].items():
    if hashlib.sha256((options.baseline/name).read_bytes()).hexdigest()!=digest: raise RuntimeError("Baseline mismatch: "+name)
name=options.baseline.name
if not name.startswith("baseline-") or not name.replace("-","").isalnum(): raise ValueError("Invalid baseline directory")
staging="/data/local/tmp/fuxi-"+name
destination="/data/adb/fuxicontrol/migrations/"+name
adb("shell","mkdir","-m","0700",staging)
for file in options.baseline.iterdir(): adb("push",str(file),staging+"/"+file.name)
root("set -e; mkdir -p /data/adb/fuxicontrol/migrations; chmod 0700 /data/adb/fuxicontrol/migrations; test ! -e "+shlex.quote(destination)+
     "; cp -R "+shlex.quote(staging)+" "+shlex.quote(destination)+"; chown -R 0:0 "+shlex.quote(destination)+
     "; chmod 0700 "+shlex.quote(destination)+"; chmod 0600 "+shlex.quote(destination)+"/*; rm -r "+shlex.quote(staging))
request={"command":"migration.import","directory":destination,"dryRun":not options.apply}
print(root("/data/adb/fuxicontrol/fuxictl rpc "+shlex.quote(json.dumps(request))))
