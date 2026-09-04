#!/bin/zsh
set -euo pipefail

ROOT="${0:A:h:h}"
SERIAL="${1:?usage: $0 <adb-serial> [hz]}"
HZ="${2:-50}"
APK="$ROOT/app/build/outputs/apk/hil-rate-experiment/OpenFlyGo-v5-hil-rate-${HZ}-debug.apk"
PACKAGE="com.openfly.go.v5"

if [[ ! -f "$APK" ]]; then
  echo "missing APK: $APK" >&2
  exit 2
fi

adb -s "$SERIAL" get-state >/dev/null
set +e
adb -s "$SERIAL" install --incremental -r -d "$APK"
INCREMENTAL_STATUS=$?
set -e
if [[ $INCREMENTAL_STATUS -eq 0 ]] && adb -s "$SERIAL" shell pm path "$PACKAGE" | grep -q '^package:'; then
  echo "incremental install verified"
  exit 0
fi

echo "incremental install was not committed; falling back to full streamed install" >&2
adb -s "$SERIAL" install --no-incremental -r -d "$APK"
adb -s "$SERIAL" shell pm path "$PACKAGE" | grep -q '^package:'
echo "full install verified"
