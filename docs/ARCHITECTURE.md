# Architecture direction

This repository is intentionally dedicated to Moving Out 2.

## Layers

1. Android presentation and user settings (Kotlin)
2. Game installation acquisition/validation (Storage Access Framework; no proprietary content bundled)
3. Runtime session orchestration (state machine, lifecycle, logs, process supervision)
4. Compatibility adapters (Windows x64, Unity 2021, IL2CPP)
5. Runtime implementation boundary (Wine and Box64, subject to feasibility tests)
6. Graphics bridge (Vulkan/DXVK/Turnip experiments)
7. Input/audio and diagnostics

Runtime and graphics integrations must be evidence-based. Avoid mock launch success, fake compiler output, or fabricated compatibility results.
