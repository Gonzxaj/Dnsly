#!/usr/bin/env bash
# Installs dist/dnsly.apk on the phone and opens it.
#   docker/install-apk.sh                     → USB debugging
#   docker/install-apk.sh <ip>[:<port>]       → Wi-Fi debugging (adb connect; default port 5555)
# The adb key lives in the dnsly-keys volume, so the phone asks to authorize only once.
# First-time wireless debugging (Android 11+) needs pairing once, from the host or a container:
#   adb pair <ip>:<pairing-port> <code>   (the pairing port/code are in the wireless debugging dialog)
set -euo pipefail
cd "$(dirname "$0")/.."
source docker/common.sh

target="${1:-}"
if [ -n "$target" ]; then
  case "$target" in
    *:*) connect_addr="$target" ;;
    *)   connect_addr="$target:5555" ;;
  esac
  docker run --rm \
    -v "$PWD/dist":/dist:ro \
    -v dnsly-keys:/root/.android \
    "$IMAGE" \
    sh -c "adb start-server >/dev/null \
      && adb connect $connect_addr \
      && adb -s $connect_addr wait-for-device \
      && adb -s $connect_addr install -r /dist/dnsly.apk \
      && adb -s $connect_addr shell am start -n app.dnsly/.MainActivity"
else
  docker run --rm --privileged \
    -v /dev/bus/usb:/dev/bus/usb \
    -v "$PWD/dist":/dist:ro \
    -v dnsly-keys:/root/.android \
    "$IMAGE" \
    sh -c 'adb start-server >/dev/null && adb wait-for-usb-device && adb install -r /dist/dnsly.apk && adb shell am start -n app.dnsly/.MainActivity'
fi
