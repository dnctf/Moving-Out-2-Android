# Build Status

Status last verified: 2026-11-25

## CI baseline

- Branch: `main`
- Latest verified code commit: `30b464e12df9ace817a2de65d2197ce4d6f5be46`
- Android CI: **PASS** — Run #468 (2026-11-24)
- Run: https://github.com/dnctf/Moving-Out-2-Android/actions/runs/45363557567
- Verified steps: JDK 17, Android SDK packages, MinGW-w64, runtime packager syntax, MO2X protocol layout, Wine XInput DLL build/package, Gradle setup, unit tests, debug APK build, APK content verification, and artifact upload.
- APK artifact: `moving-out-2-android-alpha` (1,630,191 bytes; uploaded ZIP SHA-256 `b9a7c947ad214736f6eb350529a4fe84a6c9878dd0ff9d114915e6c6602238ab`); expires 2026-12-08.
- Artifact download: https://github.com/dnctf/Moving-Out-2-Android/actions/runs/45363557567/artifacts/14906650972
- Run #466 failed one `GamepadTranslatorTest` regression assertion; the non-finite trigger handling was corrected and Run #467 passed.
- Run #461 failed Kotlin compilation in `VirtualControllerEngine.kt` due to type inference; later mainline changes corrected the compilation issue and Run #468 passed.
- Run #460 failed one installation metadata fixture assertion. The current green baseline includes subsequent commits; continue expanding validator regression coverage.
- Historical run 44693974536 was **cancelled** during Android SDK setup, before Gradle/build/test/artifact steps. It was not a source compilation failure and produced no APK.

## Foundation status

- Architecture packages exist for core model/state/config/logging, installation, runtime, compatibility, graphics, input, audio, settings, and extracted UI views/state.
- Installation validation: filesystem and SAF validators expose staged checks for access, required files, metadata structure, PE architecture, compatibility, and launch readiness. Continue parity testing; SAF permission revocation requires Android instrumentation/device coverage and cannot be fully established by JVM filesystem fixtures.
- PE inspection: bounds-checked PE32+ analysis covers machine type, section/RVA mapping, import descriptors, bounded import names, and malformed input. It is an analyzer only, not a binary loader or a guarantee of Unity/metadata version identification.
- Runtime: bundle manifest validation, runtime state/session/process abstractions, watchdog cleanup, and diagnostics exist. Launch must remain blocked when native Wine/Box64 components are missing or invalid; report `RUNTIME_NOT_INSTALLED` / `MISSING_NATIVE_COMPONENTS` honestly.
- Compatibility/graphics: device diagnostics and Vulkan native probing exist. GPU-family labels are heuristics; Vulkan capability probing is not swapchain, DXVK/Turnip, or game-rendering certification.
- UI: extracted home/installation/diagnostics/settings state and views exist, but `MainActivity` remains approximately 400 lines and still owns picker, settings, input, and orchestration logic. Thin-Activity decomposition remains open.
- Native Wine ARM64 + Box64 runtime integration and real device/game launch are **not complete**. No proprietary game files or game binaries are committed.

## Next P0/P1 work

1. Run PE parser boundary regression tests for unsigned section raw pointers and continue malformed PE/metadata fixture coverage.
2. Continue thin-Activity decomposition into dedicated screen/coordinator modules with lifecycle regression tests.
3. Align SAF and filesystem validation semantics and add Android instrumentation for permission revocation.
4. Improve runtime process supervision, exit diagnostics, and device capability reporting without claiming a launch when native components are unavailable.
5. Keep CI unit tests, debug APK content verification, and artifact upload as a release gate.

A successful Android launcher build does **not** imply game compatibility or gameplay. Runtime readiness remains blocked until lawful native components are integrated, validated, and exercised on a physical device.
