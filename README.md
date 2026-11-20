# Moving Out 2 Android

Android ARM64 compatibility launcher and runtime-integration research for **Moving Out 2** (Windows x64 Unity/IL2CPP).

> **Status: Phase 2 runtime bundle import and Phase 3 Vulkan diagnostics groundwork.** CI builds the launcher and its native diagnostics when green. Wine/Box64 execution, graphics translation, Unity compatibility and actual gameplay are not integrated or demonstrated.

Independent project, not affiliated with Team17 or SMG Studio.

## Target
- Game: Moving Out 2 (Windows x64)
- Reported target version: 1.2.274
- Engine: Unity 2021.3.16f1 / IL2CPP (details still require validation)
- Android: ARM64 (arm64-v8a)

Users must supply legitimate game files. This repository does not distribute game files, proprietary libraries, or native runtime binaries.

## Current implementation
- Android launcher and persisted SAF game-folder selection.
- Game installation structure validation and diagnostics.
- Runtime state/process supervision foundation.
- User-provided runtime bundle SAF import into app-private storage with streamed copy, bounds, staging, validation and rollback.
- Runtime bundle manifest, canonical path confinement, executable flags, ELF64 little-endian AArch64 checks, and library directory validation.
- Optional DXVK payload checks for x64 `d3d11.dll`/`dxgi.dll` and Turnip driver/ICD presence checks (explicitly presence-only, not load-tested).
- Selected game-folder profile detection: Unity version string from bounded `globalgamemanagers` scan, IL2CPP metadata magic/version, and PE architecture.
- Native JNI Vulkan diagnostics: loader API, physical device, vendor/device IDs, device API version and device extension names.
- EGL/GLES renderer fallback and asynchronous graphics status in launcher UI.
- CI debug APK build, Kotlin tests and native C++ compilation.

## Runtime integration research
See [Runtime integration gates](docs/RUNTIME_INTEGRATION_PLAN.md) for upstream source baselines, Android executable restrictions, component provenance requirements and staged Wine/Box64 integration gates.

## Runtime bundle
Import a folder through **Import runtime bundle (SAF)**. See [Runtime bundle format](docs/RUNTIME_BUNDLE.md). The import is validation-only; the bundle is not launched.

## Roadmap
1. [x] Android project and CI
2. [x] SAF game-folder selection and initial validation
3. [x] Runtime session/process/diagnostic foundation
4. [x] Runtime bundle manifest, validator and SAF import
5. [x] Native Vulkan device/extension diagnostic groundwork (device verification pending)
6. [ ] Runtime dependency inventory and license/redistribution review
7. [ ] Build or integrate Wine ARM64 + Box64 in reproducible CI
8. [ ] Android-safe native process launch and Wine prefix bootstrap
9. [ ] Unity/IL2CPP dependency/compatibility checks
10. [ ] Vulkan surface/presentation testing; DXVK and Turnip integration research
11. [ ] Real first-frame milestone (captured, not simulated)
12. [ ] Input/audio bridges, suspend/resume and crash recovery
13. [ ] Device testing, performance, thermal behavior and reproducible release packaging

## Build
CI uses JDK 17, Android SDK 35, pinned NDK/CMake and Gradle 8.9 to build the ARM64 debug APK. A green build is not game compatibility evidence.

## Legal
Moving Out 2 is copyrighted commercial software. Game files stay user-provided. Runtime binaries must be built, sourced and redistributed in compliance with each project's license.


## Game ZIP import and user settings

- Use **Import game from ZIP** to select a user-owned ZIP. Extraction is performed into the app's private storage using a staging directory and backup/rollback flow; the ZIP must contain `mo2.exe` and `MovingOut2_Data` at a recognizable game root.
- ZIP extraction rejects absolute/traversal paths, limits entries to 100,000, and caps expanded content at 16 GiB. This is an import/storage feature, not proof the game is compatible or launchable.
- **Audio, graphics and resolution settings** stores master/music/SFX volume, resolution scale (50–100%), and FPS target (30/45/60). Recommended initial profile for Snapdragon 855-class devices is Low graphics and 70–75% resolution scaling with a 30 FPS cap. Settings are persisted locally, but cannot affect game execution until the native runtime is integrated.
- Snapdragon 855 through newer devices are the intended device range. A 30 FPS minimum target is a performance goal, not a guarantee; sustained FPS depends on runtime integration, game scene, thermals, memory, drivers, and device-specific testing.
