# Architecture direction

This repository is intentionally dedicated to Moving Out 2.

## Layers
1. Android presentation and user settings (Kotlin)
2. Game installation acquisition/validation (Storage Access Framework; no proprietary content bundled)
3. Runtime session orchestration (state machine, logs, process supervision)
4. Compatibility adapters (Windows x64, Unity 2021, IL2CPP)
5. Runtime implementation boundary (Wine and Box64, subject to feasibility tests)
6. Graphics bridge (Vulkan/DXVK/Turnip experiments)
7. Input/audio and diagnostics

## Runtime selection principle
Use evidence-driven runtime capability detection rather than hard-coded promises. Potential paths include Wine+Box64 compatibility, game-specific native recompilation, native game ports, and explicitly experimental runtimes. A native recompiler/port must only be selectable when a matching implementation exists and its requirements are satisfied. Shared input/audio/settings should remain independent of the execution backend.

## Evidence requirements
Runtime and graphics integrations must be evidence-based. Avoid mock launch success, fake compiler output, or fabricated compatibility results. Detection, bundle validation, CI build, device execution, and measured game performance are separate levels of evidence.
