# Native display and input service handoff

The native display and input services also support the opt-in Cacio desktop
bridge. Console remains the default; `display=desktop` selects desktop startup
through the runner's `runner.display` property. The bridge uses the service
interfaces documented here.
The public C interfaces are in [`orbis_display_service.h`](../tools/orbis_display_service.h)
and [`orbis_input_service.h`](../tools/orbis_input_service.h); these headers are
the source of truth for signatures and ABI version 1.

## Display API and ownership

The display API has C linkage and fixed-width status/argument types:

```c
typedef int32_t (*orbis_display_console_render_fn)(
    uint32_t *argb, uint32_t width, uint32_t height,
    uint32_t stride_pixels, void *context);
int32_t orbis_display_set_console_renderer(
    orbis_display_console_render_fn renderer, void *context);
int32_t orbis_display_start(void);
void orbis_display_stop(void);
int32_t orbis_display_set_desktop_enabled(int32_t enabled);
int32_t orbis_display_submit_argb(const uint32_t *pixels, size_t count,
    uint32_t width, uint32_t height, uint32_t stride_pixels,
    uint64_t sequence);
int32_t orbis_display_set_cursor(int32_t x, int32_t y, int32_t visible);
void orbis_display_mark_console_dirty(void);
```

Register the console renderer while stopped and before starting. The presenter
calls it without display or input locks. It must copy console state under its
short console lock, release that lock, rasterize the complete frame, and return
0. The callback must not call either service. Frame submission copies caller
pixels synchronously and never retains the pointer. It accepts exactly 1280 by
720 pixels, 921,600 pixels total, and a stride of 1280. Sequence numbers must
increase strictly after the first accepted frame. Pixels are canonical straight
ARGB; presentation composites alpha over opaque black, then swaps red and blue
for the A8B8G8R8 output format. A small opaque native arrow is drawn on top and
clipped to the screen.

The newest desktop frame is kept in a two-slot mailbox, with a separate private
compositor snapshot. Console state is rasterized on demand. The presenter owns
VideoOut, registered buffers, flip identities, and CPU composition, and targets
at most 60 submissions per second. Flip timeouts and errors leave the affected
buffer reserved while status is polled; a possibly submitted buffer is not
reused unless its exact flip identity completes.

The bounded image storage is 19,447,808 bytes: 8,388,608 bytes for the two
registered direct-memory images after 2 MiB alignment, 7,372,800 bytes for the
mailbox pair, and 3,686,400 bytes for the private image snapshot. This excludes
the pthread stack, service state, and VideoOut/kernel bookkeeping. Registered
direct memory and mappings are intentionally retained until process exit after
registration, including after stop: available declarations and homebrew
evidence do not establish that unregister/close synchronously ends scanout
ownership. Consequently, the service rejects restart after registration. If
unwind before registration cannot safely release acquired memory, it is also
retained and restart is rejected.

## Input API, focus, and reset behavior

The input API is declared in `orbis_input_service.h`:

```c
int32_t orbis_input_start(void);
void orbis_input_stop(void);
int32_t orbis_input_set_desktop_enabled(int32_t enabled);
int32_t orbis_input_set_focus(orbis_input_focus focus);
int32_t orbis_input_read_event(orbis_input_focus requested_focus,
    orbis_input_event *out, uint32_t timeout_ms);
int32_t orbis_input_get_state(orbis_input_state *out);
```

The version-1 event and state structures carry `abi_version` and `struct_size`.
State includes focus, modifiers, a 256-bit pressed-key bitmap, button bits,
pointer coordinates, and reset generation. Events include type, focus owner,
monotonic timestamp, raw key, translated scalar where available, modifiers,
coordinates, button/wheel data, and pressed-state snapshots. The queue holds at
most 256 events. `read_event` returns 0 when it writes an event, 1 on timeout,
and a negative status on stopped/error/invalid input. It removes only an event
for the requested focus, so the console and a future desktop consumer cannot
steal one another's events.

Focus changes clear held state and route RESET events to both old and new focus
owners. Overflow clears queued transitions, increments reset generation, and
inserts RESET snapshots for affected owners and current focus. Consumers should
discard locally held/editor state on RESET or FOCUS and reconcile from the
snapshot. The current owner receives the current pressed state; inactive owners
receive cleared keys, buttons, and modifiers, retaining pointer coordinates.
F12 toggles focus only while desktop input is enabled, and the toggle
key is consumed. Disabling desktop returns focus to console. Display desktop
availability and input desktop availability are separate service calls and
must be set consistently by a future integration layer.

