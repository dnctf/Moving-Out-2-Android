# Graphics Pipeline

## Current diagnostics implementation (Phase 3 foundation)
- Native JNI probe calls the Android Vulkan loader.
- Enumerates the loader API version, physical devices, the first physical-device properties, device API version, vendor/device IDs and that device's extension names.
- EGL/GLES renderer probing remains a fallback for display-name hints.
- The launcher runs the probe asynchronously and displays a status summary.

This is a read-only diagnostic probe. It does not create a Vulkan surface, enumerate memory heaps, test swapchain/presentation, verify DXVK, install or verify Turnip, or demonstrate rendering/game output. No physical Android device test is recorded.

## Integration research
- Identify actual Direct3D/API requirements for this game build.
- Determine DXVK version/build compatibility and Wine integration requirements.
- Snapdragon/Adreno: assess Mesa Turnip availability, version compatibility and packaging/license constraints.
- Mali/other GPUs: do not assume Turnip; assess system Vulkan driver capabilities independently.

The native probe is not a compatibility certification. First-frame evidence remains a separate launch gate.
