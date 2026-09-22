# Camera profile compatibility

[English](CAMERA_PROFILE_COMPATIBILITY.md) · [Chinese reference](CAMERA_PROFILE_COMPATIBILITY.zh-CN.md)

Updated September 21, 2026. This guide applies to installation packages and the
corresponding survey source builds. It does not claim flight acceptance for every
DJI-supported aircraft.

## Automatic matching

- Matching uses explicit aircraft, camera and lens aliases, not substring guesses. For example, M300 RTK must not match M30.
- An enterprise family name alone cannot confirm geometry: the specific camera and the catalogued wide-angle lens must be identified (RGB for M3M where applicable). Unknown payloads, conflicting identities, thermal, telephoto and multispectral lenses cannot inherit a wide-angle profile.
- Profiles contain photo dimensions, field of view and capture intervals. Horizontal/vertical FOV is derived from published diagonal FOV, not SDK-calibrated intrinsics or per-aircraft calibration.
- Mavic 2 Zoom's variable focal length is unverified and no longer authorizes execution as a fixed wide-angle camera.

## Before and during execution

- Readback checks photo aspect ratio, selectable resolution and zoom. V5 also checks landscape orientation for rotatable Mini cameras. V4/iOS use catalog dimensions for fixed-resolution cameras and distinguish normal/high-resolution Air 2 modes.
- Parameter readback is requested at most once per second. Values older than two seconds cannot confirm geometry. Disconnecting or changing cameras clears the cache; late callbacks cannot restore old state. These are parameter-check rates, not video or flight-controller rates.
- Unknown, stale or mismatched geometry blocks start/resume. A runtime mismatch causes a resumable pause; successful readback does not resume flight automatically. Video preview and offline planning remain available.
- Ordinary surveys compare photo dimensions and FOV. Cloud reacquisition accepts matching FOV/aspect ratio with resolution no lower than the mission requirement; it does not silently rewrite the mission.
- Regenerate ordinary surveys after a camera change. Old missions with different FOV may be blocked; renaming the camera is not a valid workaround. Regenerate cloud missions for the actual camera geometry.
- An explicitly enabled UE HIL virtual image source does not depend on physical-camera profile confirmation. DJI Simulator using the real camera still requires those checks.

## Aspect ratio and operation

- V4/iOS currently use the catalog's default aspect ratio. Restore the matching photo ratio, normal resolution and 1x zoom, then wait for readback.
- V5 retains 16:9 center-crop planning with reduced vertical FOV; a crop is not treated as a full-sensor image. KMZ preparation may set the mission ratio, but readback must match before execution. Set the matching ratio manually before custom Virtual Stick missions.
- For an unconfirmed/mismatched-camera warning, inspect model, lens, mode, ratio, resolution, landscape orientation and zoom. Allow a few seconds after connecting. Persistently missing parameters are not confirmation; do not repeatedly attempt takeoff to bypass the warning.

## Remaining limits

- SDK connectivity does not establish profile availability, survey support or flight validation for that payload.
- New payloads, zoom lenses and distortion parameters are not calibrated automatically; these checks do not guarantee optical accuracy.
- Minimum capture intervals still primarily use catalog values. Dynamic readback across all cameras, storage and JPEG/RAW modes is not claimed. Camera changes require ground capture and mission validation.
- Software tests use fake providers/desktop simulators, not aircraft acceptance. Profile tests do not trigger takeoff or mission execution.
