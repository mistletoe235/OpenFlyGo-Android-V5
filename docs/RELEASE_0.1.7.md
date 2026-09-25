# Android V5 0.1.7 release — 2026-09-25

Signed survey-only Release APK: `com.openfly.go.v5`, version `0.1.7-v5`, versionCode `9`.
No MNN/model inference or terrain-following workflow is included in this installer.

## Changes since the previous GitHub preview

- Restore Baidu map classes in the optimized Release build and display map initialization failures.
- Treat unverified camera geometry as an advisory rather than rejecting an otherwise executable route; retain known incompatible-camera checks.
- Accept cloud mission schemas through 14; retain cloud result viewing and mission import workflows.
- Disable extra phone-side downlink image archives by default without disabling aircraft SD-card photos or required upload retry storage.
- Separate moving-strip photography from stopped point captures. Moving strips no longer require near-zero speed or start a stopped-pose timeout while flying to the first point.
- Require fresh, aligned heading within 3 degrees and verified gimbal pose continuously for 800 ms before moving-strip photos; check subsequent interval photos too. This does not require stopping the aircraft.
- Synchronize successful pause acknowledgements with app state, coalesce duplicate requests and preserve mission state on rejected pause operations.
- Reject malformed/empty PLY input and improve capture/pause diagnostic events.

## Verification and limits

Release unit tests: 576 passed, 2 skipped, 0 failed. Related private-source tests: 15 passed.
The signed APK was installed and launch-checked on Xiaomi 12S. This is not validation of every
SDK-supported aircraft or completion of a new real-flight acceptance run. Rehearse the mission,
capture and pause/resume flow in the DJI simulator before real use; keep the pilot ready to take over.

The released APK is built from this public source with privately injected application/map keys and
the maintainer signing certificate. To select the release version in your own build:

```sh
./gradlew :app:testReleaseUnitTest :app:assembleRelease -POPENFLY_VERSION_CODE=9 -POPENFLY_VERSION_NAME=0.1.7-v5
```

Source builds require your own SDK keys and signing configuration; they are not expected to be
byte-identical to the maintainer APK. Do not uninstall or clear app data to bypass an update error.
