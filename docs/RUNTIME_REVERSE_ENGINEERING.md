# Moving Out 2: Runtime and Input Reverse-Engineering Notes

## Target evidence and limits

The intended game target is a Windows x86-64 Unity IL2CPP player (`mo2.exe`, `UnityPlayer.dll`, `GameAssembly.dll`, and `mo2_Data/il2cpp_data/Metadata/global-metadata.dat`). A D3D11 renderer is expected from the target profile, but the project has not yet inspected a user-supplied game binary in this run. **Do not publish an exact DLL-import list or compatibility result without that binary.**

`GameBinaryAnalyzer` parses PE32+ headers and the import directory with bounds checks. The import results should be collected separately for `mo2.exe`, `UnityPlayer.dll`, and `GameAssembly.dll`, then reconciled with delay imports, dynamic LoadLibrary calls, Unity plugins, and the Steam/EOS SDKs before any Wine override is declared complete. An import table is evidence of static imports only.

## Input architecture and current bridge

The intended flow is:

`Android touch / USB-Bluetooth events → canonical ControllerState → mapping/profile translator → MO2X adapter → runtime`

The existing MO2X protocol uses a 128-byte shared-memory file: a 32-byte header and four 24-byte player slots. Slots use a sequence counter for consistent reads. Controller packet serialization is little-endian (12 bytes: buttons, triggers, four signed axes). The JNI transport writes the shared file; the native Wine XInput DLL exposes `XInputGetState` and reads the same protocol.

This is a real transport implementation and CI builds the XInput DLL, but it is **not yet an active game session**: no Wine/Box64 process is launched or supervised by the Android app, no DLL reader is confirmed running inside a Wine prefix, and no game has consumed the controller packet. The bridge must remain in waiting/unsupported state until runtime connection evidence exists.

## Compatibility stack hypothesis (not a verified launch profile)

A likely investigation path is:
- ARM64 Android host and Vulkan-capable GPU.
- Box64 x86-64 dynamic translation, with build-specific Dynarec configuration and memory-ordering options (for example, `STRONGMEM=1` only when the selected Box64 build supports and requires it).
- A pinned Android Wine fork/build with its own prefix, DLL override policy and synchronization options. `esync`/`fsync` availability is build and kernel dependent; do not enable them blindly.
- D3D11 → Vulkan via a matching DXVK build.
- Mesa Turnip only on a compatible Adreno/Vulkan stack and only after a loader/ICD integration is proven. An imported ICD JSON or library presence alone is not runtime evidence.
- Unity IL2CPP native plugin dependencies and Steam/EOS networking/overlay requirements must be investigated separately. The title may fail before rendering if one of these is missing.

Suggested diagnostic variables are runtime-specific. Record the exact versions/commits, ABI, prefix path, environment, logs, and driver. Do not use a generic `-force-d3d11` flag as a substitute for checking the executable's actual command-line support; Unity options can differ by version/player build.

## Why Wine + Box64 is the first general experiment

Moving Out 2 is a commercial Unity IL2CPP game. Static recompilation would require recovering and reimplementing or recompiling the specific game executable, engine behavior, IL2CPP native code, third-party libraries, asset access and platform services. A successful port would be game-specific and would not be a reusable generic PC-game runtime. It also requires legally obtained original game files and a substantial per-title reverse-engineering effort.

Wine + Box64 is therefore the more plausible first *general compatibility experiment*: it can reuse the Windows executable and its existing runtime interfaces rather than requiring a new native port. That does **not** imply this game is compatible, performant, or launchable on Snapdragon 855. Unity/D3D11, x64 translation, memory pressure, Vulkan driver, anti-cheat/platform services and audio all remain untested. A static port may be preferable only if a legally compatible game-specific reimplementation or recompilation project becomes available and its licensing/build prerequisites are met.

## Controller research references (source-level review completed 2026-11-22)