The collector polls on an 8 ms cadence and validates keyboard sample counts in
the range 0–32 before reading entries. It bounds mouse reads to batches of 32;
zero samples mean no new data, not disconnect. Failed handles are retried no
more than once per second, and diagnostics are bounded. The editor receives
printable ASCII text, key transitions, modifiers, pointer motion, buttons, and
wheel events; this does not promise a complete international keyboard layout.
Enter and Backspace emit only KEY_DOWN for their initial commit/edit. Enter
does not repeat. Held Backspace emits TEXT with raw Backspace identity and
normalized U+0008, independent of raw-42 or ASCII-8 firmware translation;
printable text and Backspace repeat after 500 ms at 33 ms intervals, with at most
one repeat per collector poll. Failed keyboard reads discard the entire sample,
clear keyboard held/repeat/suppression state, and publish RESET to affected
owners while preserving current mouse state. Invalid keyboard counts use the
same reset path without delaying mouse polling. Mouse read failures clear button
state and publish RESET while preserving current keyboard state. These resets
use the same per-owner snapshot rule and discard a pending console command.

The presenter samples native focus outside its display lock. The console
renderer also runs unlocked; the presenter refreshes focus after composition
and discards a candidate frame if focus or desktop availability changed during
the callback. Focus can still change immediately after that refreshed read, so
the displayed mode can lag until the next native loop. These snapshots do not
provide atomic focus-to-flip handoff.

## Inferred mouse ABI provenance

The installed PS4 toolchain `Mouse.h` contains void/no-argument placeholders,
so it is not used to establish the prototypes or data layout. The adapter in
[`orbis_mouse_abi.h`](../tools/orbis_mouse_abi.h) is explicitly inferred from
the declarations in [shadPS4 `mouse.h` at pinned commit
0f6d1b870ee1b42f3762bc899526fea6f6147d59](https://github.com/shadps4-emu/shadPS4/blob/0f6d1b870ee1b42f3762bc899526fea6f6147d59/src/core/libraries/mouse/mouse.h)
and behavior in its [matching `mouse.cpp`](https://github.com/shadps4-emu/shadPS4/blob/0f6d1b870ee1b42f3762bc899526fea6f6147d59/src/core/libraries/mouse/mouse.cpp).
The adapter's source header carries `SPDX-FileCopyrightText: Copyright
2024-2026 shadPS4 Emulator Project` and `SPDX-License-Identifier:
GPL-2.0-or-later`; the implementation is not copied. The pinned upstream
[GPL-2.0 license](https://github.com/shadps4-emu/shadPS4/blob/0f6d1b870ee1b42f3762bc899526fea6f6147d59/LICENSE)
applies to that source attribution.

The inferred sample layout is 40 bytes with offsets 0 (timestamp), 8
(connected byte plus explicit three-byte padding), 12 (buttons), 16 (x), 20
(y), 24 (wheel), 28 (tilt), and 32 (reserved bytes). The open parameter is
eight bytes. These sizes and offsets are asserted at compile time for the
configured x86_64 PS4 ABI. This is emulator-derived reverse-engineering
evidence, not a Sony primary SDK declaration. Hardware ABI, firmware behavior,
button meanings, timestamp units, and delta units remain unverified.

## Acceptance boundary

In desktop mode, the runner reflectively initializes and starts the bridge
before loading the application main class. The target application stays out of
the system class path and is loaded by the Cacio-aware application loader. On
startup failure, the runner requests desktop shutdown and reports the original
failure; shutdown returns native focus to console. The application loader and
application/AWT threads are intentionally not terminated when windows close or
`main` returns.

The Java frame publisher runs at most 30 Hz. The native two-slot mailbox may
discard intermediate frames when publishing outruns presentation; it keeps
the newest accepted image, which remains visible while Java is paused. Bridge
shutdown returns presentation to the console. The native cursor is composited
separately, so it can continue moving while Java is paused. Mouse coordinates
and wheel/button meanings depend on the inferred mouse ABI described above.
The adapter reports a click only when press and release occur at the same
coordinates within 500 ms; it emits click count one and does not detect
multi-click sequences.

RESET releases delivered held keys/buttons and reconciles the active snapshot.
Suppressing typed text and clicks does not suppress application actions:
reconciled presses may repeat pressed bindings, and synthetic releases may
trigger release bindings or Swing button actions. Held keys/buttons during
overflow, disconnect and F12 require application-specific acceptance. Sustained
Robot/local events can defer native input and resets indefinitely under the
current local-first polling; fairness remains deferred.

A successful compile, export, or package audit does not prove PS4 execution.
Manual acceptance remains pending for Java2D, text and images, basic Swing
editing, input release/wheel/F12 behavior, Java pauses with native cursor,
errors and shutdown. Managed-window drag/resize and modality need specific
adaptation and confirmation. Mouse ABI layout remains inferred; registered
video memory remains retained until process exit, and display restart is
unsupported.
