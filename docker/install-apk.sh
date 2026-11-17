#!/usr/bin/env bash
# Installs dist/dnsflow.apk on the USB-connected phone (USB debugging on) and opens it.
# The adb key lives in the dnsflow-keys volume, so the phone asks to authorize only once.
set -euo pipefail
cd "$(dirname "$0")/.."
source docker/common.sh
docker run --rm --privileged \
  -v /dev/bus/usb:/dev/bus/usb \
  -v "$PWD/dist":/dist:ro \
  -v dnsflow-keys:/root/.android \
  "$IMAGE" \
  sh -c 'adb start-server >/dev/null && adb wait-for-usb-device && adb install -r /dist/dnsflow.apk && adb shell am start -n app.dnsflow/.MainActivity'
