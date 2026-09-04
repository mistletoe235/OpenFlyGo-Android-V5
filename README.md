# OpenFly Go for Android — MSDK V5

OpenFly Go is an open-source mobile ground application for low-cost DJI aircraft. This repository
contains the Android client based on DJI Mobile SDK V5, with live camera operation, map-based survey
planning, WPMZ/KMZ mission support, simulator/HIL integration and reconstruction-service handoff.

The current public release intentionally excludes VLN, on-device model inference, USB/LAN inference
transports, model distribution and native inference runtimes. DJI RC2 compatibility experiments and
non-public DJI simulator hooks are also excluded.

## Verified hardware

| Client | SDK | Physically verified aircraft |
| --- | --- | --- |
| Android V5 | DJI MSDK 5.18.0 | DJI Mini 4 Pro |

“Verified” means that connection, telemetry, live video, camera operations and the OpenFly survey
workflow have been exercised on real hardware. Other products supported by DJI MSDK V5 are not
claimed as OpenFly-verified until they pass the same acceptance process.

## Features

- DJI connection, account state, telemetry, battery, signal, camera and gimbal status;
- live camera preview, photo/video controls and aircraft-media browser;
- Baidu map integration with aircraft, remote-controller/device and home-point presentation;
- polygon survey planning, route ordering, camera-aware spacing and time estimation;
- custom survey execution plus DJI WPMZ/KMZ generation where supported;
- checkpoints, safe pause/resume, external-intervention handling and DJI RTH handoff;
- UE/AirSim HIL transport and the public DJI Simulator API;
- trigger-aligned frame/pose metadata and V86 image-stream/reconstruction client;
- optional DSM/building-height planning retained as experimental functionality.

Terrain following is **off by default**. It requires explicit user activation, valid surface data and
a new safety review. Surface data can be incomplete or stale and never replaces obstacle sensing or
site inspection.

## Requirements

- Android Studio with the Android SDK/NDK versions declared by the project;
- JDK 17 or newer supported by the included Gradle toolchain;
- an Android device with `arm64-v8a`;
- a DJI Developer account and an MSDK V5 App Key;
- a Baidu Maps Android key for the same application ID and signing-certificate SHA1.

## Configuration

```bash
cp local.properties.example local.properties
```

Set the following values only in the untracked `local.properties` file:

```properties
sdk.dir=/path/to/Android/sdk
OPENFLY_APPLICATION_ID_V5=com.example.openflygo.v5
AIRCRAFT_API_KEY_V5=your_dji_app_key
BAIDU_MAP_AK_V5=your_baidu_map_key
V86_DEFAULT_ENDPOINT=http://127.0.0.1:55000
```

The DJI App Key must match `OPENFLY_APPLICATION_ID_V5`. The Baidu key must match both that package
and the certificate used to sign the APK. Never commit real keys, tokens, keystores or passwords.

## Build and test

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. For release builds, copy
`release-signing.properties.example` to `release-signing.properties` and use your own keystore.
Official OpenFly Go APKs are signed by the maintainers in a private build environment.

## Architecture

```text
DJI MSDK V5 ── telemetry / camera / gimbal / control / WPMZ
       │
       ├── flight HUD and map
       ├── survey planner and safety policies
       ├── public Simulator / UE HIL bridge
       └── capture metadata ── V86 reconstruction service
```

The `uxsdk/` module is derived from DJI's public MSDK V5 Sample Code and retains its original
copyright/license headers.

## Safety

This is research software, not a replacement for the remote pilot, DJI flight-safety systems,
airspace authorization, site inspection or legal compliance. Keep visual line of sight, maintain a
manual takeover path and validate changes in simulation before any real flight. Unsupported or stale
telemetry must fail closed.

Useful documentation:

- [Survey execution interface](docs/SURVEY_EXECUTION_INTERFACE.md)
- [V4/V5 HIL parity and protocol](docs/V4_V5_HIL_PARITY.md)
- [V86 reconstruction client](docs/V86_ANDROID_STREAMING_CLIENT.md)
- [WPMZ survey policy](docs/WPMZ_SURVEY_POLICY_2026-08-23.md)

## Contributing and license

See [CONTRIBUTING.md](CONTRIBUTING.md) and [SECURITY.md](SECURITY.md) before submitting flight-control
changes. OpenFly Go is licensed under the [Apache License 2.0](LICENSE). Third-party components retain
their own terms; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
