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


def check(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


identifiers = {}
for name in ("SERVICE_UUID", "QUOTA_CHAR_UUID"):
    fw_id = one(rf'^#define\s+{name}\s+"([^"]+)"', firmware, f"firmware {name}")
    phone_id = one(
        rf'val\s+{name}:\s+UUID\s*=\s*UUID\.fromString\("([^"]+)"\)',
        client,
        f"Android {name}",
    )
    identifiers[name] = uuid.UUID(fw_id)
    check(identifiers[name] == uuid.UUID(phone_id), f"{name} differs across BLE peers")
check(identifiers["SERVICE_UUID"] != identifiers["QUOTA_CHAR_UUID"], "BLE IDs must differ")

phone_row = one(r'^\s*fun\s+toBleString\(\):\s*String\s*=\s*"([^"]+)"', quota, "phone row")
check(phone_row == "$name:$used:$limit:$unit", "phone row field order changed")
firmware_row = one(r'^\s*if\s*\(\s*sscanf\(line,\s*"([^"]+)"', firmware, "firmware row")
check(firmware_row == "%15[^:]:%f:%f:%7s", "firmware row field order changed")
phone_lines = one(
    r'^\s*quotas\.joinToString\("([^"]+)"\)\s*\{\s*it\.toBleString\(\)\s*\}\.toByteArray\(Charsets\.UTF_8\)',
    quota,
    "phone line joiner",
)
check(phone_lines == r"\n", "phone line delimiter changed")
firmware_lines = one(
    r'^\s*char\*\s+line\s*=\s*strtok_r\(buf,\s*"([^"]+)"',
    firmware,
    "firmware line tokenizer",
)
check(firmware_lines == r"\n", "firmware line delimiter changed")
for call in ("createService(SERVICE_UUID)", "addServiceUUID(SERVICE_UUID)",
             "getService(SERVICE_UUID)", "getCharacteristic(QUOTA_CHAR_UUID)"):
    source = firmware if call.startswith(("create", "add")) else client
    check(call in source, f"BLE ID use changed: {call}")
check(
    re.search(r'createCharacteristic\(\s*QUOTA_CHAR_UUID\s*,', firmware) is not None,
    "firmware characteristic use changed",
)
check(
    "setServiceUuid(ParcelUuid(SERVICE_UUID))" in client,
    "Android scan service use changed",
)

print("BLE UUIDs, row fields, and line delimiter agree across firmware and phone")
