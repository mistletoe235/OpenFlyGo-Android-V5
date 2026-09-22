# V5 control and telemetry parity — September 21, 2026

[English](PUBLIC_PARITY_2026-09-21.md) · [Chinese reference](PUBLIC_PARITY_2026-09-21.zh-CN.md)

## Scope

This change affected only the V5 source-release client. Survey logic, camera
profiles, uploads and existing-session browsing retained their behavior. Private
MNN/VLN, radar planning, RC2 experiments, low-level OSD sampling and diagnostic
recording modules were not copied.

At review time, 55 survey-core files, five camera-capture files and
`SurveyFeatureController` matched the private client. The service endpoint remains
configured through `BuildConfig.V86_DEFAULT_ENDPOINT`, not a copied private URL.

## Changes

1. **Control acquisition:** requesting advanced mode no longer counts as success. The current acquisition must receive an SDK callback confirming advanced mode, while also confirming lease, app authority and enabled state. Timeout rollback, RC takeover and release safeguards remain.
2. **Command sending:** the shared `VirtualStickSendPolicy` checks lease, app authority, advanced mode and finite values on all four axes. Pre-release zero-velocity commands use the same checks; failure to send zero does not prevent requesting release.
3. **Rebind cleanup:** old velocity, all gimbal attitude values and timestamps are cleared, and unsupported-field collection restarts. Old listener values do not remain valid; unrelated business state is not reset.
4. **Velocity freshness:** SDK cached queries supply display values only before a velocity callback arrives. They neither refresh timestamps nor overwrite received callback values, including explicit invalid/null callbacks. A velocity callback updates velocity and flight-state timestamps with the same receipt time. No velocity integration or radar module was added.

`DjiTelemetrySnapshotPolicy` is independently testable and wired into the actual
DJI source, not an unused test helper. The control policy and six tests match the
private client.

## Recorded regression

- All 26 targeted tests passed: six send-policy, seven telemetry-snapshot, nine control-lifecycle and four lease tests.
- Full Debug: 537 cases, 536 passed, one skipped, zero failed. Targeted tests are included, not added again.
- Full Release: the same 537 cases, 536 passed, one skipped, zero failed.
- Debug APK, R8-optimized Release APK and Release `lintVital` passed. Release output remained unsigned at `app/build/outputs/apk/release/app-release-unsigned.apk`.
- Both APKs retained DJI native libraries. No MNN/indoor-planning native libraries or MNN/ONNX/GGUF/TFLite models were found. No private module dependency was added; this file inspection was not a complete supply-chain audit.
- The skipped case requires a supplied phone-mission fixture; a failing case was not converted to a skip.
- New cases cover requested-but-unreported advanced mode, later invalidation, lost authority, non-finite axes, unchanged small/zero velocities, rebind cleanup, cache timestamp/value preservation and null invalidation.

With JDK and Android SDK configured:

```sh
./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest \
  :app:assembleDebug :app:assembleRelease \
  -Pkotlin.incremental=false -Pkapt.incremental.apt=false
```

Incremental Kotlin/KAPT caches were disabled for this run to avoid an existing
Java/Kotlin stub-cache issue. Repository defaults were not changed and compilation/
tests were not skipped. Local logs were `/tmp/v5-public-parity-targeted-20260921.log`
and `/tmp/v5-public-parity-full-20260921.log`; JUnit XML is under
`app/build/test-results/testDebugUnitTest/` and `app/build/test-results/testReleaseUnitTest/`.
Existing SDK R8 stack-map/resource and Gradle/JDK deprecation warnings remained.

## Distribution and hardware scope

These are JVM logic tests, not acceptance of real DJI callback timing or flight
behavior. The run did not connect an aircraft, execute a mission or replace a
private phone installation. Registration, control acquisition/release, reconnect
telemetry and missions still require hardware acceptance. At the time of this
record, the change had not been committed or pushed. The source is now hosted in
[OpenFlyGo-Android-V5](https://github.com/mistletoe235/OpenFlyGo-Android-V5) as a private
repository. Distribution still requires matching DJI/map keys and signing; source
synchronization is not publication of an installation package.
