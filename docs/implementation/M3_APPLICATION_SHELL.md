# M3 Application Shell

**Status:** In Progress  
**Started:** 2026-08-31  
**Scope:** Personal/portfolio application

## Current increment — authoritative enable/disable controls

This increment establishes the first React Native application shell without allowing JavaScript
startup timing to bypass an active alarm.

Implemented:

- Codegen-verified `getActiveRuntimeSnapshot` and `launchActiveInstance` TurboModule methods;
- a native active-runtime DTO sourced from the canonical attended instance, including persisted
  revision, runtime/mission state, Math prompt without answer key, progress, and FIFO queued count;
- strict UUID/revision validation on both sides of the bridge;
- process-scoped launch receipts: replaying the same request returns the same session and does not
  open a second Alarm Host; conflicting reuse fails with a stable idempotency error;
- instance and revision are revalidated immediately before the explicit native presentation;
- startup verifies the native contract and active-runtime snapshot before rendering Home;
- an active snapshot routes to the native Alarm Host and never reveals normal Home controls;
- query/contract/launch failure locks Home behind an explicit recovery surface and retry action;
- loading and delayed-loading copy communicate the recovery check without assuming success;
- an atomic native `getHomeSnapshot` reads the canonical Room alarm list, active runtime summary,
  and five most recent history rows in one transaction;
- the bridge and JavaScript wrapper enforce contract versioning plus bounded alarm/history payloads,
  UUIDs, revisions, schedule values, mission targets, and active-instance consistency;
- Home renders the authoritative alarm count/list, next enabled occurrence, weekly/once schedule,
  mission summary, enabled state, and recent history while retaining the offline/build evidence;
- a second active-runtime check inside the atomic Home snapshot closes the startup race between the
  initial recovery query and rendering normal controls;
- Add Alarm and persisted alarm rows enter a native-backed create/edit editor;
- the editor supports local time, once/weekly schedule selection, seven weekday controls, optional
  label normalized to `Alarm`, Math/Push-up/QR mission selection, and bounded mission targets;
- once schedules calculate the next local occurrence while weekly schedules require at least one
  day; the configured IANA device timezone is persisted with the draft;
- mission-specific payloads are constructed without leaking incompatible fields: Math receives the
  accepted operation/generator version, Push-up receives the available profile version, and QR is
  stored with target one for later native registration;
- invalid input disables Save and shows inline recovery copy; the JavaScript boundary repeats
  the full native schedule, revision, mission, and configuration validation before crossing Codegen;
- each logical Save gets one UUID v4 command ID which is retained across timeout/error retries and
  replaced only after form changes; a successful ack is followed by a fresh authoritative Home
  snapshot rather than optimistic list mutation;
- new alarms are persisted as disabled drafts, while edits carry the exact snapshot revision and
  preserve the native enabled-edit scheduling workflow;
- each Home alarm row now separates its edit action from an accessible ON/OFF switch; only the
  targeted row is locked and shows progress while its native command is pending;
- enable and disable commands use the exact authoritative revision and a per-action UUID v4; UI
  state changes only after the command ack and a fresh Home snapshot confirm the durable result;
- retry after timeout or capability failure reuses the same command ID when the alarm revision and
  desired state are unchanged, while a stale revision causes an authoritative refresh before the
  user repeats the action;
- capability-required, unregistered-QR, conflict/invalid-state, and unknown failures preserve the
  prior switch state and show sanitized actionable copy without exposing native exception detail;
- accepted light/dark palette behavior and safe-area/status-bar handling.

## Verification evidence

- JavaScript lint and TypeScript checking pass.
- 26 Jest tests pass. Startup tests cover the unresolved gate, no-active Home route, both
  active-instance race paths, fail-closed storage error, retry, foreground re-query, authoritative
  alarm rendering, create/edit navigation, weekly and future one-time payloads, mission-specific
  configuration, inline invalid-time blocking, post-save Home refresh, and stable command-ID retry;
  wrapper tests cover Home DTO validation, complete draft validation, active DTO consistency, and
  launch metadata injection. Home tests additionally cover revision-aware enable, disable, fresh
  snapshot confirmation, capability education, and stable retry identity.
- React Native Codegen regenerates successfully and the Kotlin implementation compiles against the
  generated `NativeMissionAlarmSpec`.
- 32 app instrumentation tests pass on the Android 37.1 `Projek_AlarmWO` emulator. Bridge tests
  cover an explicit empty snapshot, authoritative persisted Home alarm data, a real active instance,
  stale revision rejection, and process-level duplicate-launch suppression.
- 60 native-core instrumentation regression tests pass on the same emulator after the Home DAO
  additions.
- The standard Android verification matrix passes: lint, debug, internal, benchmark, minified
  release/R8, benchmark test APK, and native-core JVM tests.
- A bundled benchmark APK was installed without Metro. Visual inspection at 1080×2400 confirmed the
  editable layout, selected schedule/day/mission states, and enabled Save action. A UI-driven Save
  persisted a real disabled alarm to Room, returned to Home, and reopened with the stored schedule,
  time, label, mission, and target.

## Remaining M3 increments

1. Add direct capability request/settings actions plus explicit delete and dirty-exit confirmation.
2. Add adaptive Home/History/Settings navigation and native-backed history/settings snapshots.
3. Complete loading/empty/error/conflict/pending states, accessibility checks, representative layout
   evidence, and font-scale qualification for the screens delivered in M3.

Audio preview and the licensed `classic.ogg` import remain deferred by product-owner decision. The
system alarm-tone fallback remains available to runtime and does not count as packaged-audio evidence.
