# V4 / V5 HIL source parity

This matrix compares the Android V4 implementation in `dji-vln-mini2-camera` with this MSDK V5 application. It intentionally distinguishes network/mock validation from DJI `SimulatorState` validation.

| Capability | V4 | V5 implementation | Verification |
| --- | --- | --- | --- |
| Hotspot peer discovery and rediscovery | UDP HELLO/heartbeat with peer timeout | Same protocol plus peer sequence replay window; timeout resets peer and sequence | `MODE=offline` |
| LAN fixed peer | User-provided UE host | Timeout resets sequence and resends HELLO, so a restarted UE process can start again at sequence zero | `MODE=offline HOST=<ue-ip>` |
| UDP status/pose path | Independent from camera TCP | Same | Offline loopback report |
| TCP camera reconnect | Listener/server survives client reconnect | Connection generations reject late frames | Unit tests + UE integration |
| DJI Simulator start API | `Simulator.start(InitializationData)` | `SimulatorManager.enableSimulator(InitializationSettings)` | `MODE=raw-source` |
| Simulator callback frequency | V4 SDK accepts 2–150 Hz and this app defaults to 100 Hz | V5 SDK exposes no setter; `SimulatorStatusListener` is retained as the simulator/local-frame anchor | RAW callback count shown in regression |
| HIL pose cadence | V4 forwards periodic SimulatorState | V5 currently republishes cached flight-controller telemetry at 2–150 Hz; this is wire cadence, not proof of fresh DJI samples | Offline transport regression; source cadence still requires per-field hardware measurement |
| UDP scheduling | V4 receive and periodic transmit paths are independent | V5 uses a dedicated blocking receive executor plus a separate scheduled transmit pool, so status rendering and inbound waits cannot serialize the POSE loop | Offline regression + device RTT/POSE observation |
| Adopt active airborne simulator | Never restart | Never restart | Lifecycle unit test |
| Grounded process/update recovery | Stop then restart residual session | Stop then restart with bounded timeout/retry | Lifecycle unit test + `raw-source` |
| Missing completion callback | Trust actual active state | Trust `isSimulatorEnabled()`/fresh RAW | Lifecycle unit test |
| Active but stale RAW callback | Rebind callback without restart | Rebind listener up to five times | `MODE=raw-rebind` |
| Network/Simulator lifetime | Network stays alive on Simulator failure | Same | Lifecycle unit test |
| Debug offline regression | Mock dynamics, explicitly synthetic | Ported; logs `source=MOCK synthetic=true` | `tools/run_hil_android_regression.sh` |
| Debug pose regression | Real DJI flight-controller telemetry plus SimulatorState anchor | Source and rebind checks; logs `synthetic=false`, raw callback count, telemetry changes, polling samples, and output rate | Script raw modes |
| HIL image source control | DJI/UE switch in HIL UI | DJI/UE source selector calls the shared camera router | Manual UI smoke test |

## Important V5 differences

- MSDK V5 has no equivalent of V4's Simulator update-frequency setter. The selected frequency controls a read-only Android pose sampler and Android-to-UE POSE cap; it does not change DJI's internal callback cadence.
- Static flight-controller values may not trigger listeners repeatedly. The sampler currently republishes the latest valid cached state without sending Virtual Stick commands, but repeated packets must not be counted as fresh DJI samples.
- V5 lifecycle decisions use `SimulatorManager.isSimulatorEnabled()` plus fresh `SimulatorState`. Callback success alone is not authoritative.
- A running airborne simulator is adopted. Automatic stop/restart is only permitted while the simulator is grounded.
- HIL UDP/TCP remains independent: a Simulator error blocks RAW/POSE authority but does not destroy a healthy UE network session.

## Regression commands

```bash
MODE=offline ANDROID_SERIAL=<serial> tools/run_hil_android_regression.sh
MODE=raw-source ANDROID_SERIAL=<serial> tools/run_hil_android_regression.sh
MODE=raw-rebind ANDROID_SERIAL=<serial> tools/run_hil_android_regression.sh
```
