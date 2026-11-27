# Controller Compatibility Research Notes

Research checked on 2026-11-27. These repositories are architectural references only; no source code, graphics, or other assets have been copied into this project.

## Flipendo Droid

Repository: https://github.com/Ihor-Ihorevych/flipendo-droid  
Default branch: `master`  
License: GPL-3.0

Relevant inspected files:
- `android/java/io/github/flipendo/spike/Ps1PadLayout.java`
- `android/java/io/github/flipendo/spike/Ps1PadView.java`
- `android/java/io/github/flipendo/spike/Ps2Pad.java`

Observed design patterns:
- Touch geometry is separated into a plain-Java layout class and covered by Android-independent tests.
- Controls are grouped (D-pad, face buttons, shoulders, middle controls) with independently persisted offsets and scale.
- The view tracks pointer IDs and combines simultaneous touches into one native pad bitmask.
- The layout editor supports group dragging, resizing, reset, and safe-area handling for display cut-outs.
- Physical gamepad activity can hide the touch overlay; touch can reactivate it.

The repository is GPL-3.0. Its implementation is not copied here; any future code reuse would require a separate GPL compatibility and distribution review.

## Prince of Persia: The Two Thrones Android

Repository: https://github.com/Cleverdee/pop-t2t-android  
Default branch: `main`  
License: MIT

Relevant inspected files:
- `android/app/src/main/java/com/cleverdee/pop3/TouchOverlay.java`
- `android/app/src/main/java/com/cleverdee/pop3/GameActivity.java`
- `android/app/src/main/java/com/cleverdee/pop3/GameSurface.java`
- `host/input.c`

Observed design patterns:
- The Android Activity owns a real SDL surface and places the touch overlay above it.
- Touch controls feed a game-specific native input function rather than relying on Android-wide virtual-key injection.
- Layouts can change with the game phase and each layout stores its own control geometry.
- Per-pointer ownership, held-button release, and physical-pad/overlay visibility are explicit.
- The README states that gamepad support is not yet implemented/verified in that repository; its touch-to-native-pad path is not evidence of a tested physical gamepad path.

The repository is MIT licensed. No code was copied; the current project uses a distinct Kotlin/XInput protocol and has a different runtime architecture.

## Current project boundary

The current input path is:

`VirtualControllerView / PhysicalGamepadManager
  -> ControllerInputCoordinator
  -> RuntimeInputBridge
  -> MO2X shared memory
  -> Wine XInput DLL`

The project does not currently expose a game-owned SDL input API. Android's stock input dispatch cannot inject a virtual gamepad globally into another process. The Wine XInput adapter is project-specific IPC and is only considered connected after the native DLL reader heartbeat advances.

The virtual controller is currently presented in the launcher's controller-test dialog. It is not yet composited over a live game surface. A production in-game overlay requires a supported rendering/window host for the Wine process and lifecycle coordination with that host.

## Next engineering steps

1. Add independently configurable control groups (position and scale), persist them per game and orientation, and clamp groups inside display-safe bounds.
2. Add a physical-pad activity policy that hides touch controls only while a physical device is producing input, then restores them on touch or disconnect.
3. Keep the canonical controller state independent of the runtime adapter; do not label Wine/Box64 or Unity as connected until the XInput reader evidence is current.
4. When a game surface host exists, compose the touch view above that surface and route all pointer cancellation through the same canonical-state release path.
5. Continue JVM tests for geometry, pointer ownership, axis mapping, device removal, and profile import/export.
