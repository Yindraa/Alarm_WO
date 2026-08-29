# M0 Delivery Readiness

**Status:** In Progress  
**Review date:** 2026-08-29  
**Roadmap source:** [`../roadmap/IMPLEMENTATION_ROADMAP.md`](../roadmap/IMPLEMENTATION_ROADMAP.md)

## Outcome so far

The production React Native 0.87 Android application has been scaffolded independently from the feasibility spike. Its typed native contract bootstrap, module boundaries, build separation, test foundation, and provider-neutral verification entry points are operational on the developer host.

M0 is not yet closed because repository/CI administration, permanent identity, production signing ownership, device access, and CV data governance require named external owners.

## Delivery checklist

| Item | Evidence | Status |
|---|---|---|
| Independent production scaffold | [`../../mobile`](../../mobile) | Pass |
| RN 0.87 / Node / JDK baseline pinned | `package-lock.json`, `.nvmrc`, `.java-version` | Pass |
| Typed native bootstrap | `NativeMissionAlarm.ts`, generated Codegen, Kotlin module, Jest tests | Pass |
| Moderate module boundaries | `app` plus `native-core`, `mission-camera`, `test-support`, and `benchmark` | Pass for M0 skeleton |
| Build separation | Debug/internal include test support; benchmark/release do not | Pass |
| Provider-neutral CI commands | `npm run verify:js`, `npm run verify:android` | Pass |
| Dependency locks and SBOM | npm/Gradle lockfiles created; automated SBOM artifact job awaits CI | Partial |
| Clean CI checkout | CI provider and repository unavailable | Pending external decision |
| Permanent application ID | Currently `com.missionalarm.app` | Pending product-owner approval |
| Production signing | Release remains unsigned; no production key in source | Pending ownership decision |
| Play declarations/administration | Exact alarm, FGS, full-screen intent evidence not yet assigned | Pending owner |
| Physical device P1–P5 plan | Inventory/access owner not supplied | Pending owner |
| CV consent/data plan | Custodian, storage, retention, and recruitment owner not supplied | Pending owner |
| License metadata registry | Product owner confirms licenses received; per-asset metadata is still required | Pending catalog |
| Requirement/test registry | [`../traceability/REQUIREMENT_TEST_REGISTRY.md`](../traceability/REQUIREMENT_TEST_REGISTRY.md) | Pass for skeleton |

## Verified host evidence

On 2026-08-29, the following passed:

- ESLint and strict TypeScript typecheck.
- Jest: two bootstrap tests, including incompatible/unavailable native fail-closed behavior.
- React Native Codegen for `NativeMissionAlarm`.
- Android lint/build path; `debug`, `internal`, minified `benchmark`, minified `release`, and Macrobenchmark test APK assembly.
- Release manifest inspection: `usesCleartextTraffic=false`.
- Release DEX inspection: no `com.missionalarm.testsupport` package.
- Release APK includes `assets/index.android.bundle`; it does not depend on Metro at runtime.

Generated APK checksums for this local evidence run:

| Artifact | SHA-256 |
|---|---|
| `app-debug.apk` | `73eae108f2d056ebe35fc479c4c4db97550e8ed2a71130ca05b27ad5e478a745` |
| `app-internal.apk` | `3792ee89bc16dd783a31ed8b111563dde83f3d9400159fa1eb1ca14b8ff0c471` |
| `app-benchmark.apk` | `874288d821b09bca3213fbc9bf8e3841af7cd3d9e57ce678c5b4ed122382b53c` |
| `app-release-unsigned.apk` | `0fb4a8a2b78dd2c35a8d99de7798a7abf8181e44ba517aa07fc01ecb84fc15de` |
| `benchmark-benchmark.apk` | `83e52c838006f17ec07b117b2f8ed722af38ff2fd71919b47d02e6716fea9212` |

These local artifacts are evidence only and are not release candidates.

## Dependency and warning disposition

- Exact npm resolution is stored in `package-lock.json`; dependencies are installed with `npm ci` in automation.
- `npm audit` reports nine transitive high findings rooted in Metro's build-time `image-size@1.2.1`. No compatible patched path is currently available without a breaking React Native downgrade. Metro may process trusted repository assets only; findings remain open and require pre-release re-evaluation.
- Current RN 0.87/AGP 9 integration emits deprecated DSL/variant API warnings. Builds pass, but this is an upgrade-watch item before AGP 10.
- `react-native-safe-area-context` emits upstream legacy API warnings. The selected 5.9.1 version compiles against RN 0.87; monitor upstream New Architecture cleanup.

## Decisions required to close M0

| Decision | Recommended default | Required by |
|---|---|---|
| Permanent application ID | Approve `com.missionalarm.app`, or provide organization-owned reverse domain | Before Play app creation |
| Source control and CI | Private GitHub repository + GitHub Actions | Before clean-checkout CI evidence |
| Signing ownership | Google Play App Signing; organization-owned upload key stored in CI secret manager | Before first signed internal artifact |
| Device qualification owner | Assign P1–P5 inventory/procurement owner | Before M2 physical smoke |
| CV data governance owner | Assign consent, secure storage, retention/deletion, and recruitment custodian | Before participant capture |
| Team capacity | Name role allocations from roadmap Section 6 | Before committing calendar dates |

## M0 backlog

| ID | Work item | Status | Owner / next action |
|---|---|---|---|
| M0-ENG-001 | Production scaffold and pinned toolchain | Done | Engineering |
| M0-ENG-002 | Typed native bootstrap and fail-closed UI | Done | Engineering |
| M0-ENG-003 | Module and build-variant separation | Done | Engineering |
| M0-ENG-004 | Macrobenchmark target/test module | Done; physical run pending | QA/device owner |
| M0-ENG-005 | Reusable lint/test/Codegen/build checks | Done locally | CI owner must wire provider |
| M0-GOV-001 | Permanent package identity | Waiting decision | Product owner |
| M0-GOV-002 | Repository and protected CI | Waiting decision | Repository administrator |
| M0-GOV-003 | Play App Signing/upload-key custody | Waiting decision | Release administrator |
| M0-GOV-004 | Play declaration evidence drafts | Not started | Policy/release owner |
| M0-DEV-001 | P1–P5 device access inventory | Not started | QA/device owner |
| M0-CV-001 | Consent, storage, retention/deletion plan | Not started | CV/privacy owner |
| M0-LIC-001 | Populate per-asset license/checksum rows | Not started | Product/license owner |

## M0 exit recommendation

**NO-GO to close M0 and enter M1 feature implementation, but implementation preparation may continue.** Engineering evidence for the local foundation is green. M1 feature work should start only after permanent identity and repository/CI choices are confirmed; physical-device and CV governance assignments may proceed in parallel but remain release-critical.
