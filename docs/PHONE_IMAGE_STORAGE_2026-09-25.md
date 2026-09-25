# Phone image storage defaults — 2026-09-25

## Default behavior

Ordinary survey/shutter actions no longer create an extra downlink JPEG/JSON archive on the phone
by default. These were video-downlink copies, not automatic downloads of aircraft SD originals.
The aircraft photo request, photo acknowledgements, route progress and telemetry/text logs are unchanged.

| Client | Default local image archive | Explicit collection |
| --- | --- | --- |
| Android V4 | Off (`SAVE_TRIGGER_FRAMES_TO_PHONE=false`) | An active, accepting cloud upload session requests frames directly into its upload queue; no extra Downloads archive |
| Android V5 | Off | “Save extra downlink frames to phone” explicitly enables an extra copy; otherwise an open upload session only prepares/queues the image |
| iOS | Off (`OPENFLY_SAVE_TRIGGER_FRAMES` is absent) | The live-upload switch must be enabled in an accepting session; temporary image/metadata files are removed after queue intake or rejection |

The private V5 app already had the default-off switch; the public V5 client now matches it.
The V4 and iOS changes apply to their public and private source builds. Debug builds do not silently
re-enable these survey image archives. V5 remembers an explicit user opt-in rather than resetting it.

## Uploads and explicit exports

- Enabling cloud collection still needs local retry storage. Turning off the extra archive does not
  discard a pending upload or make upload storage “zero bytes.”
- iOS creates only temporary source files for live collection, copies accepted data into the bounded
  upload queue, and removes the source files on success, rejection or failure. Queued bytes survive
  network failures and are deleted after upload acknowledgement. No duplicate session-folder image
  remains by default. A frame rejected before intake is not promised to have an archived backup.
- V4 uses the same session-admission checks as its uploader before requesting a downlink frame.
  Merely browsing a remote point cloud or mission does not enable local capture.
- Manual screenshot/export, explicitly selected historical photos and enabled HIL capture remain
  intentional actions; this change does not remove those workflows or alter their source files.
- Existing photographs and diagnostic archives are not deleted. Remove unwanted old copies manually.

## 中文摘要

三端普通拍照不再默认往手机额外保存图传图片；飞机 SD 卡拍照和文字 / 遥测日志不变。
主动开启云端采集仍会产生必要的待传缓存，成功上传后清理；它不是另一份永久相册。
V5 可手动开启“额外保存图传帧到手机”，默认关闭。旧图片不会自动删除，手动导出和 HIL 不受影响。

## Validation scope

Regression coverage checks the packaged default-off flags, the actual iOS runtime skipping frame
requests without opt-in, explicit upload frame requests without delaying photo completion, temporary
file cleanup and preservation of queued retry data. This is local unit/simulator/build validation,
not a new aircraft flight acceptance. No aircraft was operated and no phone was overwritten.

## Build and regression results

| Source | Tests | Release build |
| --- | --- | --- |
| Public Android V4 | Debug and Release: 306 passed, 1 optional test skipped each | Passed; signed route APK build 5 |
| Public Android V5 | Debug and Release: 546 passed, 2 optional tests skipped each | Passed; signed route APK build 3 |
| Public iOS | Debug: 193 passed, 2 optional tests skipped | arm64 build 20260925 passed |
| Private Android V4 | Debug and Release: 309 passed each | Passed |
| Private iOS | Full Debug run: 268 passed, 1 existing HIL test failed initially; isolated retry passed | arm64 build 20260925 passed |

The private iOS retry was `testSimulatorStopCallback8012RemainsRetryableAndKeepsHILNetwork`;
no HIL control code or timeout was changed to make it pass. The new storage/cleanup and runtime
opt-in tests passed in both iOS projects. Skip counts are not counted as passes.

The Android signed route packages use the existing official signing key, are non-debuggable,
pass APK v2 signature verification and zipalign, and contain no bundled MNN/model artifacts.
Application keys are injected privately during the build, not committed to the public repositories.
iOS Release was compiled with `CODE_SIGNING_ALLOWED=NO`: this is not a signed/distributable IPA
or an App Store Connect upload. Existing phone installations are unchanged until upgraded.
