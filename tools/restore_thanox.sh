#!/system/bin/sh
# Run after deployment to restore the preserved pre-handover callback and Thanox.
set -eu
BASE=/data/adb/fuxicontrol
test "$(id -u)" = 0
test -f "$BASE/handover/callback.before.sh"
sh "$BASE/recover.sh"
cp "$BASE/handover/callback.before.sh" /data/adb/modules/zygisk-assistant/callback.sh.new
chmod 0700 /data/adb/modules/zygisk-assistant/callback.sh.new
mv /data/adb/modules/zygisk-assistant/callback.sh.new /data/adb/modules/zygisk-assistant/callback.sh
pm enable --user 0 github.tornaco.android.thanos
CLASSPATH=/data/adb/modules/zygisk_vector/daemon.apk app_process /system/bin org.matrix.vector.daemon.Cli modules enable github.tornaco.android.thanos
echo 'Thanox restored. Reboot to switch system hooks.'
