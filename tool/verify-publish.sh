#!/usr/bin/env bash
# Proves the published artifacts work for a consumer, without publishing anywhere: publishes every module and the BOM to
# Maven Local at <version>, then compiles a separate Kotlin Multiplatform project (tool/consumer-check) that depends on
# the BOM alone, on Android, iOS (device and simulator), the JVM and wasm. macOS is needed for the iOS artifacts.
# Usage: tool/verify-publish.sh <version>      for example: tool/verify-publish.sh 1.0.0-localcheck
set -euo pipefail

version="${1:?usage: $0 <version>}"
cd "$(dirname "$0")/.."

# The consumer is its own build, so it needs the Android SDK from the environment, as a user's project would.
if [[ -z "${ANDROID_HOME:-}" && -f local.properties ]]; then
  ANDROID_HOME="$(sed -n 's/^sdk.dir=//p' local.properties)"
  export ANDROID_HOME
fi

./gradlew publishToMavenLocal -PVERSION_NAME="$version"
./gradlew -p tool/consumer-check -Psinew.version="$version" \
  compileKotlinJvm compileKotlinWasmJs compileKotlinIosArm64 compileKotlinIosSimulatorArm64 compileAndroidMain \
  compileTestKotlinJvm
echo "OK: a consumer of the BOM at $version compiles on Android, iOS, JVM and wasm."
