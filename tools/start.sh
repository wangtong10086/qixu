#!/system/bin/sh
set -eu
BASE=/data/adb/fuxicontrol
rm -f "$BASE/service.stopped"
if [ -f "$BASE/supervisor.pid" ]; then
    supervisor=$(cat "$BASE/supervisor.pid")
else
    supervisor=
fi
if [ -z "$supervisor" ] || [ ! -r "/proc/$supervisor/cmdline" ] || ! tr '\000' ' ' <"/proc/$supervisor/cmdline" | grep -q "$BASE/supervise.sh"; then
    rm -f "$BASE/service.ready"
    nohup sh "$BASE/supervise.sh" >>"$BASE/supervisor.log" 2>&1 </dev/null &
fi
attempt=0
while :; do
    if [ -f "$BASE/service.ready" ] && [ -f "$BASE/service.pid" ]; then
        pid=$(cat "$BASE/service.pid")
        if [ "$(cat "$BASE/service.ready")" = "$pid" ] && kill -0 "$pid" 2>/dev/null; then exit 0; fi
    fi
    attempt=$((attempt+1))
    if [ "$attempt" -ge 120 ]; then echo 'Local controller startup timed out; inspect service and supervisor logs' >&2; exit 1; fi
    sleep 0.25
done
