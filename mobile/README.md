# Mission Alarm mobile

Production Android application for Mission Alarm. This source was scaffolded independently from the feasibility spike.

## Baseline

- React Native 0.87.0 with TypeScript and the New Architecture
- Node.js 24.19.0 (`.nvmrc`)
- JDK 17 (`.java-version`)
- Android `minSdk 24`, `targetSdk 36`, `compileSdk 37`
- Provisional application ID: `com.missionalarm.app`

The application ID must be approved or changed before any Play Console upload. The generated iOS template is retained, but iOS is outside the MVP build and CI scope.

## Install and verify

```sh
npm ci
npm run verify:js
JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk npm run verify:android
```

`verify:android` runs Codegen, Android lint, and all application assemblies. It intentionally does not configure a production signing key.

## Build separation

| Variant | Purpose | Cleartext | Test support | Signing |
|---|---|---:|---:|---|
| `debug` | Local Metro development | Yes | Yes | Debug key |
| `internal` | Internal fault/test-enabled build | Yes | Yes | Debug key |
| `benchmark` | Release-like measurement target | No | No | Debug key, non-release |
| `release` | Production surface | No | No | Unsigned until secure CI signing is configured |

Supporting boundaries are `native-core`, `mission-camera`, debug/internal-only `test-support`, and the separate `benchmark` test application. Macrobenchmark measurements are qualification evidence only when run on an approved physical device.

## Native contract bootstrap

`src/native/specs/NativeMissionAlarm.ts` is the Codegen source of truth. Startup calls the typed `getContractInfo` method and fails closed when native/client contract versions are incompatible. The complete Phase 6 contract will be added incrementally from M1.

## Security note

`npm audit` currently reports nine transitive high-severity advisories through Metro's build-time `image-size@1.2.1`. The dependency receives only trusted repository assets in this project; untrusted image input must never be processed by Metro. There is no compatible patched resolution without a breaking React Native downgrade, so the finding is tracked for upstream remediation and must be reviewed again before release. Do not use `npm audit fix --force`.
