# M2 Reliable Alarm Core

**Status:** Complete for automated portfolio scope — physical/audio qualification deferred  
**Started:** 2026-08-30  
**Scope:** Personal/portfolio application

## Current increment

The current M2 increments connect trusted exact-alarm delivery and the device-protected recovery
journal to canonical persisted alarm instances, then launch the attended instance into a durable
foreground runtime.

Implemented:

- journal-first exact receiver flow, with immediate canonical reconciliation when the user is
  unlocked;
- atomic occurrence lookup, enabled/current-revision validation, `FIRED` transition, alarm display
  snapshot, immutable mission snapshot, instance creation, and runtime-effect insertion;
- at-most-one instance per occurrence and duplicate delivery returning the existing instance without
  regenerating questions, recurrence, or effects;
- transactional monotonic FIFO `queue_order` and one nullable `attention_slot=1`; overlapping alarms
  become `PENDING_ATTENTION` without creating another runtime-start effect;
- wall-clock trigger time plus elapsed-realtime and boot-session correlation when reconciliation is
  immediate; post-boot imports preserve wall-clock time and safely leave monotonic fields null;
- deterministic versioned Math question generation and answer persistence before the instance can be
  presented;
- `START_ALARM_RUNTIME` and `PRESENT_ACTIVE_INSTANCE` outbox effects for the attended instance;
- one-time alarms disable atomically at trigger; weekly alarms create the next occurrence plus exact
  scheduling and Direct Boot mirror effects in the same transaction;
- idempotent pending `TRIGGERED`, `EMERGENCY_DISMISSED`, and `RUNTIME_STOPPED` journal import,
  including recovery when canonical commit succeeded but journal acknowledgement was interrupted;
- journal reconciliation applies semantic event precedence (trigger, emergency dismissal, runtime
  stop), so equal-timestamp Direct Boot events cannot terminal a missing instance; emergency history
  retains the original device-protected event time;
- invalid occurrence/alarm references and unsafe emergency conflicts are quarantined with sanitized
  reason codes; runtime-stop evidence remains pending until its canonical instance is terminal;
- boot and unlock reconciliation imports journal entries before scheduling and mirror rebuild.
- a filtered runtime-effect runner validates `START_ALARM_RUNTIME` identity and aggregate version,
  starts only the canonical attended instance, acknowledges terminal no-ops, and applies bounded
  retry or dead-letter handling without consuming unrelated presentation effects;
- an explicit foreground-service launch adapter uses a stable instance URI, making duplicate effect
  delivery safe while keeping Android component resolution closed to other applications;
- a `mediaPlayback` foreground service publishes its high-importance alarm notification immediately,
  recovers the attended instance after service restart, and validates canonical state before audio;
- one process-local audio controller owns at most one looping alarm stream; duplicate starts are
  no-ops and replacement stops the prior stream before playback begins;
- alarm playback uses `USAGE_ALARM`, transient-exclusive audio focus, pause/resume focus handling,
  a partial wake lock, and idempotent cleanup; a visible recovery notification replaces silent
  failure when audio cannot start;
- unlocked reconciliation drains runtime launch effects before schedule and Direct Boot mirror
  effects.
- a filtered presentation-effect runner validates `PRESENT_ACTIVE_INSTANCE`, retries transient
  failures, quarantines malformed/stale requests, and opens the host with a stable explicit intent;
- private single-task `AlarmHostActivity` uses show-when-locked, turn-screen-on, keep-screen-on, and
  excluded-from-recents behavior; system Back cannot dismiss the active alarm;
- the host treats intent data only as correlation and transactionally re-queries the canonical
  attended instance, mission snapshot, persisted progress, current Math prompt, and queued count;
- notification content and full-screen intents target the same native host, while stale presentation
  intents recover the current canonical attended instance rather than rendering stale state;
- loading, no-active-instance, and database-recovery-failure surfaces are native and do not depend on
  React Native startup.
- the Alarm Host exposes a native emergency dialog whose continuous hold state uses monotonic time;
  release, pointer loss/out-of-bounds, dialog dismissal, or Activity pause resets all progress and
  only a full five-second hold can issue the trusted dismissal command;
- emergency dismissal atomically terminals only the attended instance as `EMERGENCY_DISMISSED`,
  preserves verified progress, inserts immutable history with `EMERGENCY_HOLD`, emits an
  instance-scoped runtime-stop effect, and promotes at most the oldest FIFO queued instance;
