#!/system/bin/sh

if [ "$(getprop init.svc.zygote)" != "running" ]; then
    setprop ctl.start zygote || exit 10
    sleep 1
fi

pm install --user 0 --abi arm64-v8a -r /sdcard/a.apk || exit 20
sh /sdcard/s
