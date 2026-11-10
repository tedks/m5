#!/usr/bin/env bash
set -euo pipefail

runfiles="${TEST_SRCDIR:?}/${TEST_WORKSPACE:?}"
stage="$(mktemp -d "${TEST_TMPDIR:?}/ble-wire.XXXXXX")"
trap 'rm -rf -- "$stage"' EXIT
files=(
  companion/app/src/main/java/com/quotawatch/api/QuotaData.kt
  companion/app/src/main/java/com/quotawatch/ble/BleClient.kt
  firmware/src/main.cpp
  tools/ble_wire_contract.py
)
for file in "${files[@]}"; do
  mkdir -p "$stage/${file%/*}"
  cp "$runfiles/$file" "$stage/$file"
done
python3 "$stage/tools/ble_wire_contract.py" "$stage"
