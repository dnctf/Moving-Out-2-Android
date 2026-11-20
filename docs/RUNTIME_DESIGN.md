# Runtime Design

## Non-negotiable states
- `RUNTIME_NOT_INSTALLED`: native runtime integration is absent.
- `MISSING_NATIVE_COMPONENTS`: required native components are missing or invalid.
- `BUNDLE_VALIDATION_ONLY`: bundle layout/header validation passes; launch is still blocked.
- No mocked launch or rendering success.

## Layers
1. Android launcher and SAF installation management.
2. Runtime session state machine, process supervision and diagnostics.
3. Native runtime bundle validation/import.
4. Wine ARM64 integration and prefix management.
5. Box64 x86_64 translation.
6. Graphics translation (DXVK/Vulkan) and device-specific driver selection.
7. Audio/input bridges.

## Phase 2 bundle validation
`RuntimeBundleValidator` validates a local bundle manifest, safe canonical paths, executable flags, ELF64 little-endian AArch64 headers, and a nonempty library directory. It does **not** verify dynamic dependencies, linker compatibility, ABI/runtime compatibility, or successful execution.

Bundle layout contract is in `docs/RUNTIME_BUNDLE.md`. RuntimeManager deliberately remains `NOT_INSTALLED` even when a bundle passes validation. No launcher starts native components until integration and safety checks are complete.

## Process lifecycle
Native processes may be started only after explicit executable/library validation. Capture stdout/stderr, exit code, process termination, session timestamps and crash diagnostics. Android Activity must not own native process state directly.
