#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD="${1:-${ROOT}/build/target}"
SDK="${2:-/home/wonder/PS4Toolchain}"
SOURCES="${ROOT}/build/sources"
MESA="${SOURCES}/mesa"
COMPAT="${SOURCES}/orbis-compat"
KIT="${SOURCES}/orbis-porting-kit"
OUT="${BUILD}/graphics-probe.elf"
GALLIUM="$(echo "${BUILD}"/mesa/src/gallium/targets/dri/libgallium-*.a)"
ZLIB="$(echo "${BUILD}"/mesa/subprojects/zlib-*/libz.a)"
LIBC16=("${COMPAT}"/build/libc16/libc16_*.o)

for archive in "${BUILD}/mesa/src/egl/libEGL.a" "${GALLIUM}" \
    "${BUILD}/mesa/src/amd/vulkan/libvulkan_radeon.a" "${ZLIB}" \
    "${COMPAT}/build/liborbis-compat.a"; do
    test -f "${archive}" || { echo "missing archive: ${archive}" >&2; exit 2; }
done
[[ ${#LIBC16[@]} -ge 5 ]] || { echo "missing libc16 objects" >&2; exit 2; }
for object in "${LIBC16[@]}"; do
    [[ -f "${object}" ]] || { echo "missing libc16 object: ${object}" >&2; exit 2; }
done

for source in probe_main probe_egl probe_log probe_control probe_render; do
    clang --target=x86_64-pc-freebsd12-elf --sysroot="${SDK}" -fPIC \
        -D__ORBIS__ -DPS4 -D_BSD_SOURCE=1 \
        -isystem "${COMPAT}/include" -isystem "${SDK}/include" \
        -I"${MESA}/include" -I"${ROOT}/src" \
        -c "${ROOT}/src/${source}.c" -o "${BUILD}/${source}.o"
done

clang --target=x86_64-pc-freebsd12-elf --sysroot="${SDK}" \
    -nostdlib -fuse-ld=lld -pie -Wl,-m,elf_x86_64 \
    -Wl,--script="${KIT}/cmake/orbis-tls.ld" -Wl,--eh-frame-hdr -Wl,--no-rosegment \
    -Wl,--why-extract="${BUILD}/probe-why-extract.txt" \
    -Wl,--error-limit=0 -Wl,--allow-multiple-definition \
    "${BUILD}/probe_main.o" "${BUILD}/probe_egl.o" "${BUILD}/probe_log.o" "${BUILD}/probe_control.o" "${BUILD}/probe_render.o" \
    -Wl,--start-group \
    "${BUILD}/mesa/src/egl/libEGL.a" "${GALLIUM}" \
    -Wl,--whole-archive "${BUILD}/mesa/src/amd/vulkan/libvulkan_radeon.a" -Wl,--no-whole-archive \
    "${ZLIB}" -Wl,--end-group \
    -Wl,--whole-archive "${COMPAT}/build/liborbis-compat.a" -Wl,--no-whole-archive \
    "${LIBC16[@]}" \
    -L"${SDK}/lib" -lc -lkernel -lc++ -lSceGnmDriver -lSceVideoOut "${SDK}/lib/crt1.o" \
    -o "${OUT}"
sh "${KIT}/scripts/ps4/check-wchar-link.sh" "${BUILD}/probe-why-extract.txt" \
    | tee "${BUILD}/probe-wchar-audit.log"
echo "linked ${OUT}"
