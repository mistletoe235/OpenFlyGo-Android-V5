# OpenFly Go for Android — MSDK V5

Part of [OpenFlyScan](https://github.com/mistletoe235/OpenFlyScan) ·
[Paper](https://arxiv.org/abs/2609.24253) ·
[Citation](https://github.com/mistletoe235/OpenFlyScan#citation)

> [!WARNING]
> **Flight safety — read before flying**
>
> This is a research and development tool, not a flight-safety guarantee. **Passing simulation does not establish real-flight safety. Automated routes and obstacle avoidance do not replace site inspection or pilot supervision.**
>
> - **Simulate before every flight:** rehearse the planned route, capture, pause/resume and completion in the App's built-in simulator. If simulation is unavailable or fails to start, do not substitute a real flight for validation.
> - **Use HIL where supported:** connect the real flight controller to UE to inspect simulated telemetry, heading, altitude and control responses. Remove propellers, secure the aircraft and verify that DJI Simulator is active; an HIL connection alone is not sufficient. See the [HIL guide](docs/HIL_QUICKSTART.md).
> - **Check clearance and signals:** inspect buildings, trees, wires, transit and return paths. Height relative to takeoff is not clearance above terrain or rooftops. Check positioning, control/video links, failsafe behavior and return-to-home settings.
> - **Enable available obstacle avoidance; do not use Sport/S mode.** Not all consumer aircraft have omnidirectional sensing. Verify active sensing directions and limitations, use the supported normal positioning mode and maintain safe clearance.
> - **Recheck before real flight:** exit simulation, restore the real camera and verify takeoff/home positions, route, battery and weather. Maintain pilot supervision and readiness to pause or take over; follow local flight rules.

> Quick links: [Client selection](#client-selection-and-aircraft-support) · [HIL](docs/HIL_QUICKSTART.md) · [Cloud workflow](docs/CLOUD_ROUTE_WORKFLOW.md)
>
> [Project home](https://github.com/mistletoe235/OpenFlyScan) · [English](README.md) · [Chinese reference](README.zh-CN.md)


Cloud point-cloud viewing and existing-session route import are included; see
`docs/CLOUD_ROUTE_WORKFLOW.md`. No model inference runtime is required.

See the [control/telemetry parity and regression record](docs/PUBLIC_PARITY_2026-09-21.md).

OpenFly Go is an open-source mobile ground application for low-cost DJI aircraft. This repository
contains the Android client based on DJI Mobile SDK V5, with live camera operation, map-based survey
planning, WPMZ/KMZ mission support, simulator/HIL integration and reconstruction-service handoff.

The current public release intentionally excludes VLN, on-device model inference, USB/LAN inference
transports, model distribution and native inference runtimes. DJI RC2 compatibility experiments and
non-public DJI simulator hooks are also excluded.

Terrain following is disabled in this public build: its UI is unavailable, and missions with `terrainPlan` cannot be loaded, executed or exported to DJI KMZ.
Maintainers can build survey installation packages using private keys and release signing. These are not the private development builds containing model runtimes.

## Download and install

- [Signed Android APK](https://github.com/mistletoe235/OpenFlyGo-Android-V5/releases/download/v0.1.1-v5/OpenFlyGo-Android-V5-0.1.1.apk)
- [App release and checksums](https://github.com/mistletoe235/OpenFlyGo-Android-V5/releases/tag/v0.1.1-v5)
- [Identical APK in the main project release](https://github.com/mistletoe235/OpenFlyScan/releases/tag/preview-20260922)

Version `0.1.1-v5`, versionCode `3`; arm64 Android 7.0 or later. This is the
survey/capture source-release client, not the private model-inference build.
The Android preview APK is publicly downloadable from Releases.

Download the APK and allow installation from your browser/file manager if Android
prompts. Select this SDK line for compatible aircraft; `Mini 4 Pro` is the project
reference, not a guarantee for every SDK-listed model. Preserve missions when
updating and do not uninstall/clear data to bypass a signature conflict or downgrade.
Use the included checksums and notices. Maintainer packages are signed with the
project certificate; source builds still require your own keys and signing.
Configure a phone-reachable workstation URL and access code for cloud features.
Read the flight-safety warning above before any aircraft use; installation neither
starts a mission nor establishes flight readiness.

## Client selection and aircraft support

Reviewed September 21, 2026. **V4 and V5 are different DJI SDK product lines,
not older and newer versions of one app. Mini 2 cannot gain V5 features by
installing the V5 client.** Check the exact build as well as the platform.

| Client | Pinned SDK | Project reference aircraft | Map | Mission schemas | Cloud uploads | Cloud results |
| --- | --- | --- | --- | --- | --- | --- |
| Android V4 | MSDK 4.16.4 | **DJI Mini 2** | Baidu Maps | 1–14 | Trigger frames and historical photos | Supported |
| Android V5 | MSDK 5.18.0 | **DJI Mini 4 Pro** | Baidu Maps | 1–14 | Trigger frames and historical photos | Supported |
| iOS | MSDK 4.16.2 | **DJI Mini 2** | MapKit | 1–14 | Trigger frames and historical photos; updated build required | Supported |

Reference aircraft have project hardware-use records; this does not mean every
release has repeated all flight acceptance tests. SDK connectivity and a camera
profile entry do not establish verified camera, gimbal, control, survey or
simulator support for a particular aircraft.

### Official DJI support references

- [MSDK supported products and platforms](https://developer.dji.com/mobile-sdk/)
- [MSDK V5 supported products](https://github.com/dji-sdk/Mobile-SDK-Android-V5#what-is-dji-mobile-sdk-v5)
- [MSDK V4 product support](https://developer.dji.com/mobile-sdk/documentation/introduction/product_introduction.html#supported-products)
- [Android V4 4.16.4 release](https://github.com/dji-sdk/Mobile-SDK-Android/tree/V4.16.4)
- [iOS V4 4.16.2 release](https://github.com/dji-sdk/Mobile-SDK-iOS/tree/v4.16.2)

Official pages change, and older V4 pages may omit later additions. Check the
pinned SDK, Android/iOS platform, aircraft firmware and remote controller together.

| Aircraft / product family | Client selection | Project support boundary |
| --- | --- | --- |
| Mini 2 | Android V4 or iOS | Project hardware-use record; preflight is still required for the installed firmware |
| Mini 4 Pro | Android V5 | Project hardware-use record; not supported by this iOS client |
| Mini 3 / Mini 3 Pro | Listed by DJI for V5; use Android V5 for compatibility testing | Full project workflow not yet hardware-validated |
| Mavic 3 Enterprise, Mavic 3TA, Matrice 30 / 300 RTK / 350 RTK / 400, Matrice 4 / 4D Enterprise | Check the V5 product list and firmware requirements | Enterprise payloads, multiple cameras and all survey functions are not guaranteed; Mavic 3 Enterprise is not the consumer Mavic 3 |
| Mavic Pro / Mavic Air, Mavic 2 Pro / Zoom / Enterprise, Spark, Phantom, Inspire, earlier Matrice products | Check the exact V4 model and platform | SDK candidates, not individually accepted by this project |
| Mavic Mini, Mini SE, Mavic Air 2, Air 2S and other V4 products | Check the matching Android/iOS SDK release | Android support does not imply iOS support; not listed as project-validated aircraft |
| Avata / Avata 2, Neo / Neo 2 and other unadapted products | Outside project support | No bypass integration; installing an app or receiving video does not establish control support |

Use an SDK-supported remote controller with a USB data connection to the phone.
The V5 source excludes RC2 bypass/video compatibility experiments. Installing an
APK on a controller is not sufficient. Listed gimbals or payloads such as H30
are not separate aircraft models.

### Platform and build differences

- All three clients provide survey planning, preview, preflight, pause/resume and HIL; hardware APIs remain model-dependent.
- V5 supports DJI WPMZ/KMZ execution. V4/iOS Mini 2 missions use app-side control: **keep the app in the foreground and connected**, rather than treating them as offline onboard missions.
- Default reacquisition uses stable stop-and-capture points (schema 13). All three clients support experimental schema 14: V4/iOS use Virtual Stick and V5 uses DJI KMZ. Only eligible intermediate capture points pass continuously; boundaries and turns may still stop. V4/iOS require the September 22, 2026 adaptation or a later compatible build. This is not merely relaxed version parsing or a new real-flight acceptance claim.
- The source excludes MNN, VLN, model downloads and private inference runtimes. Cloud routes and point clouds do not depend on them.
- Android retains experimental terrain following, disabled by default. iOS Release rejects missions with `terrainPlan`.
- Debug is for development; Release is a build configuration, not an all-aircraft acceptance label. Supply your own keys/signing for local builds. Maintainer installation packages use private signing; different signatures cannot overwrite one another. Do not erase app data merely to switch packages.

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

## Basic workflow: connect, survey, reconstruct and reacquire

1. **Connect:** attach the phone to the controller with a USB data cable and grant the required permissions and map consent. Close other DJI apps competing for USB. Verify SDK registration, aircraft/camera identity, fresh telemetry and video. For a black screen or registration failure, check the cable, package ID, keys, signing certificate and controller compatibility.
2. **Plan:** open the area survey planner from the map. Add at least three polygon vertices in order and drag to adjust. Match the camera, lens and photo aspect ratio; select nadir or oblique capture directions.
3. **Configure:** set altitude or GSD, speed, forward/side overlap, gimbal angle, start point and completion/RTH behavior. Start with a short, small-area route. Distinguish takeoff-relative height, target-surface height and ASL. Missions store WGS84 coordinates; do not manually add map offsets.
4. **Preview and execute:** save the mission and inspect its full path, photo count, duration and entry/exit legs. Resolve GPS, home-point, camera, control-authority and height preflight blocks. Follow the selected backend's preparation/execution steps. Importing or generating a mission never takes off automatically. Validate in [HIL](docs/HIL_QUICKSTART.md) before pilot-supervised real use.
5. **Pause and resume:** investigate manual takeover, disconnection or safety-gate events before explicitly resuming. Resume may first return to the checkpoint. Do not replace an executing or paused mission. RTH is a safety action, not permission granted by cloud processing.
6. **Upload:** before capture, open the local capture/upload section of cloud reconstruction. Set the workstation URL, access code and actual takeoff ASL, then create a session. Monitor captured, uploaded, pending and rejected/not-queued counts. Trigger-frame uploads are neither continuous video streaming nor automatic transfer of SD-card originals.
7. **Review and reacquire:** finish capture after the queue drains, wait for processing, view the PLY and download the proposed mission. Recheck the takeoff datum, coordinates, altitude, camera, review status and clearance before preflight and explicit execution. The existing-session browser can inspect another device's results without taking over its upload queue.

See the [HIL guide](docs/HIL_QUICKSTART.md) and
[cloud workflow](docs/CLOUD_ROUTE_WORKFLOW.md) for detailed steps.
Workstation installation, GPU/model dependencies and service startup are documented
in the [OpenFlyScan workstation guide](https://github.com/mistletoe235/OpenFlyScan/blob/main/docs/workstation.md).
This repository documents the app side rather than duplicating server setup.

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
V86_DEFAULT_ENDPOINT=https://reconstruction.example.com
```

The DJI App Key must match `OPENFLY_APPLICATION_ID_V5`. The Baidu key must match both that package
and the certificate used to sign the APK. Never commit real keys, tokens, keystores or passwords.

Use the workstation HTTPS root URL reachable from the phone. On the phone,
`127.0.0.1` refers to the phone itself, not your computer. Do not enter an SSH
address or append `/api/sessions/...` to the service root.

## Build and test

See `docs/BASIC_REGRESSION_2026-09-21.md` for the latest local verification scope, startup fixes,
and the distinction between offline checks and real-aircraft acceptance.

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. For release builds, copy
`release-signing.properties.example` to `release-signing.properties` and use your own keystore.
Official OpenFly Go APKs are signed by the maintainers in a private build environment.

```bash
cp release-signing.properties.example release-signing.properties
```

Configure your own keystore, then run:

```bash
./gradlew testReleaseUnitTest assembleRelease
```

Maintainers can set `OPENFLY_RELEASE_SIGNING_FILE` to a signing configuration outside the repository; no credentials need to be copied into source Git.
Override the version with `-POPENFLY_VERSION_CODE=2 -POPENFLY_VERSION_NAME=0.1.0-v5`,
incrementing it relative to the last distributed build. DJI/map authorization and
the service endpoint still use your private configuration.

With signing configured, the output is normally `app/build/outputs/apk/release/app-release.apk`.
An unsigned `app-release-unsigned.apk` cannot be installed directly. Verify DJI/map
authorization for the release package and certificate, and back up your signing key.
Debug authorization does not cover a different Release certificate. App-store
eligibility must be checked separately for the target platform.


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

- [HIL setup and troubleshooting](docs/HIL_QUICKSTART.md)
- [Cloud uploads, point clouds and reacquisition](docs/CLOUD_ROUTE_WORKFLOW.md)
- [Camera profile compatibility](docs/CAMERA_PROFILE_COMPATIBILITY.md)
- [Survey execution interface](docs/SURVEY_EXECUTION_INTERFACE.md)
- [V4/V5 HIL parity and protocol](docs/V4_V5_HIL_PARITY.md)

Archived developer notes (Chinese; retained separately from the current guides):

- [Earlier V86 streaming client](docs/V86_ANDROID_STREAMING_CLIENT.md)
- [WPMZ survey policy](docs/WPMZ_SURVEY_POLICY_2026-08-23.md)

## Contributing and license

See [CONTRIBUTING.md](CONTRIBUTING.md) and [SECURITY.md](SECURITY.md) before submitting flight-control
changes. OpenFly Go is licensed under the [Apache License 2.0](LICENSE). Third-party components retain
their own terms; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Camera profile compatibility

Read the [camera compatibility guide](docs/CAMERA_PROFILE_COMPATIBILITY.md) before
changing aircraft, lens or photo mode. SDK connectivity does not verify the camera
profile; unconfirmed geometry must not authorize mission execution.

## License and third-party software

Original OpenFly Go code uses [Apache-2.0](LICENSE). DJI SDK binaries, map
services and other dependencies retain their own terms. See
[third-party notices](THIRD_PARTY_NOTICES.md) and the retained files in `LICENSES/`;
include the applicable notices when distributing an installation package.
