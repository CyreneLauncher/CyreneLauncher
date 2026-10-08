/* SPDX-License-Identifier: MIT
 * fficonfig.h for libffi 3.4.4's x86-64 Unix back-end on Orbis, written by hand (configure cannot
 * run for the console). Closures come from orbis_ffi_closures.c instead of closures.c, so neither
 * static trampolines nor the double-mapping allocator are configured. long double stays off to
 * match LWJGL's ffi.h (HAVE_LONG_DOUBLE 0).
 */
#ifndef ORBIS_FFICONFIG_H
#define ORBIS_FFICONFIG_H

#define EH_FRAME_FLAGS "a"
#define HAVE_ALLOCA 1
#define HAVE_ALLOCA_H 1
#define HAVE_AS_CFI_PSEUDO_OP 1
#define HAVE_AS_X86_64_UNWIND_SECTION_TYPE 1
#define HAVE_AS_X86_PCREL 1
#define HAVE_HIDDEN_VISIBILITY_ATTRIBUTE 1
#define HAVE_INTTYPES_H 1
#define HAVE_MEMCPY 1
#define HAVE_RO_EH_FRAME 1
#define HAVE_STDINT_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_SYS_MMAN_H 1
#define HAVE_UNISTD_H 1
#define SIZEOF_DOUBLE 8
#define SIZEOF_LONG_DOUBLE 16
#define SIZEOF_SIZE_T 8
#define STDC_HEADERS 1
#define PACKAGE_VERSION "3.4.4"
#define VERSION "3.4.4"

#ifdef LIBFFI_ASM
#define FFI_HIDDEN(name) .hidden name
#else
#define FFI_HIDDEN __attribute__ ((visibility ("hidden")))
#endif

#endif
