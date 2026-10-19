#!/system/bin/sh
set -eu
BASE=/data/adb/fuxicontrol
test "$(id -u)" = 0
touch "$BASE/service.stopped"
if [ -f "$BASE/supervisor.pid" ]; then
    supervisor=$(cat "$BASE/supervisor.pid")
    if [ -r "/proc/$supervisor/cmdline" ] && tr '\000' ' ' <"/proc/$supervisor/cmdline" | grep -q "$BASE/supervise.sh"; then
        kill "$supervisor"
        attempt=0
        while [ -r "/proc/$supervisor/cmdline" ] && tr '\000' ' ' <"/proc/$supervisor/cmdline" | grep -q "$BASE/supervise.sh"; do
            attempt=$((attempt+1))
            if [ "$attempt" -ge 100 ]; then echo 'Supervisor shutdown timed out' >&2; exit 1; fi
            sleep 0.1
        done
    fi
fi
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
