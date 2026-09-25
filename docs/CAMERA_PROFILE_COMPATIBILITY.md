# Camera profile compatibility

Updated September 25, 2026. Applies to the camera-advisory fix and later builds.

## Automatic matching and estimates

- Aircraft, camera and lens aliases select published photo dimensions, field of view and capture intervals. Matching is not per-aircraft optical calibration or flight acceptance.
- Unknown payloads and lenses do not silently inherit a verified wide-angle identity. Their planning parameters remain explicitly estimated. A missing catalog entry alone does not prevent execution.
- Photo ratio, resolution, zoom and supported orientation readback refine confidence in the planning geometry. V5 supports center-cropped 16:9 planning.

## Execution behavior

- Missing, stale or different geometry is a non-blocking coverage/GSD advisory. It does not prevent start/resume, pause a running survey, or pause a V5 native KMZ mission. No additional acknowledgment button is required.
- The same distinction applies to cloud-imported reacquisition missions. Geometry/aspect differences appear as warnings, not execution errors. Mission positions, altitude and speed are not silently rewritten.
- A disconnected/unusable camera, an unsupported KMZ payload mapping, and actual capture/flight safety failures still follow their existing handling. RC takeover, stale flight telemetry, battery, altitude and controller safeguards are unchanged.
- V5 tries to set the planned photo ratio before KMZ preparation. Failure to set a geometry preference is logged as an advisory; actual mission upload and execution must still succeed.
- An explicitly enabled UE HIL virtual image source remains independent of physical-camera geometry.

## Readback reliability

- Requests are bounded to once per second per parameter. A failed refresh does not erase a still-fresh successful value, but it never extends that value's original two-second validity window.
- Android evaluates sample age after a synchronous callback, fixing the case where a successful callback crossing a millisecond boundary appeared invalid.
- Disconnecting/changing camera sessions clears cached parameters. Late callbacks from old sessions cannot restore old state. Expired values are not relabeled as verified.

## Using another camera

Use the actual lens, photo ratio and zoom when accuracy matters; regenerate local or workstation missions if their assumed footprint differs. Estimated parameters can produce inaccurate GSD, overlap or gaps. Execution availability is not a guarantee of survey quality or compatibility with every DJI-supported payload.

These changes do not add camera calibration, change aircraft support, or replace ground capture checks and supervised flight validation. Software regression uses fake providers/desktop simulators, not real-flight acceptance.
