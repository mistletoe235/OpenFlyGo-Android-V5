#!/usr/bin/env python3
"""Patch DJI MSDK 5.18's RC video LocalSocket name for DJI RC 2.

The public MSDK constructs the short std::string ``dji_fpv`` inside
MSDKDataLinkServiceMgr::CommonInitialize().  DJI Fly's RC331 profile instead
uses ``fpv_sock``.  This patch changes only that inline string construction and
refuses to touch an unexpected binary.
"""

from pathlib import Path
import struct
import sys


def words(values: list[int]) -> bytes:
    return struct.pack("<" + "I" * len(values), *values)


# Instructions at MSDKDataLinkServiceMgr::CommonInitialize()+0x230 in 5.18.0.
BEFORE = words([
    0x528001C8,  # mov  w8, #14       (libc++ short-string length 7)
    0x528D4C89,  # mov  w9, #0x6a64   ("dj")
    0xF85B83A0,
    0x381A03A8,
    0x528CCBE8,  # mov  w8, #0x665f
    0xD10183B8,
    0x72ABED29,  # movk w9, #0x5f69, lsl #16 ("i_")
    0x72AECE08,  # movk w8, #0x7670, lsl #16 ("pv")
    0x381A83BF,
    0xB8001309,  # stur w9, [x24, #1]
    0xB81A43A8,  # stur w8, [x29, #-92]
])

AFTER = words([
    0x52800208,  # mov  w8, #16       (libc++ short-string length 8)
    0x528E0CC9,  # mov  w9, #0x7066   ("fp")
    0xF85B83A0,
    0x381A03A8,
    0x528DEE68,  # mov  w8, #0x6f73   ("so")
    0xD10183B8,
    0x72ABEEC9,  # movk w9, #0x5f76, lsl #16 ("v_")
    0x72AD6C68,  # movk w8, #0x6b63, lsl #16 ("ck")
    0x381A83BF,
    0xB8001309,  # stur w9, [x24, #1] ("fpv_")
    0xB81A53A8,  # stur w8, [x29, #-91] ("sock")
])

def main() -> int:
    if len(sys.argv) != 2:
        print(f"usage: {Path(sys.argv[0]).name} LIBDJISDK_JNI_SO", file=sys.stderr)
        return 2
    path = Path(sys.argv[1])
    data = path.read_bytes()
    before_count = data.count(BEFORE)
    after_count = data.count(AFTER)
    if before_count == 0 and after_count == 1:
        print(f"already patched: {path}")
        return 0
    if before_count != 1 or after_count != 0:
        raise SystemExit(
            f"refusing unexpected libdjisdk_jni.so: before={before_count} after={after_count}"
        )
    offset = data.index(BEFORE)
    path.write_bytes(data[:offset] + AFTER + data[offset + len(BEFORE):])
    print(f"patched dji_fpv -> fpv_sock at file offset 0x{offset:x}: {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
