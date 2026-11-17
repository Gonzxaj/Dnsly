#!/usr/bin/env bash
# Runs Gradle tasks inside Docker: docker/gradle.sh testDebugUnitTest assembleDebug
# The source is copied into the container, so no build output lands in the repo;
# only the debug APK is copied out to dist/ (owned by you).
# Gradle cache: reuses go-data-android's go-data-android-gradle volume, so shared
# dependencies (AGP, Kotlin, AndroidX) are downloaded and stored once for both projects.
# The small dnsly-keys volume keeps the debug keystore: a new one each run would
# force uninstalling the app before every update.
set -euo pipefail
cd "$(dirname "$0")/.."
source docker/common.sh
build_image
mkdir -p dist
docker run --rm \
  -v "$PWD":/src:ro \
  -v "$PWD/dist":/out \
  -v go-data-android-gradle:/gradle-cache \
  -v dnsly-keys:/root/.android \
  -e GRADLE_USER_HOME=/gradle-cache \
  "$IMAGE" \
  sh -c "mkdir /work \
    && tar -C /src --exclude=./dist --exclude=./.git --exclude=./.gradle \
         --exclude='*/target' --exclude='*/build' -cf - . | tar -C /work -xf - \
    && cd /work/android && ./gradlew --no-daemon --console=plain $* \
    && if [ -f app/build/outputs/apk/debug/app-debug.apk ]; then \
         install -m644 -o $(id -u) -g $(id -g) app/build/outputs/apk/debug/app-debug.apk /out/dnsly.apk \
         && echo 'APK: dist/dnsly.apk'; fi"
