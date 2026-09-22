# Basic-function regression, 2026-09-21

## Scope

This check covers the public V5 client, not the private development app. It runs without an
aircraft and does not take off, execute a route, or change aircraft settings.

## Findings and fixes

- Four recapture test files had been changed to private `handoff/` paths even though their
  fixtures were already tracked under public `testdata/active-recapture/`. Restored the public
  paths instead of skipping the eleven failing tests or adding private mission copies.
- Updated stale WPMZ assertions for explicit 2-second gimbal rotation and heading-change settling.
  Added checks around the 3-degree threshold and heading wraparound. Did not remove production
  settling safeguards to make the tests pass.
- Updated the instrumented EXIF test to provide and check camera yaw separately from aircraft
  heading. The production pipeline already resolves camera yaw before writing EXIF.
- Offline UI startup was binding the DJI video decoder without an aircraft. It could crash in
  the SDK EGL thread on the Android emulator. Preview binding now checks aircraft connection;
  disconnect releases the binding, reconnect rebinds, and camera/lens changes use the same path.
- `MapWidget` used a `LocationListener` lambda relying on newer Android default methods. On
  Android 10 a disabled provider caused `AbstractMethodError`. All legacy callbacks are now
  explicitly implemented, with the existing location-update behavior unchanged.

## Verification

- Full JVM suite: 515 cases, 514 passed, one optional phone-capture fixture skipped, no failures.
- Four instrumented cases passed on an isolated Android 10 arm64 emulator: native KMZ generation,
  a 1,022-waypoint split export, EXIF roundtrip, and relative-height image preprocessing.
- Final Debug and minified Release APK builds passed. Existing third-party R8/deprecation warnings
  remain; no warning suppression was added for this check.
- Fixed Debug APK survived three offline cold starts and three background/resume cycles. The
  activity returned to the foreground; no new crash was recorded for those processes. The check
  did not validate live video, aircraft reconnection or flight.

An incremental KAPT stub cache failed after the Java listener edit. The final build and full
tests were rerun with `-Pkotlin.incremental=false -Pkapt.incremental.apt=false`; no source or
dependency was removed to bypass compilation.

## Reproduce

Set `JAVA_HOME`, the Android SDK path and the documented local build configuration, then run:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
./gradlew :app:assembleDebugAndroidTest
bash scripts/check_offline_startup.sh emulator-5580
```

The lifecycle script expects the Debug APK to be installed and intentionally refuses physical
device serials. It checks process survival only and never clicks flight controls. Complete the
language/privacy setup separately if checking the planner UI. Instrumentation must select the
reviewed non-flight test classes, not indiscriminately run physical-flight or soak tests.

## Still required for deployment

The public defaults are templates, not an activated DJI build. Supply a valid DJI App Key matching
the application ID, a Baidu key matching the ID/signing certificate, and appropriate release signing.
The checked Release APK is unsigned. No production credentials were copied into the public repo.

Before calling this release flight-validated, check SDK registration, actual camera preview,
photo/video, aircraft disconnect/reconnect and route execution on the intended hardware. The cloud
protocol tests also require a real authenticated session for final end-to-end service acceptance.
