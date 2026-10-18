#!/system/bin/sh
# Start after framework services are ready. No Thanox or other module changes.
while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done
exec sh /data/adb/fuxicontrol/start.sh
