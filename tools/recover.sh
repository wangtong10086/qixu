#!/system/bin/sh
set -eu
BASE=/data/adb/fuxicontrol
test "$(id -u)" = 0
chmod 0600 /data/adb/service.d/95-fuxicontrol.sh
sh "$BASE/stop.sh"
CLASSPATH=$BASE/controller.apk app_process /system/bin dev.local.fuxicontrol.service.Recovery
CLASSPATH=/data/adb/modules/zygisk_vector/daemon.apk app_process /system/bin org.matrix.vector.daemon.Cli modules disable dev.local.fuxicontrol
echo 'Recovery completed. Reboot to unload the system hooks.'