- [flipendo-droid](https://github.com/Ihor-Ihorevych/flipendo-droid): candidate reference for a native game-port controller overlay.
- [pop-t2t-android](https://github.com/Cleverdee/pop-t2t-android): candidate reference for a touch overlay connected to a game's own native input layer.

Source and license review covered `src/hp1/HP1Touch.cpp` in flipendo-droid and `TouchOverlay.java` plus `web/touch.js` in pop-t2t-android. Flipendo is GPL-3.0; POP T2T is MIT. The first is a game-specific native touch bridge (its controls send actions into the port's own engine); the latter demonstrates pointer-owned controls, dynamic layouts, per-layout edit/persistence, classic fallback and touch/gamepad arbitration. These are not generic global Android input APIs. No code or artwork from either repository was copied.

The architecture here follows the project's own requirements: configurable touch controls, physical input normalization, one canonical state, and a runtime adapter owned by this app. These references do not provide a generic Android API for injecting a gamepad into another app. Android global input injection is restricted; the supported route here is the app's own runtime adapter, not system-wide virtual HID.

## Import-table workflow

1. User selects a game installation with read permission.
2. Read bounded PE files without executing them.
3. Store DLL import names and parse errors as diagnostic metadata only.
4. Compare static imports with runtime DLL loading evidence and Unity plugins.
5. Test one DLL override at a time against a pinned Wine build.
6. Record first failure stage, logs and exact runtime provenance.

Never copy the executable, proprietary game assets, user credentials, or private runtime files into the repository or CI artifact.

## Controller source review and implementation notes (2026-11-24)

### External reference findings (no code/assets copied)

- **Flipendo Android** (`android/java/io/github/flipendo/spike/FlipendoActivity.java`, `src/hp1/HP1Touch.cpp`): a game-specific SDL activity maps Android `KeyEvent` / `MotionEvent` gamepad input into the port's SDL and native game input path. Its touch overlay uses explicit pointer ownership, layout editing and per-game behavior. The repository is GPL-3.0; this project uses it as architectural research only and does not copy source or assets.
- **POP T2T Android** (`android/app/src/main/java/com/cleverdee/pop3/TouchOverlay.java`): the overlay tracks pointer IDs for held buttons, a separate stick pointer and a separate mouse/look pointer; `ACTION_CANCEL` clears held state; a connected physical controller hides the touch overlay and clears touch state. Control positions and sizes are stored per layout as normalized screen coordinates and reapplied from `onSizeChanged`; edit mode supports drag and two-finger resize. The Android README documents physical controller input through SDL and touch input through pad 0. The repository's root README describes a separate browser/macOS build and explicitly marks gamepad as not verified there, so those claims must not be conflated. The repository license is MIT. No code or assets were copied.
- Both references keep game input inside the game's own engine/SDL adapter. Neither provides an Android API for injecting a virtual controller globally into unrelated applications.

### MO2X mapping ownership

The JNI implementation owns one process-global mapping (`g_mapping`, `g_fd`) while Kotlin can create more than one `NativeXInputTransport` (the controller screen and the Wine launcher each construct a bridge). Calling `nativeInitSharedMemory` or `nativeDestroySharedMemory` from one instance previously replaced or unmapped the mapping for every other instance. `NativeMappingLeaseRegistry` now enforces one active path, permits same-path shared leases, rejects conflicting paths, and calls JNI init/destroy only for the first/last lease. JVM tests cover shared ownership, conflicting paths, last-owner teardown and restoration after a failed native destroy.

### Runtime connection status remains intentionally gated

`XInputTransport` is the runtime adapter boundary and `RuntimeInputBridge` reports native transport capability. The Android controller screen initializes the shared-memory writer; `WineRuntimeLauncher` stages the XInput DLLs and supplies `MO2_XINPUT_SHM_PATH`. The MO2X header now reserves its final 64-bit field as a reader heartbeat. The Wine XInput DLL opens the mapping read/write and increments this counter from XInput entry points; JNI reads the counter, and a supervised monitor promotes the bridge to `BRIDGE_CONNECTED` only after the counter advances. Process startup or file presence alone still leaves `BRIDGE_READY_WAITING_RUNTIME`. The monitor returns to waiting/unsupported when the process exits or the mapping heartbeat resets. This verifies that the bundled XInput DLL is being called, not that the game responds correctly to every button or that gameplay works; those still require a physical runtime test.


## Physical controller player routing (2026-11-25)

Each live Android gamepad is now routed by device ID to its own XInput slot. The first physical device shares player 0 with the virtual touch overlay; additional devices use slots 1–3. Disconnect and Activity teardown write neutral/disconnected packets for the affected slots. Device IDs are runtime-scoped and are not persisted across reconnects; a reconnected device receives the next free slot. This supports up to four simultaneous XInput players, but does not yet provide a user-facing player-slot assignment screen.
