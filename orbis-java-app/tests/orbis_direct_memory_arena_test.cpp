#include <assert.h>
#include <stdint.h>
#include <stdio.h>

#include "../../src/hotspot/os/orbis/orbis_direct_memory_arena.hpp"

static const size_t page_size = 0x4000;
static const size_t arena_size = page_size * 4;
alignas(page_size) static unsigned char arena_memory[arena_size];

int main() {
  OrbisDirectMemoryArena arena;
  assert(orbis_direct_memory_arena_init(&arena, arena_memory, arena_size));

  size_t first_span = 0;
  void* first = orbis_direct_memory_arena_allocate(&arena, 1, &first_span);
  assert(first == arena_memory);
  assert(first_span == page_size);
  assert(reinterpret_cast<uintptr_t>(first) % page_size == 0);

  size_t second_span = 0;
  void* second = orbis_direct_memory_arena_allocate(
      &arena, page_size + 1, &second_span);
  assert(second == arena_memory + page_size);
  assert(second_span == page_size * 2);

  size_t third_span = 0;
  void* third = orbis_direct_memory_arena_allocate(
      &arena, page_size, &third_span);
  assert(third == arena_memory + page_size * 3);
  assert(third_span == page_size);
  assert(orbis_direct_memory_arena_allocate(&arena, 1, NULL) == NULL);

  assert(orbis_direct_memory_arena_release(&arena, second, second_span));
  assert(orbis_direct_memory_arena_release(&arena, first, first_span));
  size_t merged_span = 0;
  void* merged = orbis_direct_memory_arena_allocate(
      &arena, page_size * 3, &merged_span);
  assert(merged == arena_memory);
  assert(merged_span == page_size * 3);
  assert(orbis_direct_memory_arena_release(&arena, merged, merged_span));
  assert(!orbis_direct_memory_arena_release(&arena, merged, merged_span));
  assert(orbis_direct_memory_arena_release(&arena, third, third_span));

  size_t full_span = 0;
  void* full = orbis_direct_memory_arena_allocate(
      &arena, arena_size, &full_span);
  assert(full == arena_memory);
  assert(full_span == arena_size);
  assert(orbis_direct_memory_arena_release(&arena, full, full_span));

  size_t reused_span = 0;
  void* reused = orbis_direct_memory_arena_allocate(
      &arena, arena_size, &reused_span);
  assert(reused == arena_memory);
  assert(reused_span == arena_size);

  OrbisDirectMemoryArena invalid;
  assert(!orbis_direct_memory_arena_init(&invalid, arena_memory, arena_size - 1));
  assert(orbis_direct_memory_arena_allocate(&arena, SIZE_MAX, NULL) == NULL);
  puts("Orbis direct-memory arena tests passed");
  return 0;
}
