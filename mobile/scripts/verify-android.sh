#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${JAVA_HOME:-}" ]]; then
  echo "JAVA_HOME must point to JDK 17." >&2
  exit 1
fi

if [[ -z "${ANDROID_HOME:-}" ]]; then
  echo "ANDROID_HOME must point to the Android SDK." >&2
  exit 1
fi

java_major="$(java -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')"
if [[ "$java_major" != "17" ]]; then
  echo "JDK 17 is required; detected major version ${java_major:-unknown}." >&2
  exit 1
fi

cd android
./gradlew \
  :native-core:testDebugUnitTest \
  :native-core:assembleDebugAndroidTest \
  :app:generateCodegenArtifactsFromSchema \
  :app:lintDebug \
  :app:assembleDebug \
  :app:assembleInternal \
  :app:assembleBenchmark \
  :app:assembleRelease \
  :benchmark:assembleBenchmark
