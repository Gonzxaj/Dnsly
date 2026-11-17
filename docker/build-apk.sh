#!/usr/bin/env bash
# Runs the unit tests and builds dist/dnsflow.apk (debug).
exec "$(dirname "$0")/gradle.sh" testDebugUnitTest assembleDebug
