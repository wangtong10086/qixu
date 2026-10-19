#!/system/bin/sh
# Wait on the local daemon; no network checks or polling of application data.
BASE=/data/adb/fuxicontrol
umask 077
if [ "${1:-}" != --locked ]; then
    exec /data/adb/magisk/busybox flock -n "$BASE/supervisor.lock" sh "$0" --locked
fi
echo $$ >"$BASE/supervisor.pid"
child=
cleanup() {
    trap - EXIT
    if [ -n "$child" ]; then kill "$child" 2>/dev/null; wait "$child"; fi
    rm -f "$BASE/supervisor.pid" "$BASE/service.ready"
}
trap cleanup EXIT
trap 'exit 0' TERM INT
while [ ! -f "$BASE/service.stopped" ]; do
    rm -f "$BASE/service.ready"
    [ ! -f "$BASE/service.log" ] || mv "$BASE/service.log" "$BASE/service.previous.log"
    CLASSPATH=$BASE/controller.apk app_process /system/bin dev.local.fuxicontrol.service.RootDaemon "$BASE" >"$BASE/service.log" 2>&1 </dev/null &
    child=$!
    echo "$child" >"$BASE/service.pid"
    wait "$child"
    result=$?
    child=
    echo "$(date -Iseconds) daemon exited: $result" >>"$BASE/supervisor.log"
    [ ! -f "$BASE/service.stopped" ] || break
    sleep 5 &
    child=$!
    wait "$child"
    child=
done
