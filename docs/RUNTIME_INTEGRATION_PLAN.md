# Runtime integration research baseline

This repository remains a game-specific launcher and compatibility project. The runtime-import UI and manifest are not a generic Android Wine runtime; they are a validated staging mechanism only.

## Source baselines to study
- [Winlator coordinator repository](https://github.com/brunodev85/winlator): treat the root repository as a coordinator with component/submodule boundaries, not as a single self-contained source tree.
- [Winlator app source](https://github.com/brunodev85/winlator-app): Android application/runtime orchestration reference.
- [WinLauncher](https://github.com/Prathvirajkharvi/WinLauncher): reference for a separated runtime engine, import/deployment and diagnostics; its README also describes limitations and device-verification gaps.
- Upstream components to evaluate independently: Wine (Android fork/patchset), Box64, PRoot/root filesystem, Mesa Turnip or system Vulkan, DXVK/VKD3D. Do not treat a component name/version as a compatible stack by itself.

Do not transplant source or binary components without recording upstream commit, license, patch delta, ABI and build provenance.

## Android executable restriction
The current importer copies ARM64 executables into app-private writable storage and sets executable permission. This **does not prove those files can be executed** on Android 10+; platform security policy may reject direct execution from app-data paths. A native process-launch layer may need a carefully audited anonymous-file strategy (e.g. memfd/execveat), or another supported deployment strategy.

Before implementation:
1. Verify exact Android/kernel API availability and security behavior.
2. Audit process-spawn design for multithreaded ART safety; do not perform unsafe post-fork work.
3. Test launch of a harmless, purpose-built ARM64 probe on a real non-root device.
4. Capture errno, API level, kernel version and SELinux denial evidence; never silently mark execution as successful.
5. Keep imported binaries inactive until this gate passes.

## Integration gates
### A. Runtime component provenance
Record exact Wine/Box64/PRoot/rootfs/glibc/Mesa/DXVK source commits, build flags, ABI, licensing and SHA-256 checksums. Create a component manifest and dependency inventory.

### B. Native process launch
Implement a narrowly scoped ARM64 process launcher with explicit argument/environment handling, stdout/stderr capture, PID/exit supervision and stop/timeout handling. Test executable permission, dynamic linker, dependency resolution, cancellation and crash recovery on-device. Android process restrictions must be validated before integrating game launch.

### C. Container / filesystem
Establish a private, bounded root filesystem and path mapping. Validate extraction paths, storage quotas, rollback, permissions and Wine prefix creation. Game files remain selected from user-owned storage and are staged only as required.

### D. Wine + Box64 bootstrap
Only after A-C pass, run Wine initialization (wineboot or the fork's documented bootstrap), test a minimal x86_64 PE utility, then probe Unity/IL2CPP requirements. The invocation is fork-specific; do not hardcode a community command without validating it against the exact runtime build.

### E. Graphics
Vulkan diagnostics currently enumerate the loader and first physical device/extension list. Next gates: Vulkan surface/presentation and swapchain, device memory/feature details, actual DXVK compatibility, Turnip driver packaging/version constraints, and real Unity renderer initialization.

### F. Game milestones
- Runtime process starts and remains supervised.
- Wine prefix bootstrap succeeds.
- Minimal x86_64 PE executes.
- Moving Out 2 Unity player reaches initialization.
- First frame captured and reproducible.
- Input/audio, suspend/resume, crashes, thermals and gameplay tested.

Each milestone must be evidenced independently. A successful Android APK build or bundle validation does not count as any runtime milestone.
