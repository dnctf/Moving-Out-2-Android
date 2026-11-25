# Build Status

Status last verified: 2026-11-25

## CI baseline

- Branch: `main`
- Latest verified code commit: `051808a2397f5e3b9f0c3499b6d45b699bdfec53`
- Android CI: **PASS** — Run #471 (2026-11-25)
- Run: https://github.com/dnctf/Moving-Out-2-Android/actions/runs/45531502095
- Verified steps: JDK 17, Android SDK packages, MinGW-w64, runtime packager syntax, MO2X protocol layout, Wine XInput DLL build/package, Gradle setup, unit tests, debug APK build, APK content verification, artifact upload, and post-job cleanup.
- SDK retry mitigation: package installation succeeded on attempt 1/3; `adb`, `android.jar`, `aapt`, NDK `clang`, and CMake were verified.
- APK artifact: `moving-out-2-android-alpha` (1,630,179 bytes; uploaded ZIP SHA-256 `7f1b9f932658f75ee21a7e66c36d1cc5db7644ba048770fd369438ea97841321`); expires 2026-12-09.
- Artifact download: https://github.com/dnctf/Moving-Out-2-Android/actions/runs/45531502095/artifacts/14984807202
- Run #470 verified unsigned PE section RVA/raw-pointer hardening and the SDK retry workflow. Run #471 verified JSON-manifest ELF architecture enforcement and its new fixture tests.
- Run #469 failed before Gradle/tests because SDK Manager reported an archive read error while extracting NDK `27.2.12479018`. The retry and critical-tool verification added in the next commit were exercised successfully by Run #470.
- Run #466 failed one `GamepadTranslatorTest` regression assertion; non-finite trigger handling was corrected and Run #467 passed.
- Run #461 failed Kotlin compilation in `VirtualControllerEngine.kt` due to type inference; subsequent mainline changes corrected the issue and Run #468 passed.
- Run #460 failed one installation metadata fixture assertion; continue expanding validator regression coverage.
- Historical run 44693974536 was **cancelled** during Android SDK setup, before Gradle/build/test/artifact steps. It was not a source compilation failure and produced no APK.

## Foundation status

- Architecture packages exist for core model/state/config/logging, installation, runtime, compatibility, graphics, input, audio, settings, and extracted UI views/state.
- Installation validation: filesystem and SAF validators expose staged checks for access, required files, metadata structure, PE architecture, compatibility, and launch readiness. Continue parity testing; SAF permission revocation requires Android instrumentation/device coverage and cannot be fully established by JVM filesystem fixtures.
- PE inspection: bounds-checked PE32+ analysis covers machine type, unsigned section/RVA mapping, import descriptors, bounded import names, and malformed input. Boundary regression tests passed Run #470. This is an analyzer only, not a binary loader or a guarantee of Unity/metadata version identification.
- Runtime bundle validation: both legacy properties manifests and JSON manifests now require Box64, Wine, and wineserver to be executable ELF64 little-endian AArch64 files. JSON-manifest tests cover a valid AArch64 bundle and reject an x86_64 executable even when its SHA-256 matches the manifest.
- Runtime: bundle manifest validation, runtime state/session/process abstractions, watchdog cleanup, and diagnostics exist. Launch must remain blocked when native Wine/Box64 components are missing or invalid; report `RUNTIME_NOT_INSTALLED` / `MISSING_NATIVE_COMPONENTS` honestly.
- Compatibility/graphics: device diagnostics and Vulkan native probing exist. GPU-family labels are heuristics; Vulkan capability probing is not swapchain, DXVK/Turnip, or game-rendering certification.
- UI: extracted home/installation/diagnostics/settings state and views exist, but `MainActivity` remains approximately 400 lines and still owns picker, settings, input, and orchestration logic. Thin-Activity decomposition remains open.
- Native Wine ARM64 + Box64 runtime integration and real device/game launch are **not complete**. No proprietary game files or game binaries are committed.

## Next P0/P1 work

1. Continue thin-Activity decomposition into dedicated screen/coordinator modules with lifecycle regression tests.
2. Align SAF and filesystem validation semantics and add Android instrumentation for permission revocation.
3. Expand installation fixture coverage for malformed metadata, PE boundary cases, zero-byte files, and invalid folder structures.
4. Improve runtime process supervision, exit diagnostics, and device capability reporting without claiming a launch when native components are unavailable.
5. Keep CI unit tests, debug APK content verification, and artifact upload as a release gate.

A successful Android launcher build does **not** imply game compatibility or gameplay. Runtime readiness remains blocked until lawful native components are integrated, validated, and exercised on a physical device.
