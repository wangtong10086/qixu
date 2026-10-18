"""Build artifacts only. WSL can reuse the installed Windows NDK."""
import os
from pathlib import Path
import subprocess

project = Path(__file__).resolve().parents[1]
ndk = Path(os.environ.get("ANDROID_NDK_HOME", "/mnt/d/xiaomi13/tools/android-dev/sdk/ndk/29.0.14206865"))
output = project / "app/src/main/jniLibs/arm64-v8a"
output.mkdir(parents=True, exist_ok=True)
clang = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android35-clang"
args = ["-shared", "-fPIC", "-O2", "-Wall", "-Wextra", "-Wno-unused-parameter", "-Wl,-z,max-page-size=16384"]
if clang.exists():
    source, target = str(project / "native/controller.c"), str(output / "libfuxicontrol.so")
else:
    clang = ndk / "toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe"
    args.insert(0, "--target=aarch64-linux-android35")
    def win(path):
        return subprocess.check_output(["wslpath", "-w", str(path)], text=True).strip()
    source, target = win(project / "native/controller.c"), win(output / "libfuxicontrol.so")
subprocess.run([str(clang), *args, source, "-o", target], check=True)
ctl_source, ctl_target = str(project / "native/networkctl.c"), str(project / "app/build/networkctl")
if clang.suffix == ".exe":
    ctl_source, ctl_target = win(project / "native/networkctl.c"), win(project / "app/build/networkctl")
subprocess.run([str(clang), *[a for a in args if a not in ("-shared", "-fPIC")], ctl_source, "-o", ctl_target], check=True)
obj = project / "app/src/main/assets/network.bpf.o"
subprocess.run([os.environ.get("BPF_CLANG", "clang-21"), "-target", "bpf", "-O2", "-g", "-I/usr/include/x86_64-linux-gnu",
                "-c", str(project / "native/network.bpf.c"), "-o", str(obj)], check=True)
print(target, obj)
