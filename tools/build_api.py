"""Compile the vendored API 102 sources; no unpublished API binary dependency."""
import hashlib
import os
from pathlib import Path
import subprocess
import urllib.request

project=Path(__file__).resolve().parents[1]
sdk=os.environ.get("ANDROID_HOME")
if not sdk:
    settings=dict(line.split("=",1) for line in (project/"local.properties").read_text().splitlines() if "=" in line)
    sdk=settings["sdk.dir"]
dependencies=project/"private/build-dependencies"
dependencies.mkdir(parents=True,exist_ok=True)
annotation=dependencies/"annotation-jvm-1.8.1.jar"
checksum="9aab326d9492800991854360ac248f493ce7f7c3183519309b78ace9e240f6f6"
if not annotation.exists():
    urllib.request.urlretrieve("https://dl.google.com/dl/android/maven2/androidx/annotation/annotation-jvm/1.8.1/annotation-jvm-1.8.1.jar",annotation)
if hashlib.sha256(annotation.read_bytes()).hexdigest()!=checksum:
    raise RuntimeError("AndroidX annotation checksum mismatch")
output=project/"vendor/api-classes"
output.mkdir(exist_ok=True)
java=Path(os.environ["JAVA_HOME"])/"bin"
subprocess.run([str(java/"javac"),"-source","17","-target","17","-cp",
                str(Path(sdk)/"platforms/android-36/android.jar")+os.pathsep+str(annotation),
                "-d",str(output),*[str(p) for p in (project/"vendor/api-src").rglob("*.java")]],check=True)
subprocess.run([str(java/"jar"),"cf",str(project/"vendor/api-102.jar"),"-C",str(output),"."],check=True)
