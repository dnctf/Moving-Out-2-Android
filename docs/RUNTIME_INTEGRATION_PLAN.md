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

## Moving Out 2 integration profile (implementation staging)

Game import validation now gathers a bounded profile from user-selected files: Unity version string (from `MovingOut2_Data/globalgamemanagers`), IL2CPP metadata magic/version (from `global-metadata.dat`), and PE machine architecture (`mo2.exe`). It reports file evidence only; this is not a runtime compatibility test.


### Runtime bundle payload assessment

The Android launcher can optionally assess user-imported graphics payload directories using manifest keys `dxvk_dir` and `turnip_dir`:
- DXVK: checks x64 PE headers for `d3d11.dll` and `dxgi.dll`.
- Turnip: checks for an ARM64 ELF shared object and an ICD JSON that references `library_path`.

These are static presence checks. **No payload is loaded or injected into the Android Vulkan loader.** The app cannot mark Wine, Box64, DXVK, Turnip or the Unity player as operational from these checks.

### Recommended integration route

Use an established Android Wine/Box64 integration as the upstream architecture and build provenance baseline, rather than attempting to assemble an unpatched Wine/Box64/Mesa stack from arbitrary binaries. The upstream Winlator project lists Wine, Box64, Mesa/Turnip and DXVK as separate components and has Unity-specific stability guidance; this confirms the stack is versioned and configuration-dependent, not a simple executable copy. Review its current source, component versions, licenses and build scripts before porting any module:
- https://github.com/brunodev85/winlator
- https://github.com/brunodev85/winlator-app
- https://github.com/ptitSeb/box64
- https://github.com/doitsujin/dxvk

Avoid wholesale APK or proprietary runtime extraction. Integrate source/build modules with a recorded commit, license, ABI and dependency manifest; keep game assets user-supplied.

### Import test readiness

Next device test should first select the game directory and collect the import/validation report. Save only diagnostic values and filenames, not game content. Required evidence: SAF read permission retained, required files found, PE x64 confirmed, Unity version detected if present, IL2CPP metadata magic/version confirmed, metadata directory present, and no launch-ready claim. This test is independent of runtime bundle import.
