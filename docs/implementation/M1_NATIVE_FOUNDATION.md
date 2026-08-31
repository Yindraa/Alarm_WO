# M1 Native Foundation Vertical

**Status:** Complete — implementation evidence green
**Started:** 2026-08-29
**Scope:** Personal/portfolio application

## Current slice

The current M1 increment establishes the Kotlin domain model, Room V1 persistence, and the first
crash-safe alarm scheduling command slice.

Implemented:

- lowercase UUID v4 identifiers for alarm, occurrence, instance, and command identity;
- positive monotonic revision value;
- injectable wall and monotonic clock ports;
- mission type/configuration validation for Push-up, Math, and QR;
- monotonic, bounded, idempotent verified progress;
- alarm-instance runtime states and explicit transition rules;
- success only from completed verified progress;
- recovery that cannot imply success;
- immutable terminal result and emergency-dismissal semantics;
- deterministic occurrence generation across all weekday masks, DST gaps/overlaps, and timezone changes;
- stable occurrence deduplication identity derived from alarm ID, revision, and scheduled instant;
- complete canonical Room V1 with 10 tables: alarm/config, occurrence, instance/mission,
  Math questions, immutable history, runtime effects, command receipts, and diagnostics;
- transactional disabled-draft insertion and re-query;
- atomic idempotent occurrence-to-instance creation for duplicate receiver delivery;
- database-level schedule, mission, runtime-state, monotonic-progress, verified-success,
  immutable-history, effect-state, and receipt invariants;
- deterministic command/effect identity primitives and receipt-expiry cleanup;
- transactional disabled-draft repository with canonical SHA-256 request hashing,
  seven-day receipts, replay acknowledgement, optimistic revision, and atomic rollback;
- transactional `enableAlarm` repository workflow with fresh expected-revision enforcement,
  current-timezone recurrence, stable occurrence identity, QR-readiness rejection, and expired
  one-time rejection;
- transactional enabled-alarm edit, disable, and delete workflows with optimistic revision,
  command replay, future-occurrence cancellation, and direct-boot mirror reconciliation effects;
- enabled edit atomically marks superseded occurrences `CANCELLED`, replaces configuration and
  mission snapshot source, and creates the next occurrence without temporarily disabling the alarm;
- delete rejects alarms with non-terminal instances, while retained occurrence/instance/history
  records are detached through the schema's non-cascading audit relationships;
- atomic alarm + `PENDING_OS` occurrence + `SCHEDULE_OCCURRENCE` +
  `SYNC_DIRECT_BOOT_MIRROR` outbox + command-receipt commit before Android side effects;
- outbox claim, owner-scoped acknowledgement, scheduled retry, and expired-lease reclaim;
- bounded `SCHEDULE_OCCURRENCE` effect execution with strict versioned-payload validation,
  transient exponential backoff, capability blocking, and terminal dead-letter classification;
- Android exact scheduling through an explicit immutable `PendingIntent` whose data URI is keyed by
  occurrence ID, preserving the same OS identity across duplicate execution and cancellation;
- cancellation effects execute before replacement scheduling, retry with bounded backoff, and fail
  closed so a replacement is not reported healthy while an older OS identity may remain;
- persisted schedule health changes to `SCHEDULED_OS` only after the Android adapter succeeds;
- device-protected `mission_alarm_boot.db` Room V1 with allowlisted `boot_schedule` mirror and
  idempotent `boot_journal`; user labels, QR material, Math answers, CV data, and history are excluded;
- filtered `SYNC_DIRECT_BOOT_MIRROR` execution with strict versioned payload validation, complete
  canonical-state rebuild, stale-row removal, monotonic mirror revision, retry, and dead-lettering;
- Direct Boot-aware receivers restore active exact alarms on locked boot and reconcile canonical
  scheduling/mirror effects after unlock, normal boot, or package replacement;
- the exact-alarm receiver validates action and occurrence identity against the device-protected
  mirror, marks it fired, and persists one idempotent `TRIGGERED` journal entry;
- unregistered QR configuration may persist only as a disabled draft and cannot be enabled;
- exported Room schema committed for future migration verification;
- exported-schema validation through the production Room builder;
- required due-occurrence and due-effect indexes verified through `EXPLAIN QUERY PLAN`;
- 15 deterministic JVM tests and 37 passing native-core emulator instrumentation tests;
- JVM tests and Android-test APK compilation included in the standard Android verification command.
- Codegen-verified `saveAlarmConfiguration`, `enableAlarm`, `disableAlarm`, `deleteAlarm`, and
  `getAlarmEditorSnapshot`
  TurboModule methods;
- allowlisted native DTO mapping, stable contract error codes, and fresh Android capability inspection;
- exact-alarm capability blocks activation while notification/full-screen remain non-blocking fallback
  capabilities and camera remains just-in-time for camera mission use;
- persistent save/enable/query bridge round-trip verified on an API 37 emulator.

## Verification evidence

- `:native-core:testDebugUnitTest` passes all 15 domain tests.
- `:native-core:assembleDebugAndroidTest` compiles the instrumentation suite.
- `:native-core:connectedDebugAndroidTest` passes 37 tests on the Android 37 `AlarmWO_API_37` emulator,
  including OS-success acknowledgement, stable retry identity, capability blocking, malformed-payload
  dead-lettering, retry delay, bounded retry exhaustion, atomic edit/disable/delete,
  cancel-before-reschedule ordering, mirror rebuild/retry, schema constraints, and idempotent trigger
  journaling.
- `:app:connectedDebugAndroidTest` passes 6 TurboModule persistence/capability/lifecycle/error-contract
  tests on the same emulator.
- JavaScript lint/typecheck and 9 Jest tests pass, including enable/disable/delete wrapper validation
  and contract-version injection.
- Canonical and Direct Boot Room schema V1 files are exported under
  `mobile/android/native-core/schemas`.

## Handoff to M2

The atomic trigger/journal handoff has moved to
[`M2_RELIABLE_ALARM_CORE.md`](M2_RELIABLE_ALARM_CORE.md). Mission routing, packaged alarm audio,
and physical locked-boot qualification continue there.

Android exact scheduling, locked-boot restoration, and pre-unlock trigger journaling are operational.
Journal-to-instance import, foreground audio, Alarm Host presentation, emergency dismissal, and
Direct Boot emergency recovery are now operational M2 increments. Mission routing, packaged sound,
and physical-device qualification remain M2 work.
