#!/usr/bin/env bash
# Runs the unit tests and builds dist/dnsly.apk (debug).
exec "$(dirname "$0")/gradle.sh" testDebugUnitTest assembleDebug
