#!/system/bin/sh
# Keep package guard refreshes; Fuxi Control now owns delayed process reclaim.
MODPATH=${0%/*}
case "$1" in
    boot|packages)
        CLASSPATH="$MODPATH/bin/guard.jar" app_process /system/bin dev.local.fuxicompat.GuardRefresh refresh "$MODPATH"
        ;;
    reclaim) exit 0 ;;
    *) exit 2 ;;
esac
