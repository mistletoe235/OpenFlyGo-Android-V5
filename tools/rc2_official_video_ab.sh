#!/system/bin/sh

LOG=/sdcard/s.log
exec >"$LOG" 2>&1

model="$(getprop ro.product.model)"
echo "RC2_OFFICIAL_AB_BEGIN $(date) model=$model"
if [ "$model" != "DJI RC 2" ]; then
  echo "ABORT_WRONG_MODEL"
  exit 23
fi

am force-stop com.openfly.go.v5
am force-stop com.openfly.rc2videobridge
am force-stop dji.go.v5
sleep 2

echo "START_SOCKET_CAPTURE"
(
  i=0
  while [ "$i" -lt 40 ]; do
    /system/bin/toybox nc -U /dev/socket/fpv_sock > "/sdcard/oa_$i.bin"
    echo "capture=$i bytes=$(wc -c < "/sdcard/oa_$i.bin")"
    i=$((i + 1))
  done
) > /sdcard/oa.log 2>&1 &
capture_pid=$!
echo "CAPTURE_PID=$capture_pid"
sleep 1

echo "START_DJI_FLY"
am start -W --user 0 -n dji.go.v5/com.dji.component.application.activity.DJIPureLaunchActivity
sleep 25

kill "$capture_pid" 2>/dev/null
echo "CAPTURE_COUNT=$(ls /sdcard/oa_*.bin 2>/dev/null | wc -l)"

echo "DJI_PID=$(pidof dji.go.v5)"
dumpsys activity activities | grep -m 1 'mResumedActivity'
echo "RC2_OFFICIAL_AB_END $(date)"
