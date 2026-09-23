#!/usr/bin/env python3
import struct
import sys
from pathlib import Path


def main() -> None:
    path = Path(sys.argv[1])
    old = sys.argv[2].encode() + b"\0"
    new = sys.argv[3].encode() + b"\0"
    if len(new) > len(old):
        raise SystemExit("replacement SONAME is longer than the original")

    data = bytearray(path.read_bytes())
    if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        raise SystemExit("expected a little-endian ELF64 file")

    shoff = struct.unpack_from("<Q", data, 40)[0]
    shentsize = struct.unpack_from("<H", data, 58)[0]
    shnum = struct.unpack_from("<H", data, 60)[0]
    shstrndx = struct.unpack_from("<H", data, 62)[0]

    def section(index):
        return struct.unpack_from(
            "<IIQQQQIIQQ", data, shoff + index * shentsize
        )

    shstr = section(shstrndx)
    names = data[shstr[4]:shstr[4] + shstr[5]]
    dynstr_range = None
    for index in range(shnum):
        header = section(index)
        end = names.find(b"\0", header[0])
        name = names[header[0]:end]
        if name == b".dynstr":
            dynstr_range = (header[4], header[5])
            break
    if dynstr_range is None:
        raise SystemExit("ELF has no .dynstr section")

    start, size = dynstr_range
    pos = data.find(old, start, start + size)
    if pos < 0:
        raise SystemExit(f"SONAME string {old!r} was not found")
    data[pos:pos + len(old)] = new + b"\0" * (len(old) - len(new))
    path.write_bytes(data)


if __name__ == "__main__":
    main()
