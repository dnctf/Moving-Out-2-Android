# Roadmap

- [x] Establish minimal Android Gradle project and CI
- [x] Implement installation folder selection using SAF
- [x] Validate expected Moving Out 2 files and report missing/invalid items
- [x] Define runtime session state machine and diagnostic log format
- [x] Add runtime bundle manifest/layout and ARM64 ELF structural validator
- [x] Runtime bundle SAF import with streamed copy, limits, staging and rollback
- [x] Native Vulkan loader/physical-device/extension diagnostic probe and launcher display (CI build pending; device behavior unverified)
- [ ] Runtime native dependency inventory and license/redistribution review
- [ ] Build or integrate Wine ARM64 + Box64 in reproducible CI pipeline
- [ ] Android-safe native executable launch strategy and device verification
- [ ] Wine prefix bootstrap and Windows x64 process execution integration
- [ ] Unity 2021 / IL2CPP target-specific requirements and dependency checks
- [ ] Vulkan surface/swapchain, memory heap and presentation testing
- [ ] DXVK feasibility/integration and Turnip assessment for Adreno
- [ ] Real first-frame test gate (capture and evidence, not simulated)
- [ ] Controller/touch input and audio routing
- [ ] Suspend/resume, exit, crash recovery and thermal/performance behavior
- [ ] Reproducible release APK and build report

Mark items complete only after evidence is collected.
