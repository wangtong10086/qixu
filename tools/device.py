import os
import shlex
import subprocess
import gzip
import io
import tarfile


def adb(*args, binary=False):
    return subprocess.check_output([os.environ.get("ADB", "adb"), *args], text=not binary)


def root(script):
    return adb("shell", "su -c " + shlex.quote(script))


def archive(paths):
    """Keep tar diagnostics out of adb exec-out's merged binary stream."""
    members=" ".join(shlex.quote(p.lstrip("/")) for p in paths)
    command="umask 077; tar -czf - -C / "+members+" 2>/data/local/tmp/fuxi-export-tar.log"
    data=adb("exec-out","su -c "+shlex.quote(command),binary=True)
    decoded=gzip.decompress(data)
    with tarfile.open(fileobj=io.BytesIO(decoded),mode="r:") as validated:
        if not validated.getmembers(): raise RuntimeError("Archive contains no members")
    root("rm /data/local/tmp/fuxi-export-tar.log")
    return data
