# Phone-side HIL quickstart

[English](HIL_QUICKSTART.md) · [Chinese reference](HIL_QUICKSTART.zh-CN.md)

HIL means hardware-in-the-loop. Start with a compatible OpenFly UE/AirSim scene
and HIL adapter; use the [main simulator guide](https://github.com/mistletoe235/OpenFlyScan/blob/main/docs/simulator.md)
for installation and downloads. A generic AirSim/ROS setup or a running UE window
does not automatically implement the OpenFly HIL protocol.

## 1. Distinguish the operating modes

| Mode | DJI hardware required? | What it checks |
| --- | --- | --- |
| Android offline regression / Xcode Simulator Mock | No | UI, planning, protocol and software state machines; not DJI controller acceptance |
| DJI Simulator + phone + UE HIL | Supported aircraft, controller and phone | DJI simulated state, control, UE virtual camera and mission execution |
| Real flight | Yes; simulation must be off | Actual flight, requiring a fresh site and flight-readiness review |

The phone controls the DJI simulated flight controller, sends simulated poses to
UE, and receives UE-rendered images. UE collision/STOP events are software safety
events. GS imagery and point clouds are **not injected into DJI obstacle sensors**.

## 2. Preparation and networking

1. Select the README's compatible app/aircraft combination. Complete SDK registration and controller USB connection.
2. **Remove propellers, secure the aircraft and keep power-off/manual takeover available.** Do not test takeoff or control until DJI Simulator activation is confirmed.
3. Open a compatible UE HIL scene. Avoid a second HIL/preview instance competing for ports.
4. Connect the phone and UE computer to a trusted LAN. Enter the **UE computer's LAN IP**, not the phone address, `127.0.0.1`, an SSH alias or a reconstruction-service URL. Alternatively, join the phone hotspot and use hotspot discovery.
5. Permit the traffic below; check network isolation, routing and firewall rules rather than disabling the entire firewall.

| Receiver | Default port | Traffic |
| --- | --- | --- |
| UE computer | UDP 30020 | Phone to UE: HELLO, POSE, heartbeat and PING |
| Phone | UDP 30021 | UE to phone: heartbeat, PONG and safety events; adapters should also support replies to the request's source endpoint |
| Phone | TCP 30022 | Normally phone-listen/UE-connect for image delivery; a compatibility path also allows the phone to connect to a UE image port |
| Optional UE observer | HTTP 30010 | Routes, telemetry, targets and capture events; configured separately from HIL and cloud reconstruction |

Peer/session/sequence checks are not cryptographic authentication, and the HIL
protocol does not encrypt transport. **Use a trusted LAN or controlled VPN; do not
expose HIL ports publicly.** Cloud bearer tokens are not HIL credentials. TCP frames
require the protocol header and complete JPEG/PNG payload, not an RTSP URL or a raw
JPEG written directly to the socket.

## 3. Phone operation

1. Open the UE HIL panel in More/settings and choose LAN or hotspot mode. iOS personal-hotspot mode provides discovery and a manual UE hotspot IP fallback; use the address assigned to the computer.
2. Set the simulated start location while the simulator is stopped and the aircraft is on the ground. Match the UE scene's WGS84 origin and altitude datum. Do not change the origin or force-restart Simulator during simulated flight.
3. Start HIL and verify the actual DJI Simulator state. A working network session is not proof of simulated flight control. Stop if Simulator is unavailable or fails to start; do not issue the next commands in real-aircraft mode.
4. Confirm peer heartbeat, active DJI Simulator with fresh poses, and an online TCP image stream with increasing frame count. Select the UE virtual camera and check the displayed scene.
5. Begin with short, slow simulated control or a small survey. Check direction, altitude and camera view, then pause, disconnect, resume and collision handling. Takeoff/execution always requires explicit action after simulation is confirmed.
6. Land the simulated aircraft, stop the mission and HIL/Simulator, then close UE. Before real flight, confirm Simulator is off, the DJI camera is restored, and home/GPS/camera states are valid. Do not directly reuse a simulation's geographic mission for real flight.

Coordinates use world ENU (east/north/up), body FRU (forward/right/up), meters and
meters per second. Heading is zero at true north and positive clockwise; downward
gimbal pitch is negative. The adapter must explicitly convert AirSim NED and UE
centimeters/handedness. Do not apply the same axis conversion twice.

## 4. Rates and platform differences

- V4 can request a DJI Simulator state rate, configured at 100 Hz by default; actual callback behavior depends on firmware.
- The public V5 Simulator API has no equivalent rate setting. Phone pose-send rate is not fresh controller-sample rate; retransmitting unchanged state is not a sensor update.
- On iOS, inspect controller, UDP and TCP freshness separately. Xcode Mock success is not hardware HIL acceptance.
- Start HIL validation with default stop-and-capture missions. V5 continuous reacquisition requires its DJI KMZ execution path; do not force these missions through a custom/UE backend. V4/iOS have a separate app-side schema 14 implementation, not the V5 KMZ path, and still require hardware validation.

## 5. Troubleshooting

| Symptom | Check first |
| --- | --- |
| No peer | LAN membership, computer IP, UDP 30020/30021, firewall, hotspot isolation and a running HIL adapter |
| Peer online, no poses | SDK registration, aircraft link, Simulator activation and fresh raw state; not just the network indicator |
| Poses online, no images | UE camera selection, TCP 30022, connection direction, framing and frame counter |
| Wrong view or map position | WGS84 origin, ENU/NED conversion, meters/centimeters and heading reference; pause before adjusting |
| No movement / execution disabled | Reported preflight block, backend, Simulator state, control authority and telemetry freshness |
| No automatic resume after disconnect | Expected safety behavior; inspect state/checkpoint and explicitly resume |
| Observer HTTP fails while HIL works | Port 30010 is separate; also check V4 Release HTTP/iOS ATS restrictions rather than globally bypassing them |

GS visual rendering, collision proxies and DJI obstacle avoidance are separate.
An apparently clear point cloud does not establish collision geometry. Automated
RAW regression scripts may command simulated takeoff, stick inputs and landing;
they are not read-only diagnostics and must not run in real-flight sessions.

Developer references: [V4/V5 state and rate differences](V4_V5_HIL_PARITY.md), [survey execution and HTTP observation](SURVEY_EXECUTION_INTERFACE.md).
