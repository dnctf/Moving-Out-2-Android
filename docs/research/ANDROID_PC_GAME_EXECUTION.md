# Android PC Game Execution Research

Updated: 2026-11-20

This note records externally observed projects and architecture patterns. It is research, not a claim that any listed runtime has been integrated or that games have been tested on this app.

## Approaches

### Windows compatibility runtime (Wine + Box64)
- General-purpose Windows compatibility layer plus x86_64 CPU translation.
- Broad potential game coverage, but CPU, Windows API, graphics, audio, filesystem, and runtime dependency issues can block individual games.
- Our runtime bundle checks are structural and are not evidence of launch capability.

### Unity-specific compatibility
- Unity games require profile-aware detection (Unity version, architecture, IL2CPP/Mono evidence, graphics API, native libraries).
- Detection is evidence gathering only. Do not infer that a game can launch because it is Unity.

### Native recompilation / game-specific ports (Stronghold research)
Reference: https://github.com/phys-winner/pc_decomp_strhld
- Project description reports native Android ARM64 ports of Stronghold HD, Crusader and Crusader Extreme using static PC recompilation; original game files are required.
- This is game-specific, unlike Wine/Box64. It can avoid a general Windows runtime for games with a maintained recompiler/port, but needs per-game reverse engineering and does not guarantee performance or compatibility.
- Metadata/repository description was checked; source tree, build scripts and implementation were not independently audited in this pass.
- Do not bundle proprietary game files. Treat this repo as prior art; review GPL-3.0 and other applicable licensing before code reuse or distribution.

### Controller research references
- https://github.com/Ihor-Ihorevych/flipendo-droid
- https://github.com/Cleverdee/pop-t2t-android

Use as comparative research for Android input/controller approaches. No code is copied or presumed integrated based solely on links.


## Capability-driven architecture
Game detection -> evidence/requirements profile -> runtime decision -> selected implementation -> shared input/audio/graphics configuration -> diagnostics.

Runtime categories:
- Wine/Box64 compatibility runtime
- Game-specific native recompilation (only when a supported implementation actually exists)
- Native game port
- Experimental runtime (explicitly labeled)

Do not expose unsupported categories as launch-ready. Runtime adapters must report capabilities and limitations rather than simulate success.

## Game profile evidence fields
- executable/file signatures and architecture
- engine/version (if detected)
- Mono/IL2CPP metadata evidence
- graphics API evidence and minimum Vulkan/OpenGL requirements
- native dependencies and redistributable evidence
- runtime availability and version
- input profile / supported virtual and physical controls
- audio backend/requirements
- device/SoC compatibility evidence
- status values: detected, needs_runtime, experimental, supported, tested_on_device; each status requires evidence

## Compatibility claims
- Detection is not compatibility.
- Bundle validation is not executable launch.
- CI APK build is not device runtime verification.
- FPS goals are targets until measured on actual hardware with repeatable frame-time evidence.

## Research backlog
1. Inspect Flipendo and POP T2T implementation/build structure and license.
2. Inspect Stronghold recompiler architecture, platform abstractions, build and asset boundaries.
3. Map findings to runtime contracts and game profile schema.
4. Identify possible game-specific recompilation targets only where source/decompilation feasibility and licensing are clear.
5. Implement controller abstraction shared across runtimes; do not rely on privileged Android-wide input injection without required permissions.
