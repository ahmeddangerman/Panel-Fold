#!/usr/bin/env bash
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TASK_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/tmp/android-sdk}}"
TASK_SIGNER="$TASK_SDK/build-tools/35.0.0/apksigner"
TASK_INPUT="${1:-$TASK_ROOT/app/build/outputs/apk/release/app-release-unsigned.apk}"
TASK_OUTPUT="${2:-$TASK_ROOT/releases/tablet-comics-0.6.1-beta.apk}"
TASK_KEYS="$TASK_ROOT/.signing"
test -f "$TASK_INPUT"
test -f "$TASK_KEYS/legacy.keystore"
test -f "$TASK_KEYS/current.keystore"
mkdir -p "$(dirname "$TASK_OUTPUT")"
if ! test -f "$TASK_KEYS/beta.lineage"; then
    "$TASK_SIGNER" rotate --out "$TASK_KEYS/beta.lineage" \
        --old-signer --ks "$TASK_KEYS/legacy.keystore" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android \
        --new-signer --ks "$TASK_KEYS/current.keystore" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android
    chmod 600 "$TASK_KEYS/beta.lineage"
fi
"$TASK_SIGNER" sign --out "$TASK_OUTPUT" --min-sdk-version 26 --rotation-min-sdk-version 28 \
    --v4-signing-enabled false --lineage "$TASK_KEYS/beta.lineage" \
    --ks "$TASK_KEYS/legacy.keystore" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android \
    --next-signer --ks "$TASK_KEYS/current.keystore" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android \
    "$TASK_INPUT"
"$TASK_SIGNER" verify --verbose --print-certs "$TASK_OUTPUT"
