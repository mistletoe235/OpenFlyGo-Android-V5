#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="${MODE:-offline}"
HZ="${HZ:-100}"
SERIAL="${ANDROID_SERIAL:-emulator-5554}"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
PACKAGE="com.openfly.go.v5"
ACTIVITY="$PACKAGE/edu.playground.djivln.NextMainActivity"

case "$MODE" in
  offline)
    ACTION="$PACKAGE.action.HIL_OFFLINE_REGRESSION"
    RESULT_PATTERN="HIL_OFFLINE RESULT="
    TIMEOUT_SECONDS=25
    ;;
  raw-source)
    ACTION="$PACKAGE.action.HIL_RAW_SOURCE_REGRESSION"
    RESULT_PATTERN="HIL_POSE RESULT="
    TIMEOUT_SECONDS=35
    ;;
  raw-rebind)
    ACTION="$PACKAGE.action.HIL_RAW_REBIND_REGRESSION"
    RESULT_PATTERN="HIL_POSE RESULT="
    TIMEOUT_SECONDS=35
    ;;
  *)
    echo "MODE must be offline, raw-source, or raw-rebind" >&2
    exit 2
    ;;
esac

"$ROOT/gradlew" -p "$ROOT" :app:testDebugUnitTest :app:assembleDebug >/dev/null
adb -s "$SERIAL" install -r "$APK" >/dev/null
adb -s "$SERIAL" logcat -c
adb -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP || true
adb -s "$SERIAL" shell wm dismiss-keyguard || true
adb -s "$SERIAL" shell am force-stop "$PACKAGE"
adb -s "$SERIAL" shell am start -n "$ACTIVITY" -a "$ACTION" --ei hz "$HZ" >/dev/null

REPORT_DIR="$ROOT/build/reports/hil"
mkdir -p "$REPORT_DIR"
REPORT_FILE="$REPORT_DIR/${MODE}-$(date +%Y%m%d-%H%M%S).log"

for ((second = 0; second < TIMEOUT_SECONDS; second += 1)); do
  result="$(adb -s "$SERIAL" logcat -d -v brief -s DjiVln:I | grep "$RESULT_PATTERN" | tail -1 || true)"
  if [[ -n "$result" ]]; then
    echo "$result"
    adb -s "$SERIAL" logcat -d -v time -s DjiVln:I \
      | grep -E 'HIL_OFFLINE|HIL_POSE' > "$REPORT_FILE" || true
    echo "report=$REPORT_FILE"
    [[ "$result" == *"RESULT=PASS"* ]]
    exit
  fi
  sleep 1
done

adb -s "$SERIAL" logcat -d -v brief -s DjiVln:I | grep -E 'HIL_OFFLINE|HIL_POSE' | tail -80
exit 1
