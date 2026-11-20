# Moving Out 2 Android

Android ARM64 compatibility launcher and runtime-integration research for **Moving Out 2** (Windows x64 Unity/IL2CPP).

> **Status: Phase 2 runtime-bundle import and validation foundation.** The launcher, game-file validator, runtime bundle importer and structural checks are in CI. Wine/Box64 execution, graphics translation and actual gameplay are not yet integrated or demonstrated.

Independent research project, not affiliated with Team17 or SMG Studio.

## Target
- Game: Moving Out 2 (Windows x64)
- Reported target version: 1.2.274
- Engine: Unity 2021.3.16f1 / IL2CPP (details still require validation)
- Android: ARM64 (arm64-v8a)

Users must supply legitimate game files. The repository does not distribute game files, proprietary libraries, or native runtime binaries.

## Current implementation
- Android launcher and persisted SAF game-folder selection.
- Game installation structure validation and diagnostics.
- Runtime state/process supervision foundation.
- Device, RAM, EGL/GLES and Vulkan-feature diagnostics (Vulkan API/extensions are not enumerated yet).
- Runtime bundle SAF import into app-private storage, staged copy, validation, rollback of previous bundle on import failure.
- Runtime bundle manifest, safe path checks, ELF64 little-endian AArch64 checks, executable permissions, and non-empty native library directory.
- CI debug APK build and unit tests.

## Runtime bundle
Import a folder through **Import runtime bundle (SAF)**. See [Runtime bundle format](docs/RUNTIME_BUNDLE.md) for its manifest and required layout. Import validates files and paths only. The imported bundle is not executed by the launcher yet.

## Roadmap
1. [x] Android project and CI
2. [x] SAF game-folder selection and initial validation
3. [x] Runtime session/process/diagnostic foundation
4. [x] Runtime bundle manifest, validator and SAF import
5. [ ] Runtime dependency inventory and license/redistribution review
6. [ ] Build or integrate Wine ARM64 + Box64 in reproducible CI
7. [ ] Wine prefix bootstrap and Windows x64 process execution
8. [ ] Unity/IL2CPP dependency/compatibility checks
9. [ ] Vulkan API/device-extension enumeration and surface testing
10. [ ] DXVK integration research/build; Turnip assessment for Adreno
11. [ ] Real first-frame milestone (must be captured and validated)
12. [ ] Input/audio bridges, suspend/resume and crash recovery
13. [ ] Device testing, performance, thermal behavior and reproducible release packaging

## Build
CI uses JDK 17, Android SDK 35, Gradle 8.9 and produces a debug APK. A successful build is not compatibility evidence.

## Legal
Moving Out 2 is copyrighted commercial software. Game files stay user-provided. Runtime binaries must be built, sourced and redistributed in compliance with each project's license.
