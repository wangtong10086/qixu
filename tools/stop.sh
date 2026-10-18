#!/system/bin/sh
set -eu
BASE=/data/adb/fuxicontrol
test "$(id -u)" = 0
if [ -f "$BASE/service.pid" ]; then
    pid=$(cat "$BASE/service.pid")
    if [ -r "/proc/$pid/cmdline" ] && tr '\000' ' ' <"/proc/$pid/cmdline" | grep -q 'dev.local.fuxicontrol.service.RootDaemon'; then
        kill "$pid"
        attempt=0
        while [ -r "/proc/$pid/cmdline" ] && tr '\000' ' ' <"/proc/$pid/cmdline" | grep -q 'dev.local.fuxicontrol.service.RootDaemon'; do
            attempt=$((attempt+1))
            if [ "$attempt" -ge 100 ]; then echo 'Controller shutdown timed out' >&2; exit 1; fi
            sleep 0.1
        done
    fi
fi
