#!/usr/bin/env python3
"""Check the source-level BLE interface shared by firmware and phone."""

import pathlib
import re
import sys
import uuid


root = pathlib.Path(sys.argv[1])
firmware = (root / "firmware/src/main.cpp").read_text()
client = (root / "companion/app/src/main/java/com/quotawatch/ble/BleClient.kt").read_text()
quota = (root / "companion/app/src/main/java/com/quotawatch/api/QuotaData.kt").read_text()


def one(pattern: str, source: str, label: str) -> str:
    matches = re.findall(pattern, source, re.MULTILINE)
    if len(matches) != 1:
        raise AssertionError(f"expected one {label}, found {len(matches)}")
    return matches[0]


identifiers = {}
for name in ("SERVICE_UUID", "QUOTA_CHAR_UUID"):
    fw_id = one(rf'^#define\s+{name}\s+"([^"]+)"', firmware, f"firmware {name}")
    phone_id = one(
        rf'val\s+{name}:\s+UUID\s*=\s*UUID\.fromString\("([^"]+)"\)',
        client,
        f"Android {name}",
    )
    identifiers[name] = uuid.UUID(fw_id)
    assert identifiers[name] == uuid.UUID(phone_id), f"{name} differs across BLE peers"
assert identifiers["SERVICE_UUID"] != identifiers["QUOTA_CHAR_UUID"], "BLE IDs must differ"

phone_row = one(r'fun\s+toBleString\(\):\s*String\s*=\s*"([^"]+)"', quota, "phone row")
assert phone_row == "$name:$used:$limit:$unit", "phone row field order changed"
firmware_row = one(r'sscanf\(line,\s*"([^"]+)"', firmware, "firmware row")
assert firmware_row == "%15[^:]:%f:%f:%7s", "firmware row field order changed"
assert 'joinToString("\\n") { it.toBleString() }' in quota, "phone line delimiter changed"
assert 'strtok_r(buf, "\\n", &saveptr)' in firmware, "firmware line delimiter changed"

print("BLE UUIDs, row fields, and line delimiter agree across firmware and phone")
