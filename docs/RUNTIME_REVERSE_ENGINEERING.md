# Moving Out 2: Runtime and Input Reverse-Engineering Notes

## Target evidence and limits

The intended game target is a Windows x86-64 Unity IL2CPP player (`mo2.exe`, `UnityPlayer.dll`, `GameAssembly.dll`, and `MovingOut2_Data/il2cpp_data/Metadata/global-metadata.dat`). A D3D11 renderer is expected from the target profile, but the project has not yet inspected a user-supplied game binary in this run. **Do not publish an exact DLL-import list or compatibility result without that binary.**

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

## Controller research references (source-level audit pending)

- [flipendo-droid](https://github.com/Ihor-Ihorevych/flipendo-droid): candidate reference for a native game-port controller overlay.
- [pop-t2t-android](https://github.com/Cleverdee/pop-t2t-android): candidate reference for a touch overlay connected to a game's own native input layer.

Repository metadata/tree requests were attempted during this run, but the connector did not return readable source or license file contents. Therefore no source-level feature claims or license conclusions are made here; these repositories remain research leads until their source and licenses can be reviewed directly. No code or artwork from either repository was copied.

The architecture here follows the project's own requirements: configurable touch controls, physical input normalization, one canonical state, and a runtime adapter owned by this app. These references do not provide a generic Android API for injecting a gamepad into another app. Android global input injection is restricted; the supported route here is the app's own runtime adapter, not system-wide virtual HID.

## Import-table workflow

1. User selects a game installation with read permission.
2. Read bounded PE files without executing them.
3. Store DLL import names and parse errors as diagnostic metadata only.
4. Compare static imports with runtime DLL loading evidence and Unity plugins.
5. Test one DLL override at a time against a pinned Wine build.
6. Record first failure stage, logs and exact runtime provenance.

Never copy the executable, proprietary game assets, user credentials, or private runtime files into the repository or CI artifact.
