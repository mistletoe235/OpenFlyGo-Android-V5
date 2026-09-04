# Survey Execution and UE Interface

Date: 2026-08-20

## Backends

| Backend | Control path | Camera path | Intended use |
| --- | --- | --- | --- |
| DJI KMZ | `DjiV5WaylinePort` upload/execute/pause/resume/stop | WPML actions | Supported-aircraft production path |
| Custom Virtual Stick | Android 20 Hz `SurveyWaypointFollower` to DJI body velocity | DJI camera distance/time trigger | Simulator and guarded manual-takeoff fallback |
| UE HIL | Same Custom Virtual Stick loop plus UE HTTP events | DJI trigger plus UE capture notification | UE visualization, virtual-camera and HIL validation |

Selecting UE HIL does not replace the binary HIL transport. The shared HIL
session continues to use UDP for low-latency state/control and TCP for complete
JPEG/PNG frames. The Survey HTTP bridge carries mission-level JSON events that
are easier to inspect and replay.

## Coordinate Contract

- Geodetic positions: WGS84 latitude/longitude degrees.
- Local world convention in HIL: ENU.
- Vehicle body command convention: FRU (`forward`, `right`, `up`).
- Heading: degrees clockwise from true north.
- Gimbal pitch: negative is down.
- Altitude in Survey events: meters AGL/relative to takeoff according to the
  generated mission contract.

The Android follower converts WGS84 north/east error into body-frame forward
and right velocity using the live aircraft heading. UE must not apply another
body-axis swap to the Survey target coordinates.

## UE HTTP Endpoints

Android is the HTTP client and posts to the base URL entered on the Survey page.
Each request uses `Content-Type: application/json; charset=utf-8` and a two
second connect/read timeout.

### `POST /v1/survey/mission`

Sent once before Virtual Stick acquisition. The body contains schema metadata,
the coordinate contract and the complete `SurveyMission` JSON.

### `POST /v1/survey/telemetry`

Best-effort live update while running. Only one telemetry POST is allowed in
flight at a time; a slow UE endpoint causes samples to be dropped rather than
building a queue.

Important fields:

- `timestamp_epoch_ms`
- `pose.latitude_wgs84_deg`
- `pose.longitude_wgs84_deg`
- `pose.altitude_agl_m`
- `pose.heading_cw_from_north_deg`
- `pose.gimbal_pitch_deg`
- `dji_simulator.active` / `dji_simulator.flying`
- `execution.state` / `execution.waypoint_index`

### `POST /v1/survey/target`

Sent when the execution leg changes. It includes execution phase, leg index,
mission waypoint index and the complete target waypoint.

### `POST /v1/survey/capture`

Sent after a distance/time capture request. The current Android implementation
reports the capture request and aircraft pose; the actual virtual-camera image
continues over the HIL TCP frame stream. A zero image width/height with format
`external` therefore means “associate the current HIL frame”, not an empty
camera image.

Any HTTP `2xx` response is accepted. HTTP errors are diagnostic and do not
create an unbounded retry queue.

## Runtime State and Pause Recovery

State transitions use `SurveySimulatorExecutionStateMachine`:

`IDLE → ARMING → RUNNING → PAUSED/COMPLETED/ABORTED`

- Start builds safe-climb, transit, Survey and completion legs.
- Each 40 ms tick (25 Hz) validates telemetry freshness, simulator/real-aircraft mode,
  Virtual Stick ownership and external intervention.
- RC takeover or a runtime gate failure sends zero, releases Virtual Stick and
  stores the live WGS84 pause point.
- Resume reacquires Virtual Stick and first creates a no-capture recovery leg to
  the exact pause point. It never resumes implicitly.
- The UI reports current-section and total remaining time using distance,
  configured/live speed, yaw turn time and gimbal stabilization time.
- Checkpoints are exported as schema 3 JSON with mission ID, waypoint index,
  execution leg, phase and optional recovery point.

## DSM and ROI Inputs

- Quick planning creates a rectangle around the aircraft position; without a
  valid aircraft position it uses the generic development fallback coordinate.
- White ROI handles in the preview are draggable. Regenerate after editing to
  rebuild route passes.
- Local DSM input accepts north-up GeoTIFF in supported WGS84, Web Mercator or
  WGS84 UTM projections.
- Global building height uses a static COG URL template containing `{x}` and
  `{y}`. Optional `{west}`, `{south}`, `{east}` and `{north}` substitutions are
  also supported.
- Download limits are 0.25 degrees per ROI, at most nine 0.2-degree tiles and
  128 MB per tile. Tiles are cached and copied to
  `Download/DJI-VLN/dsm` for file-manager access.

Downloaded GlobalBuildingAtlas height is research/non-commercial
preview/simulation data. Real terrain-following flight requires a recent,
locally verified DSM and hardware qualification.

## Safety Notes

- A real-aircraft custom mission requires manual takeoff and stable flight.
- Terrain missions marked unverified remain blocked by the real-flight gate.
- App/background exit, controller loss or command failure sends zero and
  releases Virtual Stick.
- Emulator success does not prove camera timing, gimbal limits, GPS quality or
  authority behavior on a specific product.
