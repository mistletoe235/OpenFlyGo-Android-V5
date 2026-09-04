#!/usr/bin/env bash
set -euo pipefail

SERIAL="${1:-}"
PACKAGE="com.openfly.go.v5"
ACTIVITY="$PACKAGE/edu.playground.djivln.NextMainActivity"
APK="${2:-app/build/outputs/apk/debug/app-debug.apk}"

if [[ -z "$SERIAL" ]]; then
  echo "usage: $0 <adb-serial> [apk]" >&2
  exit 2
fi

adb -s "$SERIAL" get-state >/dev/null

# HyperOS can block the adb install shell transport while its package installer
# is active. Push first, then detach pm install on the device so ADB remains
# available for diagnostics and UI automation.
REMOTE_APK="/data/local/tmp/openfly-go-v5-smoke.apk"
REMOTE_INSTALL_LOG="/data/local/tmp/openfly-go-v5-install.log"
adb -s "$SERIAL" push "$APK" "$REMOTE_APK" >/dev/null
adb -s "$SERIAL" shell "rm -f '$REMOTE_INSTALL_LOG'; setsid sh -c 'exec pm install -r -g -t \"$REMOTE_APK\" >\"$REMOTE_INSTALL_LOG\" 2>&1 </dev/null' >/dev/null 2>&1 &"

INSTALL_RESULT=""
for _ in $(seq 1 120); do
  INSTALL_RESULT="$(adb -s "$SERIAL" shell "cat '$REMOTE_INSTALL_LOG' 2>/dev/null" | tr -d '\r')"
  if grep -q "Success" <<<"$INSTALL_RESULT"; then
    break
  fi
  if grep -q "Failure" <<<"$INSTALL_RESULT"; then
    echo "install failed: $INSTALL_RESULT" >&2
    adb -s "$SERIAL" shell "rm -f '$REMOTE_APK' '$REMOTE_INSTALL_LOG'" >/dev/null 2>&1 || true
    exit 1
  fi
  sleep 1
done

if ! grep -q "Success" <<<"$INSTALL_RESULT"; then
  echo "install timed out after 120 seconds" >&2
  exit 1
fi

adb -s "$SERIAL" shell "rm -f '$REMOTE_APK' '$REMOTE_INSTALL_LOG'" >/dev/null
# A first install can still surface the platform location dialog on some HyperOS
# builds even with `pm install -g`. Grant explicitly so the automated cold-start
# loop tests our activity rather than the permission controller.
adb -s "$SERIAL" shell pm grant "$PACKAGE" android.permission.ACCESS_FINE_LOCATION >/dev/null 2>&1 || true
adb -s "$SERIAL" shell pm grant "$PACKAGE" android.permission.ACCESS_COARSE_LOCATION >/dev/null 2>&1 || true
# A permission activity requested by the pre-update process can remain above the
# newly granted app on HyperOS. Dismiss that stale surface before cold starts.
if adb -s "$SERIAL" shell dumpsys activity activities | grep -q "GrantPermissionsActivity"; then
  adb -s "$SERIAL" shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
fi
adb -s "$SERIAL" logcat -c

for _ in 1 2 3 4 5; do
  adb -s "$SERIAL" shell am force-stop "$PACKAGE"
  adb -s "$SERIAL" shell am start -W -n "$ACTIVITY" >/dev/null
  sleep 3
  adb -s "$SERIAL" shell pidof "$PACKAGE" >/dev/null
done

for _ in 1 2 3 4 5; do
  adb -s "$SERIAL" shell input keyevent KEYCODE_HOME
  sleep 1
  adb -s "$SERIAL" shell am start -W -n "$ACTIVITY" >/dev/null
  sleep 2
  adb -s "$SERIAL" shell pidof "$PACKAGE" >/dev/null
done

LOG="$(adb -s "$SERIAL" logcat -d -v brief)"
if grep -Eqi "FATAL EXCEPTION|ANR in $PACKAGE|Process: $PACKAGE.*has died" <<<"$LOG"; then
  echo "smoke test failed: crash or ANR found" >&2
  grep -Ei -C 5 "FATAL EXCEPTION|ANR in $PACKAGE|Process: $PACKAGE.*has died" <<<"$LOG" >&2
  exit 1
fi

echo "smoke test passed: 5 cold starts + 5 background/resume cycles"
