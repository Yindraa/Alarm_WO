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
| M1 domain/data state invariants | Kotlin unit/property tests | Not implemented | Planned | M1 |
| M1 Room V1 and migration | Instrumentation/schema tests | Not implemented | Planned | M1 |
| M1 command idempotency/revision | Positive, retry, and stale-revision tests | Not implemented | Planned | M1 |
| M2 alarm runtime safety | Physical-device trigger/recovery matrix | Not implemented; devices unassigned | Planned | M2 |
| M4 Math terminal path | E2E trigger-to-history evidence | Not implemented | Planned | M4 |
| M5 QR security path | Native scanner/security tests | Not implemented | Planned | M5 |
| M6/M8 Push-up qualification | Held-out participant/device metrics | Model qualification pending | Planned | M6/M8 |

Detailed test IDs from the accepted testing strategy will be added to each implementation work item when its vertical slice enters development.
