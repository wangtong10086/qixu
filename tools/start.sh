#!/system/bin/sh
set -eu
BASE=/data/adb/fuxicontrol
magiskpolicy --live 'attribute fuxicontrol_socket_type' \
    'type fuxicontrol_socket fuxicontrol_socket_type' \
    'typeattribute fuxicontrol_socket mlstrustedsubject' \
    'allow magisk fuxicontrol_socket unix_stream_socket *' \
    'allow system_server fuxicontrol_socket unix_stream_socket connectto' \
    'allow appdomain fuxicontrol_socket unix_stream_socket connectto'
if [ -f "$BASE/service.pid" ]; then
    pid=$(cat "$BASE/service.pid")
    if [ -r "/proc/$pid/cmdline" ] && tr '\000' ' ' <"/proc/$pid/cmdline" | grep -q 'dev.local.fuxicontrol.service.RootDaemon'; then
        exit 0
    fi
fi
rm -f "$BASE/service.ready"
CLASSPATH=$BASE/controller.apk app_process /system/bin dev.local.fuxicontrol.service.RootDaemon "$BASE" >"$BASE/service.log" 2>&1 </dev/null &
service_pid=$!
echo "$service_pid" >"$BASE/service.pid"
attempt=0
while [ ! -f "$BASE/service.ready" ] || [ "$(cat "$BASE/service.ready")" != "$service_pid" ]; do
    if ! kill -0 "$service_pid"; then cat "$BASE/service.log"; exit 1; fi
    attempt=$((attempt+1))
    if [ "$attempt" -ge 60 ]; then echo 'Controller startup timed out' >&2; exit 1; fi
    sleep 0.25
done
