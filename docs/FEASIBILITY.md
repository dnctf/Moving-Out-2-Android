# Runtime Feasibility

Target: Moving Out 2 Windows x64 / Unity IL2CPP on Android ARM64.

Feasibility remains unproven. Required experiments:
- Confirm executable PE x86_64 and Unity player dependencies using user-supplied game installation files.
- Build or obtain legally distributable Wine ARM64 and Box64 components with compatible licenses.
- Test Wine API coverage and x86_64 translation independently.
- Validate memory footprint, thread/signal behavior, filesystem mapping and CPU translation performance.
- Confirm Unity Player initialization without claiming gameplay.
- Record exact device model, Android release, SoC/GPU, Vulkan API/extension list, RAM and thermal observations.

A launcher CI pass is not runtime compatibility evidence.