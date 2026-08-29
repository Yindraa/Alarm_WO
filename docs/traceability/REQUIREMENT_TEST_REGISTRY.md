# Requirement-to-Test Registry

**Status:** Active skeleton  
**Created:** 2026-08-29

This registry links accepted requirements to executable tests or qualification evidence. `Planned` is not equivalent to verified; P0 requirements require negative and recovery evidence before release.

| Requirement / decision | Verification | Current evidence | Status | Target |
|---|---|---|---|---|
| IMP-ADR-003 — production source isolated from spike | Repository/source inspection | Separate `mobile` and `spikes/mobile-feasibility` trees | Pass | M0 |
| IMP-ADR-004 — moderate module boundaries | Gradle project/build inspection | `app` plus `native-core`, `mission-camera`, `test-support`, and `benchmark` | Pass (skeleton) | M0 |
| IMP-ADR-005 — tests/API foundation first | Lint, typecheck, Jest, Codegen, Gradle build | Provider-neutral verification scripts | Pass | M0 |
| IMP-ADR-007 — dependency baseline pinned | Lockfile and dynamic-version inspection | Exact direct npm versions and `package-lock.json`; Gradle template versions fixed | Partial | M0 |
| IMP-ADR-008 — test hooks excluded from release | Release dependency graph, DEX and manifest inspection | `test-support` only in debug/internal configurations; release DEX clean | Pass | M0 |
| API typed contract bootstrap | Codegen + JS/native compatibility tests | `getContractInfo`; ready and fail-closed Jest cases | Pass | M0 |
| Offline runtime principle | Release manifest/network dependency inspection | Bundled JS; no backend added; INTERNET retained for debug tooling baseline | Partial | M1/M7 |
| TR platform baseline | Clean builds on developer host and CI | Developer host green; CI pending | Partial | M0 |
| M0 release-like build | R8 assembly and binary inspection | Unsigned minified release APK produced | Pass (unsigned evidence) | M0 |
| TR-DAT-003 / API ID syntax | Kotlin unit tests | Lowercase UUID v4 ID value objects and rejection tests | Pass (domain) | M1 |
| TR-MIS-002–003 | Kotlin unit/property tests | Mission allowlist/config bounds and monotonic progress tests | Pass (domain) | M1 |
| TR-INV-001–006 / TR-INS state subset | Kotlin state-transition tests | Verified-only success, recovery non-success, immutable terminal, emergency result | Pass (domain increment) | M1 |
| TR-ALM-008 / TR-DAT-004 recurrence identity | Kotlin boundary tests | All weekday masks, DST gap/overlap, timezone change, and stable dedupe identity | Pass (domain) | M1 |
| M1 remaining domain/data state invariants | Kotlin unit/property tests | Enable recurrence/outbox transaction passes; effect runner and device-protected mirror execution remain | In Progress | M1 |
| M1 Room V1 and migration | Instrumentation/schema tests | All 10 canonical tables exported; production-open schema validation, critical query plans, transactional round-trip, and negative constraints pass on API 37 | Pass (canonical V1) | M1 |
| TR-INV-003 / TR-INS-001 duplicate trigger | Room transaction/instrumentation | Duplicate occurrence delivery returns one persisted instance; cancelled occurrence rolls back | Pass (canonical DB) | M1 |
| TR-INV-004–007 persistence guards | SQLite negative tests | Progress decrease rejected and SUCCESS requires completed mission | Pass (canonical DB) | M1 |
| M1 command idempotency/revision | Room transaction tests | Same command replay, conflicting reuse rejection, optimistic revision, atomic receipt, and rollback pass | Pass (disabled draft) | M1 |
| TR-INV-009 transactional outbox lifecycle | Room instrumentation tests | Owner-scoped lease/ack, scheduled retry, attempt count, and expired-lease reclaim pass | Pass (persistence) | M1 |
| QR draft readiness | SQLite negative test | Unregistered QR may be stored disabled; database rejects enable until native registration fields exist | Pass | M1 |
| API save/editor vertical | Codegen, Jest, Android bridge instrumentation | Contract-version injection, JS validation, persistent save/query DTO round-trip, and stable idempotency error mapping pass | Pass (disabled draft) | M1 |
| TR-ALM-002–004 / TR-INV-009 enable vertical | Room transaction, Codegen, Jest, Android bridge instrumentation | Fresh exact-alarm gate; current-zone next occurrence; atomic enabled state, occurrence, two effects, and receipt; replay, stale revision, expired one-time, QR-not-registered, and capability-denied paths pass on API 37 | Pass (desired state) | M1 |
| M2 alarm runtime safety | Physical-device trigger/recovery matrix | Not implemented; devices unassigned | Planned | M2 |
| M4 Math terminal path | E2E trigger-to-history evidence | Not implemented | Planned | M4 |
| M5 QR security path | Native scanner/security tests | Not implemented | Planned | M5 |
| M6/M8 Push-up qualification | Held-out participant/device metrics | Model qualification pending | Planned | M6/M8 |

Detailed test IDs from the accepted testing strategy will be added to each implementation work item when its vertical slice enters development.
