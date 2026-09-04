#!/usr/bin/env bash
set -euo pipefail

serial="${1:-}"
shots="${2:-6}"
periods_ms="${3:-}"
low_resolution="${4:-true}"
native_interval="${5:-false}"

adb_cmd=(adb)
if [[ -n "$serial" ]]; then
  adb_cmd+=( -s "$serial" )
fi

component="com.openfly.go.v5/edu.playground.djivln.NextMainActivity"
action="com.openfly.go.v5.action.CAMERA_CADENCE_TEST"

"${adb_cmd[@]}" get-state >/dev/null
"${adb_cmd[@]}" shell am start -n "$component" >/dev/null
sleep 3
"${adb_cmd[@]}" logcat -c
start_args=(shell am start -n "$component" -a "$action" --ei shots "$shots")
start_args+=(--ez low_resolution "$low_resolution")
start_args+=(--ez native_interval "$native_interval")
if [[ -n "$periods_ms" ]]; then
  start_args+=(--es periods_ms "$periods_ms")
fi
"${adb_cmd[@]}" "${start_args[@]}" >/dev/null

echo "Camera cadence test started: minimum_request_gap=${periods_ms:-500}ms shots=$shots low_resolution=$low_resolution native_interval=$native_interval"
echo "Safety gate: aircraft and camera must be connected, aircraft grounded, not recording, and no survey/VLN control active."
echo "Metric: ready-gated single-shot callback/actual request cadence, or native interval media count when native_interval=true."

for _ in $(seq 1 90); do
  output="$("${adb_cmd[@]}" logcat -d -s DjiVln:I '*:S' | grep 'CAMERA_CADENCE' || true)"
  if grep -qE 'CAMERA_CADENCE (COMPLETE|RESULT=(BLOCKED|ABORTED))' <<<"$output"; then
    printf '%s\n' "$output"
    exit 0
  fi
  sleep 2
done

"${adb_cmd[@]}" logcat -d -s DjiVln:I '*:S' | grep 'CAMERA_CADENCE' || true
echo "Timed out waiting for test completion." >&2
exit 1
