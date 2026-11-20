# Project status

## Current stage
Phase 2 runtime bundle import/validation foundation plus Phase 3 native Vulkan diagnostics groundwork.

## Implemented
- Android ARM64 debug launcher and CI build.
- SAF game folder selection and basic file/structure validation.
- Runtime state/process/diagnostic foundation.
- User-provided runtime bundle import through SAF, streamed into private staging storage, bounded by entry/depth/size limits, manifest path confinement, validation before activation, and rollback path.
- Runtime bundle structural validation: required manifest keys/version/ABI, nonempty executable files, ELF64 little-endian AArch64 headers and library directory.
- Optional graphics payload inventory: DXVK x64 DLL header checks and Turnip ARM64 driver + ICD JSON presence checks; does not load either.
- Game import profile detection: bounded Unity version scan, IL2CPP metadata magic/version and game PE architecture.
- Native JNI Vulkan probe: loader API version, physical device enumeration, first device properties/API/vendor/device ID and device extension list.
- Async launcher display of graphics probe summary.

## CI evidence required
- Android Gradle build, C++ NDK/CMake build, and unit tests must pass on the latest commit.
- A successful CI build does not verify native Vulkan behavior on a device.

## Not yet implemented or verified
- Wine ARM64/Box64 native runtime integration and executable process launch.
- Native runtime dependency checks, dynamic loader compatibility and Wine prefix initialization.
- Vulkan surface/swapchain, memory heaps, driver-specific features, Turnip/DXVK integration.
- Unity/IL2CPP game compatibility, first frame, gameplay, input/audio, suspend/resume.
- Physical Android device test, performance, thermals and crash recovery.

Do not interpret launcher build, runtime bundle validation, or Vulkan enumeration code as proof that Moving Out 2 runs.
