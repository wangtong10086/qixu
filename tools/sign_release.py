"""Keep one private signing identity across locally built releases."""
import os
from pathlib import Path
import secrets
import subprocess

project=Path(__file__).resolve().parents[1]
keys=project/"private"/"signing"
keys.mkdir(parents=True,exist_ok=True,mode=0o700)
password=keys/"password"
keystore=keys/"fuxicontrol.p12"
if not keystore.exists():
    with password.open("x") as handle:
        handle.write(secrets.token_urlsafe(48)+"\n")
    password.chmod(0o600)
    subprocess.run(["keytool","-genkeypair","-keystore",str(keystore),"-storetype","PKCS12",
        "-storepass:file",str(password),"-alias","fuxicontrol","-keyalg","RSA","-keysize","3072",
        "-validity","10000","-dname","CN=Fuxi Control local releases"],check=True)
    keystore.chmod(0o600)
unsigned=project/"app/build/outputs/apk/release/app-release-unsigned.apk"
release=project/"private"/"releases"
release.mkdir(exist_ok=True,mode=0o700)
signed=release/"fuxicontrol-release.apk"
subprocess.run(["apksigner","sign","--ks",str(keystore),"--ks-pass","file:"+str(password),
    "--out",str(signed),str(unsigned)],check=True)
subprocess.run(["apksigner","verify","--print-certs",str(signed)],check=True)
print(signed)
