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
- [x] Add bounded Unity version, metadata magic/version and PE architecture detection to selected game folder validation (file evidence only)
- [ ] Unity 2021 / IL2CPP target-specific requirements and dependency checks
- [x] Optional DXVK x64 DLL and Turnip ARM64 driver/ICD presence assessment in runtime bundle (presence only)
- [ ] Vulkan surface/swapchain, memory heap and presentation testing
- [ ] DXVK feasibility/integration and Turnip assessment for Adreno
- [ ] Real first-frame test gate (capture and evidence, not simulated)
- [ ] Controller/touch input and audio routing
- [ ] Controller Phase 2: virtual overlay behavior, layout, multi-touch, customization and persistence
- [ ] Controller Phase 3: physical gamepad discovery, axis/key translation, hotplug and release safety
- [ ] Controller Phase 4: shared runtime input bridge (SDL/native/Wine/Box64/Unity; integrations must be real)
- [ ] Controller Phase 5: per-game mapping profiles, presets, remap UI, import/export and persistence
- [ ] Inspect Flipendo and POP T2T Android controller research repos
- [ ] Research game-specific native recompilation/native ports and capability registry
- [ ] Suspend/resume, exit, crash recovery and thermal/performance behavior
- [ ] Reproducible release APK and build report

Mark items complete only after evidence is collected.
