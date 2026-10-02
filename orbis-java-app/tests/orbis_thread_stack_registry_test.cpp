#include "../../src/hotspot/os/orbis/orbis_thread_stack_registry.hpp"

#include <assert.h>
#include <stdint.h>
#include <stdio.h>

int main() {
  OrbisThreadStackRegistry<2> registry;
  registry.initialize();

  int first = registry.reserve(reinterpret_cast<void*>(0x1000), 0x40000,
                               0x2000, reinterpret_cast<void*>(0x3000));
  assert(first == 0);
  assert(registry.thread_for_slot(first) == reinterpret_cast<void*>(0x3000));
  registry.publish(first, 41);
  registry.complete(first);

  OrbisRetiredThreadStack retired;
  assert(registry.take_completed(&retired));
  assert(retired.slot == first);
  assert(retired.thread_id == 41);
  assert(retired.mapping == reinterpret_cast<void*>(0x1000));
  assert(retired.mapping_size == 0x40000);
  assert(retired.direct_offset == 0x2000);
  assert(retired.thread == reinterpret_cast<void*>(0x3000));

  // A stack being joined/released must not be handed out again.
  int second = registry.reserve(reinterpret_cast<void*>(0x5000), 0x40000,
                                0x6000, reinterpret_cast<void*>(0x7000));
  assert(second == 1);
  assert(registry.reserve(reinterpret_cast<void*>(0x8000), 0x40000,
                          0x9000, reinterpret_cast<void*>(0xa000)) == -1);

  registry.finish_reap(first);
  assert(registry.reserve(reinterpret_cast<void*>(0x8000), 0x40000,
                          0x9000, reinterpret_cast<void*>(0xa000)) == first);

  // Completion can race ahead of publish; publishing must preserve it.
  registry.complete(second);
  registry.publish(second, 42);
  assert(registry.take_completed(&retired));
  assert(retired.slot == second);
  assert(retired.thread_id == 42);

  puts("Orbis thread-stack registry tests passed");
  return 0;
}
