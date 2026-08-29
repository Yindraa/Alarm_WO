# M1 Native Foundation Vertical

**Status:** In Progress
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
- atomic alarm + `PENDING_OS` occurrence + `SCHEDULE_OCCURRENCE` +
  `SYNC_DIRECT_BOOT_MIRROR` outbox + command-receipt commit before Android side effects;
- outbox claim, owner-scoped acknowledgement, scheduled retry, and expired-lease reclaim;
- unregistered QR configuration may persist only as a disabled draft and cannot be enabled;
- exported Room schema committed for future migration verification;
- exported-schema validation through the production Room builder;
- required due-occurrence and due-effect indexes verified through `EXPLAIN QUERY PLAN`;
- 15 deterministic JVM tests and 18 passing native-core emulator instrumentation tests;
- JVM tests and Android-test APK compilation included in the standard Android verification command.
- Codegen-verified `saveAlarmConfiguration`, `enableAlarm`, and `getAlarmEditorSnapshot`
  TurboModule methods;
- allowlisted native DTO mapping, stable contract error codes, and fresh Android capability inspection;
- exact-alarm capability blocks activation while notification/full-screen remain non-blocking fallback
  capabilities and camera remains just-in-time for camera mission use;
- persistent save/enable/query bridge round-trip verified on an API 37 emulator.

## Verification evidence

- `:native-core:testDebugUnitTest` passes all 15 domain tests.
- `:native-core:assembleDebugAndroidTest` compiles the instrumentation suite.
- `:native-core:connectedDebugAndroidTest` passes 18 tests on the Android 37 `AlarmWO_API_37` emulator.
- `:app:connectedDebugAndroidTest` passes 5 TurboModule persistence/capability/error-contract tests on the same emulator.
- JavaScript lint/typecheck and 7 Jest tests pass, including enable wrapper validation and
  contract-version injection.
- Room schema V1 is exported under `mobile/android/native-core/schemas`.

## Next increments

1. Add repository support for edit of enabled alarms, disable/delete, occurrence replacement,
   and cancellation effects in one transaction.
2. Add bounded outbox failure classification and an Android effect-runner adapter.
3. Add the device-protected boot mirror/journal after the canonical repository path is stable.
4. Build the first Alarm Editor UI on the verified save/query contract.

Android OS scheduling, audio, receivers, and active alarm runtime remain M2 work.
