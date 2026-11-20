# Moving Out 2 Android

Android ARM64 compatibility launcher and runtime-integration research for **Moving Out 2** (Windows x64 Unity/IL2CPP).

> **Status: Phase 2 runtime bundle validation groundwork.** The APK/launcher and native bundle validator build in CI. Wine/Box64 execution, graphics translation and actual gameplay are not yet integrated or demonstrated.

This is an independent project, not affiliated with Team17 or SMG Studio.

## Target

- Game: Moving Out 2 (Windows x64)
- Reported target version: 1.2.274
- Engine: Unity 2021.3.16f1 / IL2CPP (details still require validation)
- Android target: ARM64 (arm64-v8a)

The user supplies a legitimate game installation. The app does not distribute game files, proprietary libraries, or runtime binaries.

## Current implementation

- Android launcher and persisted SAF game-folder selection.
- Installation file/structure validation and diagnostics.
- Runtime process supervision and state machine foundation.
- Device, RAM, EGL/GLES and Vulkan-feature diagnostics (Vulkan API/extensions are not enumerated yet).
- Runtime bundle validator for a user-built ARM64 bundle manifest: checks paths, ELF64 little-endian AArch64 executable headers, executable flags, and library directory.
- CI builds debug APK and runs unit tests.

## Runtime bundle layout (validator contract)

See [Runtime bundle format](docs/RUNTIME_BUNDLE.md). This is validation-only; no import/install UI or native launch is implemented.

## Roadmap

1. [x] Android project and CI
2. [x] SAF game-folder selection and initial validation
3. [x] Runtime session/process/diagnostic foundation
4. [x] Runtime bundle format and ARM64 ELF validation (validation only)
5. [ ] Bundle import/installation flow with storage safety and dependency inventory
6. [ ] Build or integrate Wine ARM64 + Box64 (license-aware, reproducible, device-tested)
7. [ ] Wine prefix bootstrap and Windows x64 process execution
8. [ ] Unity/IL2CPP dependency/compatibility checks
9. [ ] Vulkan API/device-extension enumeration and surface testing
10. [ ] DXVK integration research/build; Turnip assessment for Adreno
11. [ ] Real first-frame milestone (must be captured and independently validated)
12. [ ] Input/audio bridges, suspend/resume and crash recovery
13. [ ] Device testing, performance, thermal behavior and reproducible release packaging

## Build

CI uses JDK 17, Android SDK 35, Gradle 8.9 and produces a debug APK. A successful build proves the launcher compiles; it is not runtime/game compatibility evidence.

## Legal / distribution

Moving Out 2 is copyrighted commercial software. Game files remain user-provided and are not committed. Runtime binaries must be sourced, built, packaged and redistributed only in accordance with their respective licenses.
