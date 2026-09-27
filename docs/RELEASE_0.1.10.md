# Android V5 0.1.10 — 2026-09-27

Signed survey-only Release APK: `com.openfly.go.v5`, version `0.1.10-v5`, versionCode `12`.

## Changes / 更新

- Ordinary strips no longer require each unchanged telemetry field to update within one second. Heading/alignment and the 800 ms dwell remain; precise stopped captures retain their existing checks.
- New, unlocked Mini 3 / Mini 3 Pro tasks automatically select the existing Virtual Stick executor. Mini 4 Pro keeps KMZ by default; explicit VS/HIL choices are retained. Uploaded, running, paused and restored tasks are not silently converted.
- Show the actual execution backend and keep automatic selection separate from the saved user preference.
- Fix missing map-page capture feedback for Virtual Stick: KMZ and custom capture results now use the same top-level success/failure indicator without duplicate notifications.
- Synchronize Chinese/English FAQs, compatibility notes and public/private V5 survey changes. No MNN, private models or terrain-following workflow is packaged.
- 普通航带撤回过严的逐字段 1 秒更新要求；补齐 Mini 3 系列后端选择、VS 地图拍照提示及中英文说明。

## Verification / 验证

- Release tests: **602 passed, 2 skipped, 0 failed**; signed APK and ZIP alignment verified.
- The reported VS session recorded 30 successful capture callbacks and 30 camera shooting-edge events. This does not verify every SD-card file or validate the new overlay on a flying device.
- The 0.1.9 backend-selection build was launch-checked on Xiaomi 12S. **0.1.10 has not yet been installed/flight-checked** because the phone was actively controlling the simulator when publication began.
- Tests and simulator evidence are not real-flight acceptance. Mini 3 / Mini 3 Pro hardware acceptance remains pending.

## Known limitations / 已知限制

- VS does not yet apply the mission-selected 4:3 / 16:9 photo ratio automatically; verify the camera ratio before starting.
- Audit found a conditional risk of stale KMZ state affecting the shared checkpoint/export path after backend switching. It was not observed in the reported capture log; it is not fixed in this package. Avoid alternating KMZ and VS recovery for one task and keep exported backups.
- Experimental continuous reacquisition remains KMZ-only on V5. Ordinary strips and default stop-and-capture are separate workflows.
- M300/M350 device-specific breakpoint recovery is not adapted by this update. SDK support does not mean all camera, control and recovery functions are project-validated.
- VS missions require the app in foreground and the control link connected; no automatic fallback occurs after an ordinary upload error.

See [backend adaptation](V5_DEVICE_WAYLINE_ADAPTATION_PLAN_2026-09-27.md) and [VS feedback audit](V5_VS_CAPTURE_FEEDBACK_AUDIT_2026-09-27.md). Preserve app data when updating; installation does not start a mission.
