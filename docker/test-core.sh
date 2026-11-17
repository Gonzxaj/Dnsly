#!/usr/bin/env bash
# Formats check, lints and tests the Rust core inside Docker.
# Source is mounted read-only; build output lives in the container and is deleted with it.
set -euo pipefail
cd "$(dirname "$0")/.."
source docker/common.sh
build_image
docker run --rm -v "$PWD/core":/src:ro -e CARGO_TARGET_DIR=/tmp/target "$IMAGE" sh -c \
  "cargo fmt --check && cargo clippy --all-targets -- -D warnings && cargo test"
