/*
 * The runner's process entry and parameters: the toolchain's crt1.o, reproduced section for
 * section, except that _sceKernelMemParam names a flexible-memory size.
 *
 * Every anonymous mapping a process makes - thread stacks, malloc's large blocks, Mesa's and
 * LWJGL's buffers, and the JVM's PROT_NONE reservations (the code cache alone is 240 MiB) - is
 * charged to the flexible-memory budget, reserved-but-untouched space included. With crt1.o's
 * null pointer the budget is the system default (~448 MiB), which Minecraft under Parallel GC
 * exhausted while ~3.5 GiB of direct memory sat unused; every anonymous mapping of 1 MiB or more
 * then failed with EINVAL. ORBIS_FLEXIBLE_MEMORY_SIZE moves budget from direct to flexible.
 */
#ifndef ORBIS_FLEXIBLE_MEMORY_SIZE
#define ORBIS_FLEXIBLE_MEMORY_SIZE (1024 * 1024 * 1024)
#endif

        .text
        .globl  _start
_start:
        jmp     _start_ps4_c
        nopl    (%rax)

        .section .text._start_ps4_c,"ax",@progbits
        .globl  _start_ps4_c
_start_ps4_c:
        movl    (%rdi), %esi
        leaq    8(%rdi), %rdx
        movq    main@GOTPCREL(%rip), %rdi
        movq    _init@GOTPCREL(%rip), %rcx
        movq    _fini@GOTPCREL(%rip), %r8
        xorl    %r9d, %r9d
        jmp     __libc_start_main@PLT

        .weak   _init
        .weak   _fini

        .data
        .balign 8
sceLibcHeapExtendedAlloc:
        .quad   1
sceLibcHeapSize:
        .quad   0xffffffffffffffff
sce_libc_heap_delayed_alloc:
        .long   0
sce_libc_heap_extended_alloc:
        .long   0
__dso_handle:
        .quad   0
_sceLibc:
        .quad   0
orbis_flexible_memory_size:
        .quad   ORBIS_FLEXIBLE_MEMORY_SIZE

        .section .data.rel.ro._sceLibcParam,"aw",@progbits
        .balign 8
_sceLibcParam:
        .quad   0x90
        .long   0xc, 1
        .quad   sceLibcHeapSize
        .quad   0
        .quad   sceLibcHeapExtendedAlloc
        .quad   0
        .quad   sceLibcMallocReplace
        .quad   sceLibcNewReplace
        .quad   0, 0, 0, 0
        .quad   sceLibcMallocReplaceForTls
        .quad   0, 0, 0, 0, 0

        .section .data.rel.ro._sceKernelMemParam,"aw",@progbits
        .balign 8
_sceKernelMemParam:
        .quad   0x30
        .quad   0                               /* extended page table */
        .quad   orbis_flexible_memory_size      /* flexible memory size */
        .quad   0                               /* extended memory 1 */
        .quad   0                               /* extended GPU page table */
        .quad   0                               /* extended memory 2 */

        .section .data.rel.ro._sceKernelFsParam,"aw",@progbits
        .balign 8
_sceKernelFsParam:
        .quad   0x10, 0

        .section .data.rel.ro._sceLibcMallocReplace,"aw",@progbits
        .balign 8
sceLibcMallocReplace:
        .quad   0x70, 1
        .fill   12, 8, 0

        .section .data.rel.ro._sceLibcNewReplace,"aw",@progbits
        .balign 8
sceLibcNewReplace:
        .quad   0x70, 2
        .fill   12, 8, 0

        .section .data.rel.ro._sceLibcMallocReplaceForTls,"aw",@progbits
        .balign 8
sceLibcMallocReplaceForTls:
        .quad   0x38, 1
        .fill   5, 8, 0

        .section .data.sce_process_param,"aw",@progbits
        .balign 8
_sceProcessParam:
        .quad   0x50
        .long   0x4942524f, 3                   /* "ORBI", 3 entries */
        .quad   0x4508101
        .quad   0, 0, 0, 0
        .quad   _sceLibcParam
        .quad   _sceKernelMemParam
        .quad   _sceKernelFsParam

        .bss
        .globl  __ps4Argv
__ps4Argv:
        .quad   0
