"""Install the new controller in observation mode. Does not enable Vector or alter Thanox."""
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
from device import adb, root, archive

project = Path(__file__).resolve().parents[1]
import argparse
parser=argparse.ArgumentParser()
parser.add_argument("--apk",type=Path,default=project/"app/build/outputs/apk/debug/app-debug.apk")
options=parser.parse_args()
apk=options.apk
baselines=sorted((project / "private").glob("baseline-*/manifest.json"))
if not baselines:
    raise RuntimeError("Run export_thanos.py before installation")
manifest=baselines[-1]
for name,expected in json.loads(manifest.read_text())["sha256"].items():
    if hashlib.sha256((manifest.parent/name).read_bytes()).hexdigest()!=expected:
        raise RuntimeError("Baseline checksum mismatch: "+name)
import gzip,io,tarfile
with tarfile.open(fileobj=io.BytesIO(gzip.decompress((manifest.parent/"original-state.tar.gz").read_bytes()))) as original:
    original.getmembers()
cert = subprocess.check_output(["apksigner", "verify", "--print-certs", str(apk)], text=True)
signer = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]+)", cert).group(1)
# Capture this controller's own state before replacing its executable files.
import datetime
existing=root("test ! -f /data/adb/fuxicontrol/controller.apk || echo installed").strip()
if existing:
    adb("push",str(project/"tools/stop.sh"),"/data/local/tmp/fuxi-stop.sh")
    root("set -e; sh /data/local/tmp/fuxi-stop.sh; rm /data/local/tmp/fuxi-stop.sh")
    backup=project / "private" / ("controller-before-"+datetime.datetime.now().strftime("%Y%m%d-%H%M%S")+".tar.gz")
    with backup.open("xb") as handle:
        handle.write(archive(["/data/adb/fuxicontrol"]))
    backup.chmod(0o600)
print(adb("install", "--no-incremental", "--no-streaming", "-r", str(apk)))
root("mkdir -p /data/adb/fuxicontrol; chmod 0700 /data/adb/fuxicontrol")
adb("push", str(apk), "/data/local/tmp/fuxi-controller.apk")
root("cp /data/local/tmp/fuxi-controller.apk /data/adb/fuxicontrol/controller.apk.new; chmod 0600 /data/adb/fuxicontrol/controller.apk.new; mv /data/adb/fuxicontrol/controller.apk.new /data/adb/fuxicontrol/controller.apk; rm /data/local/tmp/fuxi-controller.apk")
native = project / "app/src/main/jniLibs/arm64-v8a/libfuxicontrol.so"
adb("push", str(native), "/data/local/tmp/fuxi-native.so")
root("set -e; cp /data/local/tmp/fuxi-native.so /data/adb/fuxicontrol/libfuxicontrol.so.new; chmod 0600 /data/adb/fuxicontrol/libfuxicontrol.so.new; mv /data/adb/fuxicontrol/libfuxicontrol.so.new /data/adb/fuxicontrol/libfuxicontrol.so; rm /data/local/tmp/fuxi-native.so")
root("printf '%s\\n' " + shlex.quote(signer) + " > /data/adb/fuxicontrol/manager.sha256; chmod 0600 /data/adb/fuxicontrol/manager.sha256")
command = "#!/system/bin/sh\nCLASSPATH=/data/adb/fuxicontrol/controller.apk exec app_process /system/bin dev.local.fuxicontrol.service.Cli \"$@\"\n"
temp = project / "app/build/fuxictl"
temp.write_text(command)
adb("push", str(temp), "/data/local/tmp/fuxictl")
root("cp /data/local/tmp/fuxictl /data/adb/fuxicontrol/fuxictl; chmod 0700 /data/adb/fuxicontrol/fuxictl; rm /data/local/tmp/fuxictl")
for source,target,mode in [
    (project/"tools/start.sh","start.sh","0700"),
    (project/"tools/stop.sh","stop.sh","0700"),
    (project/"tools/recover.sh","recover.sh","0700"),
    (project/"tools/network-start.sh","network-start.sh","0700"),
    (project/"app/build/networkctl","networkctl","0700"),
    (project/"app/src/main/assets/network.bpf.o","network.bpf.o","0600"),
]:
    staging="/data/local/tmp/fuxi-"+target
    adb("push",str(source),staging)
    destination="/data/adb/fuxicontrol/"+target
    root("cp "+shlex.quote(staging)+" "+shlex.quote(destination+".new")+
         "; chmod "+mode+" "+shlex.quote(destination+".new")+
         "; mv "+shlex.quote(destination+".new")+" "+shlex.quote(destination)+"; rm "+shlex.quote(staging))
    expected=hashlib.sha256(source.read_bytes()).hexdigest()
    actual=root("sha256sum "+shlex.quote(destination)).split()[0]
    if actual!=expected: raise RuntimeError("Device artifact checksum mismatch: "+target)
adb("push",str(project/"tools/boot.sh"),"/data/local/tmp/fuxi-boot.sh")
root("cp /data/local/tmp/fuxi-boot.sh /data/adb/service.d/95-fuxicontrol.sh; chmod 0700 /data/adb/service.d/95-fuxicontrol.sh; rm /data/local/tmp/fuxi-boot.sh")
for source,target in [(apk,"controller.apk"),(native,"libfuxicontrol.so")]:
    expected=hashlib.sha256(source.read_bytes()).hexdigest()
    if root("sha256sum /data/adb/fuxicontrol/"+target).split()[0]!=expected:
        raise RuntimeError("Device artifact checksum mismatch: "+target)
print(root("sh /data/adb/fuxicontrol/start.sh"))
print("Installed and started; Vector activation and Thanox handover remain separate.")
