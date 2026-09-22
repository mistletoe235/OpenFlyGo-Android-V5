#!/usr/bin/env bash
set -euo pipefail

SERIAL="${1:-}"
PACKAGE="${2:-com.openfly.go.v5}"
ADB="${ADB:-adb}"
ACTIVITY="$PACKAGE/edu.playground.djivln.NextMainActivity"

if [[ ! "$SERIAL" =~ ^emulator-[0-9]+$ ]] || [[ ! "$PACKAGE" =~ ^[A-Za-z][A-Za-z0-9_.]+$ ]]; then
  echo "usage: $0 <emulator-serial> [installed-package]; physical devices are prohibited" >&2
  exit 2
fi
if [[ "$("$ADB" -s "$SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ]]; then
  echo "refusing to stop or start apps outside an Android emulator" >&2
  exit 2
fi

for iteration in 1 2 3; do
  "$ADB" -s "$SERIAL" shell am force-stop "$PACKAGE"
  "$ADB" -s "$SERIAL" shell am start -W -n "$ACTIVITY"
  sleep 5
  FIRST_PID="$("$ADB" -s "$SERIAL" shell pidof "$PACKAGE" | tr -d '\r')"
  test -n "$FIRST_PID"
  sleep 3
  SECOND_PID="$("$ADB" -s "$SERIAL" shell pidof "$PACKAGE" | tr -d '\r')"
  test "$FIRST_PID" = "$SECOND_PID"
  echo "cold start $iteration: process $FIRST_PID survived"
done

for iteration in 1 2 3; do
  FIRST_PID="$("$ADB" -s "$SERIAL" shell pidof "$PACKAGE" | tr -d '\r')"
  test -n "$FIRST_PID"
  "$ADB" -s "$SERIAL" shell input keyevent KEYCODE_HOME
  sleep 1
  "$ADB" -s "$SERIAL" shell am start -W -n "$ACTIVITY"
  sleep 3
  SECOND_PID="$("$ADB" -s "$SERIAL" shell pidof "$PACKAGE" | tr -d '\r')"
  test "$FIRST_PID" = "$SECOND_PID"
  echo "resume $iteration: process $FIRST_PID survived"
done

echo "Offline lifecycle passed. No aircraft, flight control, live video or server access was tested."
