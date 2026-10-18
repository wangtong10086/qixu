#!/system/bin/sh
set -eu
BASE=/data/adb/fuxicontrol
PIN=/sys/fs/bpf/fuxicontrol
mkdir -p "$PIN/progs"
chmod 0700 "$PIN" "$PIN/progs"
bpftool prog loadall "$BASE/network.bpf.o" "$PIN/progs" pinmaps "$PIN"
