# M2 Emulator Qualification — 2026-08-31

**Artifact scope:** Local debug/release builds from the current uncommitted workspace

**Environment:** `AlarmWO_API_37`, Android API 37 ARM64 emulator

**Decision scope:** Development evidence only; not a production or physical-device approval

## Automated evidence

| Risk/scenario | Executable evidence | Result |
|---|---|---|
| Duplicate occurrence delivery | Coordinator and Runtime DAO instrumentation | Pass — one instance/effect set |
| Duplicate audio start | `AlarmAudioControllerTest` | Pass — one owned session |
| Canonical commit before journal acknowledgement | Trigger/emergency crash-window instrumentation | Pass — replay without duplicate history/effects |
| Sticky service recreation with null intent | `AlarmRuntimeRecoveryResolverTest` | Pass — canonical attended owner recovered |
| Stale/missing/queued service request | `AlarmRuntimeRecoveryResolverTest` | Pass — stale request cannot suppress current attended owner |
| No eligible runtime owner | `AlarmRuntimeRecoveryResolverTest` | Pass — no fabricated owner |
| Stale Alarm Host presentation | Presentation/host routing instrumentation | Pass — fresh canonical snapshot wins |
| Direct Boot journal ordering | Trigger/emergency/runtime-stop importer instrumentation | Pass — semantic order and idempotent terminal history |
| FIFO overlap and emergency promotion | Coordinator/emergency instrumentation | Pass — one attention slot and oldest queued promotion |
| Release surface | Lint, manifest instrumentation, release/R8 build | Pass for compiled artifact |

## Explicitly not claimed by this report

| Scenario | Status | Required next evidence |
|---|---|---|
| Real process kill while an alarm is audibly active | Not run | Test-harness kill/restart sequence on reference device; distinguish from force-stop |
| Reboot and trigger before first unlock | Not run end-to-end | Cold reboot with credential lock plus post-unlock journal inspection |
| Doze/idle drift | Not run | Timed exact-alarm run with `dumpsys deviceidle` evidence |
| Notification/full-screen denial | Not run | API-specific permission/special-access matrix |
| Battery saver/background restriction | Not run | Timed service-start and recovery observation |
| Packaged `classic.ogg` playback | Deferred | Actual binary, checksum, license metadata, and audible playback check |
| OEM behavior and physical audio quality | Not run | Personal reference Android device qualification |

System default alarm playback remains a development fallback. Passing automated state tests does not
establish audible output, wake behavior, latency, or background-launch reliability on physical OEM
devices.
