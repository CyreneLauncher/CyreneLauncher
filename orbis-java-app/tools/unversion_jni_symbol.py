#!/usr/bin/env python3
import struct
import sys
from pathlib import Path


def main() -> None:
    path = Path(sys.argv[1])
    data = bytearray(path.read_bytes())
    if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        raise SystemExit("expected a little-endian ELF64 file")

    shoff = struct.unpack_from("<Q", data, 40)[0]
    shentsize = struct.unpack_from("<H", data, 58)[0]
    shnum = struct.unpack_from("<H", data, 60)[0]
    shstrndx = struct.unpack_from("<H", data, 62)[0]

    def section(index: int) -> tuple[int, ...]:
        return struct.unpack_from(
            "<IIQQQQIIQQ", data, shoff + index * shentsize
        )

    shstr = section(shstrndx)
    section_names = data[shstr[4]:shstr[4] + shstr[5]]

    def section_name(header: tuple[int, ...]) -> bytes:
        start = header[0]
        end = section_names.find(b"\0", start)
        return bytes(section_names[start:end])

    sections = {section_name(section(i)): section(i) for i in range(shnum)}
    dynsym = sections.get(b".dynsym")
    dynstr = sections.get(b".dynstr")
    versym = sections.get(b".gnu.version")
    if dynsym is None or dynstr is None or versym is None:
        raise SystemExit("ELF is missing dynamic symbol version sections")

    dynstr_data = data[dynstr[4]:dynstr[4] + dynstr[5]]
    matches = []
    for index in range(dynsym[5] // dynsym[9]):
        symbol_offset = dynsym[4] + index * dynsym[9]
        name_offset = struct.unpack_from("<I", data, symbol_offset)[0]
        end = dynstr_data.find(b"\0", name_offset)
        if dynstr_data[name_offset:end] == b"JNI_CreateJavaVM":
            matches.append(index)

    if len(matches) != 1:
        raise SystemExit(
            f"expected one JNI_CreateJavaVM symbol, found {len(matches)}"
        )

    struct.pack_into("<H", data, versym[4] + matches[0] * 2, 1)
    path.write_bytes(data)
    print(f"made JNI_CreateJavaVM unversioned (dynsym index {matches[0]})")


if __name__ == "__main__":
    main()
