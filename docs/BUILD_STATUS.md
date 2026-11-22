# Build Status

Status last verified: 2026-11-22

## CI baseline

- Branch: `main`
- Verified commit: `dc258f56ca44ad2bee71e2b5ef6c456b42a88411`
- Android CI: **PASS** — Run #446 (2026-11-21)
- Run: https://github.com/dnctf/Moving-Out-2-Android/actions/runs/44916947448
- Completed steps: JDK 17, Android SDK packages, MinGW-w64, runtime bundle script syntax, MO2X protocol layout, Wine XInput DLL build/package, Gradle setup, unit tests, debug APK build, packaged APK verification, artifact upload.
- APK artifact: `moving-out-2-android-alpha` (1,619,541 bytes; SHA-256 `38bab3947efa3653ee9de857b18fbb3e5e70f153f32ba10bca1ef1fcc225e254); expires 2026-12-05.
- Artifact download: https://github.com/dnctf/Moving-Out-2-Android/actions/runs/44916947448
- Historical run 44693974536 was **cancelled** during Android SDK setup; no APK artifact was produced.

## Foundation status

- Installation validator: six-stage validation and filesystem fixture tests exist; case-insensitive paths, required-file/zero-byte checks, PE architecture, IL2CPP metadata, Unity-version detection limitations, and runtime-not-installed launch blocking are covered.
- SAF installation manager: separate DocumentFile-based validation; permission revocation is represented as a validation blocker. SAF permission revocation requires Android instrumentation/device coverage.
- Runtime foundation: bundle manifest validation, runtime state/session/process abstraction, watchdog cleanup, bounded diagnostics, and launch blocking when native components are missing are implemented and unit-tested.
- Compatibility/graphics: device diagnostics and Vulkan native probing exist. SoC-derived GPU labels are heuristics; Vulkan probing is not swapchain/DXVK/Turnip/game-rendering certification.
- UI: home/installation/diagnostics views have extracted UI classes, but `MainActivity` still owns substantial navigation, picker, settings, input and orchestration code. Thin-Activity refactor remains open.
- Native Wine ARM64 + Box64 runtime integration and real device/game launch are **not complete**. No game files or proprietary binaries are committed.

## Next P0/P1 work

1. Continue thin-Activity decomposition behind existing UI/state seams, with regression tests.
2. Align SAF and filesystem validator behavior and add comparable fixture coverage where feasible.
3. Expand real installation-validation fixtures (malformed directory structures, boundary/truncated PE and metadata cases).
4. Improve runtime/device diagnostics and native-runtime integration readiness without faking launch success.
5. Preserve CI APK verification/artifact upload as a release gate.

A successful Android launcher build does **not** imply game compatibility or gameplay. Runtime readiness remains `RUNTIME_NOT_INSTALLED` until lawful native components are integrated and validated.
