# Cloud uploads, point clouds and reacquisition

[English](CLOUD_ROUTE_WORKFLOW.md) · [Chinese reference](CLOUD_ROUTE_WORKFLOW.zh-CN.md)

## Workstation connection

Workstation installation, GPU/model setup and HTTPS/VPN deployment are documented
in the [main workstation guide](https://github.com/mistletoe235/OpenFlyScan/blob/main/docs/workstation.md).
The phone uses HTTP/HTTPS, not SSH. Obtain the following from the deployer:

| Setting | Enter in the app |
| --- | --- |
| Service root | A reachable URL such as `https://reconstruction.example.com`, without `/api` or a session path |
| Bearer access code | The token only; the app adds `Authorization: Bearer ...`. Keep it out of URLs, screenshots and logs |
| Existing session ID | Needed for result browsing; use an ID on the selected server, not a task name or complete URL |
| Mission compatibility | All clients accept schemas 1–14; default exports use 13, explicitly requested continuous reacquisition uses 14 |
| Takeoff ASL | The actual absolute altitude and its source, consistent with the server's datum |

On a phone, `localhost`/`127.0.0.1` refers to the phone, not the workstation.
Even if the workstation listens on port 55000, use its phone-reachable host or
HTTPS gateway. Verify VPN, hotspot routing and DNS from the phone. V4 Release
blocks cleartext HTTP; Debug can test it on a controlled LAN. V5 permits HTTP but
HTTPS is preferred because cleartext exposes images and credentials. Do not
bypass certificate checks to resolve HTTPS errors.

## Capture and upload from this device

1. Connect the aircraft, confirm the camera profile and plan a small survey. Open the local capture/upload section of cloud reconstruction.
2. Save the root URL and access code. Check task name, horizontal camera FOV, camera model, actual takeoff ASL and reacquisition-task budget, then create a session and record its ID. Relative flight height is not ASL.
3. Execute through the normal preflight workflow. After valid capture triggers, the app saves fresh downlink frames with metadata and queues them for upload. This is neither continuous video streaming nor automatic transfer of full-resolution SD originals.
4. Monitor captured, uploaded, pending and rejected/not-queued counts. Invalid/stale video, GPS, ASL or disk writes can leave a captured photo unqueued. Resolve the cause. Network failures preserve the queue for retry; retrying a sequence number must not double-count it on the server.
5. After capture finishes and pending uploads reach zero, explicitly finish collection/upload to finalize the session. Route completion does not mean upload completion. Refresh processing status and retry explicitly when needed rather than repeatedly creating replacement sessions.
6. When ready, open the PLY and rotate, zoom or fit the view. This is a reconstruction result, not a live obstacle map. Progressive updates depend on the workstation, not a guaranteed refresh for every uploaded image.

To try historical photos first, select a read-only folder of JPG/JPEG files in
the local upload workflow. The app checks EXIF and compresses for upload; it does
not invent missing GPS or altitude. V5's `relative_height_test` branch has different
output limits from production ASL sessions and cannot supply real-flight missions.

## Read an existing session

Open the existing-session browser, enter the service root, token and session ID,
and connect/refresh. It reads `GET /api/sessions/{id}/result`. View the ready point
cloud or download the mission: unapproved missions use a separate read-only diagram;
approved missions proceed to import confirmation. This entry does not create,
finalize, retry or cancel server tasks, upload photos, take over the local queue,
or control the aircraft. It cannot resume another phone's pending uploads.

## From a proposed route to reacquisition

1. Previewing an unapproved route does not activate a mission. Import still requires the result and payload review fields to permit it. Test-only/relative-height sessions remain point-cloud-only; do not remove or rewrite review metadata.
2. Import only loads the local planner. Check mission ID, schema, groups/capture points, actual takeoff location and WGS84/ASL conversion. Do not reuse an old relative altitude after changing takeoff locations.
3. Inspect the complete flight/transit/return path, clearance, RTH height, camera/gimbal direction and duration. Missing or stale point-cloud geometry is not clearance evidence. Resolve an executing/paused task lock before replacing the mission.
4. Validate a small mission in HIL/simulation, complete local preflight, then explicitly prepare/execute with the selected backend. V5 KMZ requires its generation/upload/preparation sequence; V4 Mini 2 requires the app foreground and control link.
5. Schema 13 stops and stabilizes before capture. V5 continuous reacquisition is opt-in, active-recapture-only and DJI-KMZ-only; changing it invalidates mission identity, checkpoints and prepared/uploaded KMZ, so prepare again. Updated V4/iOS builds opt into schema 14 when creating a cloud session and use app-side Virtual Stick for eligible intermediate points. Turns, height changes and boundaries may still stop.
6. At completion, inspect actual photos and missing/unconfirmed records. Reaching the endpoint does not establish valid capture at every point. Follow the workstation workflow for the next round; do not assume a finalized session accepts appended photos.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Timeout / connection refused | Phone reachability, running service, root URL/port, HTTPS, VPN and firewall |
| HTTP 401 / 403 | Access code and permission; update credentials after changing services |
| HTTP 404 / mismatched session | Session/server pairing and an accidentally appended API path |
| Captured count rises but uploaded does not | Pending/rejected records, GPS/ASL, fresh video, network and free disk space |
| No cloud or mission | `phase`, `error`, `mission_error`, processing progress and test-mode restrictions |
| Cloud download refused | Size/format limits and same-origin URL rules; the read-only browser rejects redirects |
| Import/execution blocked | Schema, review fields, coordinates/height, task lock and local preflight; do not bypass checks |

The protocol details below describe the independent browser. Its cache and size
limits need not match the local capture/upload workflow.

This route-related workflow is included in both private and public clients. It does not need
MNN, VLN inference, or model distribution. Open the cloud/reconstruction entry and choose
**Open existing cloud session**, rather than **Capture and upload from this device**.

Enter the service root HTTP/HTTPS URL, bearer access code and existing session ID. This is not
an SSH connection. Connect/refresh reads `GET /api/sessions/{id}/result`, verifies the session ID,
then enables point-cloud viewing and mission preview when artifacts are available.

## Independent, read-only browser

- Uses only GET requests; never creates/finalizes/retries/cancels server tasks or uploads photos.
- Does not attach the remote session to the local capture/upload queue or change its connection.
- PLY is downloaded to one bounded internal cache file and opened in the existing native viewer.
  It is a reconstruction result, not a live obstacle map. This entry does not fetch candidate overlays.
- Mission download opens a confirmation showing the mission name. Import only loads the mission
  into the planner; it does not request control or execute. Check coordinates, altitude and preflight.
- Execution/paused-task locks are rechecked when the download finishes and when import is confirmed.
  Existing local-upload mission imports also recheck their asynchronous execution locks.

## Compatibility and safeguards

When creating a cloud session, the continuous-recapture checkbox explicitly requests schema 14
and `CONTINUOUS_EXPERIMENTAL`. Unchecked requests retain schema 13 stop-and-capture. Relative-height
tests disable this option. Execution of continuous missions still requires the DJI KMZ backend.

Unapproved missions open a separate read-only, north-up route diagram with waypoint count and
height range. This preview never activates a mission or obtains flight control. Import still requires
`openfly_v5_mission.safe_to_execute=true` and compatible payload review flags; the platform
decoder/validator and preflight checks remain in place. Relative-height/test-only sessions remain
point-cloud-only. Review metadata is never rewritten or removed.

Android V4 accepts schema 1–14/WGS84 and retains its strict `execution_review` approval check.
Its schema 14 continuous recapture uses app-side Virtual Stick, not DJI KMZ; the cloud-session
checkbox explicitly requests this experimental mode and is off by default.
Android V5 also accepts schema 14 and retains its existing local-upload review workflow.
Unsupported schemas are not silently downgraded. The updated iOS client supports schema 1–14 with experimental app-side continuous recapture and has a
separate warning-based preview import workflow; the clients do not claim identical approval policies.

This V5 route-only build disables terrain following. A mission with `terrainPlan` is rejected
at import/activation, execution (including resume), and DJI KMZ conversion even if its terrain
verification flag is true. Ordinary fixed-height routes and non-terrain recapture remain supported.

Results are capped at 1 MiB, mission files at 8 MiB, PLY at 64 MiB, including unknown-length
responses. Failed/truncated downloads preserve the previous valid file and remove partial files.
Closing the browser cancels requests; obsolete results cannot update a new session.

Only same-protocol/host/port artifact links are accepted; redirects, URL credentials, invalid IDs
and access-code control characters are rejected. The access code uses a separate encrypted Android
Keystore-backed preference namespace, not the upload session token. Changing the service address
clears the displayed token. Saved connection settings restore only with the saved service address.

Prefer HTTPS. HTTP sends bearer credentials in plaintext; the UI warns about this. Public builds
use their configurable `V86_DEFAULT_ENDPOINT`; no private server credentials are included.

## Verification

`V86RemoteSessionClientTest` covers read-only authenticated GET, matching session IDs, HTTP failure,
redirect rejection, cross-origin rejection, approval/test-only handling, unchanged mission metadata,
streaming/declaration byte limits, truncated PLY/cache cleanup and cancellation. Use normal Gradle
unit tests and `assembleDebug`; this does not command an aircraft.

`V86LiveServiceTest` is an opt-in check against a configured workstation: it creates and cancels
empty sessions and reads a completed reference mission for preview. Set `OPENFLYSCAN_LIVE_ENDPOINT`,
`OPENFLYSCAN_LIVE_TOKEN` and `OPENFLYSCAN_LIVE_SESSION` privately to run it. It uploads no images and
issues no aircraft commands. Without those variables it is explicitly skipped.
