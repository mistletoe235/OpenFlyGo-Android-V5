#!/usr/bin/env python3
"""Patch the two MSDK 5.18 ARM64 Simulator frequency-divisor constants in an APK."""

from __future__ import annotations

import argparse
import struct
import zipfile
from pathlib import Path


DJI_LIBRARY = "lib/arm64-v8a/libdjisdk_jni.so"
OLD_MOV_W9_30 = bytes.fromhex("c9038052")
FOLLOWING_STORES = (
    bytes.fromhex("e93b0039"),  # strb w9, [sp, #0xe]
    bytes.fromhex("e9cb0039"),  # strb w9, [sp, #0x32]
)


def mov_w9_immediate(value: int) -> bytes:
    if value not in range(0x10000):
        raise ValueError(f"AArch64 MOV immediate is out of range: {value}")
    return struct.pack("<I", 0x52800000 | (value << 5) | 9)


def patch_library(data: bytes, requested_hz: int) -> tuple[bytes, int]:
    if requested_hz < 2 or requested_hz > 150 or 600 % requested_hz != 0:
        raise ValueError("requested Hz must be in 2..150 and divide 600 exactly")
    divisor = 600 // requested_hz
    replacement = mov_w9_immediate(divisor)
    patched = data
    matches = 0
    for following_store in FOLLOWING_STORES:
        pattern = OLD_MOV_W9_30 + following_store
        count = patched.count(pattern)
        if count != 1:
            raise RuntimeError(
                f"expected exactly one MSDK 5.18 pattern {pattern.hex()}, found {count}"
            )
        patched = patched.replace(pattern, replacement + following_store, 1)
        matches += count
    return patched, matches


def is_signature_entry(name: str) -> bool:
    upper = name.upper()
    if not upper.startswith("META-INF/"):
        return False
    return upper == "META-INF/MANIFEST.MF" or upper.endswith((".SF", ".RSA", ".DSA", ".EC"))


def patch_apk(input_apk: Path, output_apk: Path, requested_hz: int) -> None:
    output_apk.parent.mkdir(parents=True, exist_ok=True)
    found_library = False
    with zipfile.ZipFile(input_apk, "r") as source, zipfile.ZipFile(output_apk, "w") as target:
        for info in source.infolist():
            if is_signature_entry(info.filename):
                continue
            data = source.read(info.filename)
            if info.filename == DJI_LIBRARY:
                data, matches = patch_library(data, requested_hz)
                if matches != 2:
                    raise RuntimeError(f"expected two patched instructions, got {matches}")
                found_library = True
            target.writestr(info, data)
    if not found_library:
        output_apk.unlink(missing_ok=True)
        raise RuntimeError(f"{DJI_LIBRARY} is missing from {input_apk}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--hz", required=True, type=int)
    args = parser.parse_args()
    patch_apk(args.input, args.output, args.hz)
    print(f"patched 2 native instructions for {args.hz} Hz: {args.output}")


if __name__ == "__main__":
    main()