- promoted instances receive durable start and presentation effects in the same transaction; stop
  effects drain first, and stale stop delivery cannot terminate a newer runtime owner;
- if canonical persistence is unavailable after the hold, the safety path requests immediate
  instance-scoped audio stop and idempotently records `EMERGENCY_DISMISSED` plus `RUNTIME_STOPPED` in
  the device-protected journal without ever inferring mission success;
- the Alarm Host enables mission entry only after a fresh canonical snapshot is re-read and routed
  through an allowlisted `MATH`/`PUSH_UP`/`QR` resolver;
- Math routing exposes only the current persisted prompt, while Push-up and QR reserve explicit
  native destinations without activating camera resources before their verification workflows exist;
- missing Math prompts, unknown mission types, terminal/completed snapshots, and recovery states fail
  closed to a reloadable recovery surface with emergency dismissal still reachable.

The production sound binary has not been received yet. The stable `classic` catalog entry now maps to
`assets/alarms/classic.ogg`; runtime attempts that private packaged path first and falls back to the
device alarm tone (notification tone only as a final system fallback) if the asset is absent or
unreadable. Importing the licensed file therefore requires no canonical data migration or playback
code change, but its actual checksum/source/license metadata must not be invented.
The product owner deferred the binary import on 2026-08-31; this does not convert fallback playback
into packaged-audio or physical-device evidence.

## Verification evidence

- 18 native-core JVM tests pass, including deterministic Math generation, operation-mask rules, and
  emergency availability before mission start.
- 60 native-core instrumentation tests pass on the Android 37 `AlarmWO_API_37` emulator.
- New instrumentation coverage verifies atomic snapshot/question/effect creation, duplicate delivery,
  FIFO overlap, single attention slot, one-time auto-disable, crash-window recovery,
  invalid-journal quarantine, semantic journal ordering, deferred non-terminal runtime-stop evidence,
  and crash-window-safe emergency replay without duplicate history or effects.
- Runtime-runner instrumentation covers stable identity, acknowledgement, transient retry,
  malformed payload quarantine, and invalid attention state.
- Active snapshot coverage verifies canonical attended identity, immutable label/mission/progress,
  current Math prompt without its answer key, and FIFO queued count.
- Emergency instrumentation covers terminal/history persistence, progress retention, idempotent
  replay, rejection of queued-instance dismissal, exactly-one FIFO promotion, stop-effect
  acknowledgement, and idempotent Direct Boot fallback journaling.
- 29 app instrumentation tests pass, including continuous five-second timing/reset behavior,
  instance-scoped stop identity, stale-stop ownership protection, the TurboModule bridge,
  foreground-service/Alarm Host identity, single-stream audio behavior, allowlisted mission routing,
  and fail-closed recovery for stale or inconsistent mission snapshots.
- Packaged-audio catalog tests verify the stable `classic` path, strict unknown-ID behavior, and no
  arbitrary/path-traversal asset resolution.
- Process-recovery selection now ignores stale terminal, missing, or queued requested instances and
  recovers the latest eligible canonical attended owner; null-intent sticky restart and no-owner
  shutdown decisions have deterministic regression coverage.
- Automated versus unexecuted device scenarios are recorded in
  [`../qualification/M2_EMULATOR_QUALIFICATION_2026-08-31.md`](../qualification/M2_EMULATOR_QUALIFICATION_2026-08-31.md)
  without upgrading emulator evidence into a physical-device claim.
- 9 Jest tests remain green.

## Remaining M2 increments

1. Run the remaining API/device/process-death/locked-boot qualification matrix, including actual playback and
   background-start behavior on physical target devices.

Deferred, non-blocking for current portfolio development: receive `classic.ogg`, import it at the
reserved path, and record its real SHA-256/source/license-evidence metadata.

The routing destinations intentionally do not implement mission completion. Math answer authority
remains M4, QR verification remains M5, and verified Push-up remains M6; none of the M2 hooks can
advance progress, stop audio, or write a successful history record.

`START_ALARM_RUNTIME` and `PRESENT_ACTIVE_INSTANCE` are acknowledged only after Android accepts their
explicit component launch requests. Both destination components independently revalidate canonical
state. Android background/full-screen launch behavior still requires the physical-device
qualification listed above.
