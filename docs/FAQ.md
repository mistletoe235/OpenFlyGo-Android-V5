# Frequently asked questions (Q&A)

[English](FAQ.md) · [中文](FAQ.zh-CN.md)

## How do I confirm photos are captured during a route?

- **During a route with capture actions, watch for the brief “已拍摄” (photo captured) indicator for each capture, including on the route map page.** It is not a persistent overlay. Route previews and fake-camera tests do not establish that the real camera captured a photo.
- If the indicator never appears, do not treat route progress as proof of capture. Pause safely or take manual control as appropriate; check camera errors, photo counts, SD-card availability, write access and free space, then inspect actual images on the aircraft SD card or selected internal storage.
- A missing indicator can also be a UI visibility issue, not necessarily a failed capture. Conversely, the indicator does not replace file verification. After the initial test and each mission, confirm that actual files open and their counts and timestamps match expectations.
- **No new images in the phone gallery does not mean aircraft photos are missing.** Public installation packages disable extra phone-side downlink image archiving by default. Aircraft photos, phone caches, cloud uploads and point-cloud processing are separate stages to verify.

## Why does DJI simulation unexpectedly return home or stop? Do I need a fan?

When DJI simulation uses a connected real aircraft, its onboard electronics remain powered. Extended stationary operation without flight airflow may cause overheating. **For unexpected return-to-home or interruption, check DJI temperature/system warnings and logs**, as well as low battery, link-loss protection and remote-controller input. Do not assume every return-to-home event is caused by heat.

Remove propellers, secure the aircraft and keep cooling openings clear for bench testing. Use an external fan for additional cooling during extended tests and keep cables clear of the aircraft. If overheating is reported, safely stop the test, power down and let the aircraft cool; do not bypass protection. Software-only simulation without a powered aircraft does not have this airframe cooling issue.

Airflow during real flight helps cooling, **but does not guarantee that overheating cannot occur**. Ambient temperature, direct sunlight, hovering and blocked vents still matter. Do not take off just to cool an overheated aircraft.

## Why will simulation not restart after power loss or an App exit?

After an abnormal interruption, DJI simulation may fail to start even after the App reconnects. **Try restarting the aircraft**, rather than only repeatedly tapping Start or restarting the App. This is a recovery step for interrupted sessions, not a diagnosis of every startup failure.

1. Confirm the aircraft is on the ground, the mission is stopped and no control output is active; keep propellers removed for bench testing. Never power-cycle an aircraft in real flight.
2. Shut down the aircraft normally and power it on again. If overheating was reported, let it cool first.
3. Wait for the controller and App to reconnect. Check SDK/aircraft connectivity and fresh telemetry, then start DJI simulation again and verify that simulation is actually active.
4. Recheck the simulation origin, route and camera state before the operator decides whether to continue. **Do not reuse pre-interruption takeoff/control confirmation or resume automatically.** If startup still fails, retain errors and logs and check model support, battery, connectivity and SDK state; do not substitute real flight for a failed simulation.
