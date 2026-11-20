# Graphics Pipeline

Investigate graphics before integration:
- Identify actual Direct3D/API requirements for this game build.
- Determine DXVK version/build compatibility and Wine integration requirements.
- Probe Android Vulkan loader, API version, device extensions, memory heaps and surface support on target devices.
- Snapdragon/Adreno: assess Mesa Turnip availability, version compatibility and packaging/license constraints.
- Mali/other GPUs: do not assume Turnip; assess system Vulkan driver capabilities independently.

No GPU/driver compatibility is asserted until a real probe and device test are recorded.