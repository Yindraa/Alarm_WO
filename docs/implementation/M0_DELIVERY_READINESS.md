# M0 Delivery Readiness

**Status:** Accepted for personal/portfolio scope
**Review date:** 2026-08-29  
**Roadmap source:** [`../roadmap/IMPLEMENTATION_ROADMAP.md`](../roadmap/IMPLEMENTATION_ROADMAP.md)

## Outcome so far

The production React Native 0.87 Android application has been scaffolded independently from the feasibility spike. Its typed native contract bootstrap, module boundaries, build separation, test foundation, and provider-neutral verification entry points are operational on the developer host.

M0 is closed for the current personal/portfolio scope. The permanent package identity is approved and the workspace is connected to its GitHub repository. Public-store administration, production signing, formal team allocation, multi-device qualification, and third-party CV participant governance are deferred until distribution scope changes.

## Delivery checklist

| Item | Evidence | Status |
|---|---|---|
| Independent production scaffold | [`../../mobile`](../../mobile) | Pass |
| RN 0.87 / Node / JDK baseline pinned | `package-lock.json`, `.nvmrc`, `.java-version` | Pass |
| Typed native bootstrap | `NativeMissionAlarm.ts`, generated Codegen, Kotlin module, Jest tests | Pass |
| Moderate module boundaries | `app` plus `native-core`, `mission-camera`, `test-support`, and `benchmark` | Pass for M0 skeleton |
| Build separation | Debug/internal include test support; benchmark/release do not | Pass |
| Provider-neutral CI commands | `npm run verify:js`, `npm run verify:android` | Pass |
| Dependency locks and SBOM | npm/Gradle lockfiles created; SBOM automation deferred until needed | Pass for current scope |
| Source control and CI | GitHub origin connected; GitHub Actions workflow added | Pass; first remote run pending |
| Permanent application ID | `com.missionalarm.app` approved by product owner on 2026-08-29 | Pass |
| Production signing | Release remains unsigned; Play signing deferred | Deferred, non-blocking |
| Play declarations/administration | Public Google Play distribution is outside current scope | Deferred, non-blocking |
| Physical device plan | Personal device will be the functional reference device | Accepted for current scope |
| CV consent/data plan | Initial development uses owner/self test data only; no third-party collection | Accepted for current scope |
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

## Deferred distribution decisions

| Decision | Current disposition | Reopen when |
|---|---|---|
| Production signing and Play administration | Deferred | Public/internal Play distribution is requested |
| P1–P5 device qualification matrix | Replaced by personal reference device for development | Broad device compatibility is claimed |
| Third-party CV participant governance | No third-party data collection | Anyone other than the owner contributes recordings/data |
| Formal team capacity/calendar | Single-owner portfolio delivery; no committed release date | A delivery deadline or team is introduced |

## M0 backlog

| ID | Work item | Status | Owner / next action |
|---|---|---|---|
| M0-ENG-001 | Production scaffold and pinned toolchain | Done | Engineering |
| M0-ENG-002 | Typed native bootstrap and fail-closed UI | Done | Engineering |
| M0-ENG-003 | Module and build-variant separation | Done | Engineering |
| M0-ENG-004 | Macrobenchmark target/test module | Done; physical run pending | QA/device owner |
| M0-ENG-005 | Reusable lint/test/Codegen/build checks | Done locally and workflow added | Confirm first GitHub run |
| M0-GOV-001 | Permanent package identity | Done | `com.missionalarm.app` approved |
| M0-GOV-002 | Repository and CI definition | Done | GitHub origin + Actions workflow |
| M0-GOV-003 | Play App Signing/upload-key custody | Deferred | Reopen for Play distribution |
| M0-GOV-004 | Play declaration evidence drafts | Deferred | Reopen for Play distribution |
| M0-DEV-001 | Device access | Accepted for scope | Use owner's Android device |
| M0-CV-001 | CV data handling | Accepted for scope | Owner/self data only |
| M0-LIC-001 | Populate imported asset metadata | Ongoing | Complete rows when assets are imported |

## M0 exit recommendation

**GO for M1.** The engineering foundation is green and the two current blockers—permanent package identity and repository destination—are resolved. Deferred public-distribution work does not block personal/portfolio feature development. Runtime reliability, fail-safe behavior, and testing on the owner's physical Android device remain required because they affect the application itself.
