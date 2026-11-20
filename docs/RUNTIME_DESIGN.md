# Runtime Design

## Non-negotiable states
- `RUNTIME_NOT_INSTALLED`: Wine/Box64 native components are absent.
- `MISSING_NATIVE_COMPONENTS`: launch cannot proceed because required native pieces are missing.
- No mocked launch or rendering success.

## Planned layers
1. Android launcher and SAF installation management.
2. Runtime session state machine and diagnostics.
3. Native runtime packaging and process supervision.
4. Wine API compatibility.
5. Box64 x86_64 translation.
6. Graphics translation (DXVK/Vulkan) and device-specific driver selection.
7. Audio/input bridges.

## Process lifecycle
Native processes must be started only after explicit executable/library validation. Capture stdout/stderr, exit code, process termination, session timestamps and crash diagnostics. Android Activity lifecycle must not own native process state directly.