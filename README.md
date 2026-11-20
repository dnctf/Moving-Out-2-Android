# Moving Out 2 Android

Dedicated Android ARM64 compatibility launcher/runtime research project for **Moving Out 2**.

> Status: **Early scaffold — no game execution or compatibility has been demonstrated yet.**

This project aims to investigate running the Windows x64 Unity/IL2CPP build of Moving Out 2 on Android through a native Android launcher and a compatibility runtime. It is not an official Team17/SMG Studio project.

## Target

- Game: Moving Out 2 (Windows x64)
- Reported target version: 1.2.274
- Engine: Unity 2021.3.16f1, IL2CPP (target details still subject to validation)
- Android target: ARM64

Expected user-supplied game installation files may include `mo2.exe`, `GameAssembly.dll`, `UnityPlayer.dll`, `baselib.dll`, `MovingOut2_Data/`, and `il2cpp_data/Metadata/global-metadata.dat`. The app will validate files locally; proprietary game files are not distributed in this repository.

## Architecture

- **Android app** — Kotlin UI, installation picker (SAF), validation and settings
- **Runtime session** — lifecycle, process supervision, diagnostics, launch orchestration
- **Compatibility** — focused Moving Out 2 / Unity / IL2CPP / Windows x64 checks
- **Native runtime** — integration research and build boundaries for Wine / Box64
- **Graphics** — Vulkan, DXVK and Turnip feasibility/integration experiments
- **Input & audio** — controller/touch mapping, audio routing, lifecycle handling
- **Tests & CI** — validation tests, build checks, reports and artifacts

## Roadmap

1. Clean project skeleton, Android build and CI
2. SAF-based game folder selection and file validation
3. Runtime feasibility and integration (Wine / Box64, native dependencies)
4. Vulkan / DXVK / Turnip graphics pipeline experiments
5. First-frame milestone (must be demonstrated, not simulated)
6. Input, audio, lifecycle and playable-session validation
7. Device testing, performance, reliability and APK packaging

## Build

Build instructions will be expanded as the Android project and runtime dependencies are implemented. A successful CI build only proves that the launcher builds; it does **not** prove that Moving Out 2 runs.

## Legal / distribution

Moving Out 2 is copyrighted commercial software. This repository does not include the game, game assets, executables, metadata, or proprietary libraries. Users must provide a legitimate installation and comply with applicable licenses.
