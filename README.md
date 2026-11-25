# Moving Out 2 Android

Android ARM64 compatibility launcher, emulation supervisor, and runtime integration architecture for **Moving Out 2** (Windows x64 Unity/IL2CPP).

> **Status (P0.1 – P2 Complete):** Process supervisor, isolated prefix bootstrap, Vulkan swapchain presentation probe, transactional ZIP importer with rollback, unified game registry, XInput shared-memory IPC binding, runtime settings consumer, lightweight telemetry HUD overlay, and modernized Node 24 CI are fully integrated and verified via automated test suites. Actual gameplay and sustained frame-rates require physical validation on target Qualcomm Snapdragon devices.

*Independent project, not affiliated with Team17 or SMG Studio.*

---

## 1. Target Hardware & Specifications

- **Game:** Moving Out 2 (Windows x64)
- **Target Engine:** Unity 2021.3.16f1 / IL2CPP (64-bit PE32+)
- **Host Architecture:** Android ARM64 (`arm64-v8a`), API 26 (Android 8.0) to API 35 (Android 15)
- **Target Hardware Range:** Qualcomm Snapdragon 855+ (Adreno 640) up to Snapdragon 888 (Adreno 660) / 8 Gen 1+ (reference device: Huawei MatePad DBY2-W09 / Snapdragon 865/870).
- **Minimum Performance Target:** 30 FPS sustained at 70–75% resolution scale with conservative Dynarec flags.

*Notice:* Users must provide legitimate, user-owned game files. This repository does not distribute game files, proprietary game assets, or native runtime binaries.

---

## 2. Emulation Stack & Runtime Architecture (P0.1 – P2)

```
┌────────────────────────────────────────────────────────────────────────┐
│                        Moving Out 2 Android App                        │
│                                                                        │
│  ┌──────────────────────┐  ┌─────────────────────┐  ┌────────────────┐ │
│  │     MainActivity     │  │    GameSurfaceHost  │  │ Telemetry HUD  │ │
│  │   (UI, SAF & Flow)   │  │   (SurfaceView KHR) │  │  (FPS/RAM/Thm) │ │
│  └──────────┬───────────┘  └──────────┬──────────┘  └────────┬───────┘ │
│             │                         │                      │         │
│  ┌──────────▼───────────┐  ┌──────────▼──────────┐           │         │
│  │     GameRegistry     │  │ VulkanNativeProbe   │           │         │
│  │ (SAF & ZIP SSOT)     │  │ (VkSwapchainKHR)    │           │         │
│  └──────────┬───────────┘  └─────────────────────┘           │         │
│             │                                                │         │
│  ┌──────────▼────────────────────────────────────────────────▼──────┐  │
│  │                    WineSessionSupervisor                          │  │
│  │  State Machine: IDLE -> VALIDATING -> BOOTSTRAP -> WINESERVER -> │  │
│  │                 LAUNCH -> RUNNING -> STOPPING -> STOPPED/FAILED   │  │
│  └──────────┬────────────────────────────────────────────────────────┘  │
│             │                                                          │
│  ┌──────────▼───────────┐  ┌─────────────────────┐  ┌────────────────┐ │
│  │  ControllerInput     │  │ RuntimeSettings     │  │ AndroidExec    │ │
│  │  Coordinator (Touch) │  │ Consumer (Dynarec/  │  │ Capability    │ │
│  │  & Gamepad Translator│  │ DXVK HUD/Resolution)│  │ Probe (SELinux)│ │
│  └──────────┬───────────┘  └─────────────────────┘  └────────────────┘ │
└─────────────┼──────────────────────────────────────────────────────────┘
              │ Shared Memory Seqlock IPC (128 B)
              ▼
┌────────────────────────────────────────────────────────────────────────┐
│                    Linux Host Layer (Android OS)                       │
│  /proc/self/status | PowerManager Thermal | ANativeWindow              │
│  Host Box64 Executable (ELF64 AArch64)                                 │
└─────────────┬──────────────────────────────────────────────────────────┘
              │ User-space Emulation (x86_64 to ARM64 Dynarec)
              ▼
┌────────────────────────────────────────────────────────────────────────┐
│                    Wine & Guest Runtime (x86_64)                       │
│  • wineserver -f -p (Persistent process supervisor & socket polling)   │
│  • WINEPREFIX (Isolated per game: filesDir/wine_prefixes/<game_id>)     │
│  • System32 Overrides: WINEDLLOVERRIDES="d3d11,dxgi,xinput1_3,...=n,b" │
│  • Native Guest DLLs: xinput1_3.dll & xinput1_4.dll (MO2X reader)      │
│  • DXVK (Direct3D 11 -> Vulkan) & Turnip Freedreno Vulkan Driver      │
│  • Target Game Executable: mo2.exe (Unity IL2CPP x64)                  │
└────────────────────────────────────────────────────────────────────────┘
```

### Module Breakdown

1. **P0.1 — ABI Integrity & Executable Capability Probe:**
   - `RuntimeBinaryInspector`: Strict ELF64 AArch64 host, ELF64 x86-64 guest, and PE32+ DLL binary header verification.
   - `RuntimeManifestParser`: Schema v2 with provenance and SHA-256 validation.
   - `AndroidExecCapabilityProbe`: Preflights executable launch (`box64 --version`), detects SELinux `execute_no_trans` EACCES denials, and provides SHA-256 identical fallback from `nativeLibraryDir/libbox64.so`.
   - `WineLaunchCommand`: Strict separation between host ARM64 `LD_LIBRARY_PATH` and guest x86-64 `BOX64_LD_LIBRARY_PATH` / `BOX64_PATH`.

2. **P0.2 — Wineserver Process Supervisor & Prefix Bootstrap:**
   - `WineSessionSupervisor`: Thread-safe state machine (`IDLE` -> `VALIDATING_PREFLIGHT` -> `BOOTSTRAPPING_PREFIX` -> `STARTING_WINESERVER` -> `LAUNCHING_TARGET` -> `RUNNING` -> `STOPPING` -> `STOPPED` / `FAILED`).
   - Prefix Isolation: Dedicated prefixes per game (`filesDir/wine_prefixes/<game_id>`).
   - Bootstrap Sequence: Automatic `box64 wineboot --init` execution when `system.reg` is missing.
   - Readiness Probe: Active polling of wineserver socket and signal-0 responsiveness before target launch.
   - Structured Logging & Crash Coupling: Combined tagged stdout/stderr (`[WINESERVER]` and `[BOX64-WINE]`), ring buffer, file export, and coupled teardown preventing orphan/zombie processes.

3. **P0.3 — Vulkan SurfaceView & Swapchain Presentation Bridge:**
   - `vulkan_probe.cpp` (C++20 NDK): Queries `ANativeWindow`, initializes `VkSurfaceKHR` via `VK_KHR_android_surface`, validates presentation queue support, creates `VkSwapchainKHR`, and executes an acquire/present test frame.
   - `GameSurfaceHost`: Lifecycle-aware `SurfaceView` component detaching render pipelines safely on `surfaceDestroyed` to prevent native driver SIGSEGV.

4. **P1.1 & P1.2 — ZIP Importer Hardening & Game Registry:**
   - `GameZipImporter`: Destination concurrency lock, transactional staging, pre-swap backup, atomic swap, and fault-injection rollback covering mid-extraction failures and user cancellations.
   - `GameRegistry`: Single source of truth unifying SAF directory scans and internal extracted ZIPs with filesystem validation.

5. **P1.3 & P1.4 — XInput Shared-Memory Binding & Settings Consumer:**
   - `libmo2_input_bridge.so` & `ControllerInputCoordinator`: Lock-free sequence lock (seqlock) IPC over `MO2_XINPUT_SHM_PATH`. Merges touch multi-touch, dominant thumbsticks, and physical gamepads. Guarantees deterministic neutral zeroing on pause or backgrounding.
   - `RuntimeSettingsConsumer`: Maps user preferences (resolution scaling, 30/45/60 FPS limits, graphics presets, Turnip ICD, Box64 dynarec profiles) into runtime launch plans.

6. **P2 — CI Modernization & Telemetry Overlay:**
   - Modernized GitHub Actions targeting Node 24 natively (`actions/checkout@v6`, `actions/setup-java@v6`, `gradle/actions/setup-gradle@v6`, `actions/upload-artifact@v6`).
   - C++20 standard compliance across all native components.
   - `TelemetryCollector` & `TelemetryOverlayView`: Real-time HUD calculating rolling FPS, frame times, `/proc/self/status` RAM metrics (`VmRSS`, `VmSize`), and `PowerManager` thermal throttling alerts.

---

## 3. Technical Verification: Automated vs Physical Hardware

To maintain rigorous engineering honesty, project verification is explicitly divided into two domains:

| Component / Layer | Automated CI / JVM / NDK Status | Physical Hardware Requirement |
| :--- | :--- | :--- |
| **Kotlin Unit Tests** | **PASS (200+ unit tests)** | Verified in standard CI runners |
| **C++20 Native Code** | **PASS (NDK Clang build)** | Verified in standard CI runners |
| **MO2X Shared-Memory Layout** | **PASS (layout_test assertions)** | Verified across bitmask/alignment bounds |
| **XInput DLL Assets** | **PASS (MinGW-w64 build)** | Packaged in APK `assets/runtime/` |
| **Box64 Execution & SELinux** | Simulated / Validated in JVM | **Requires Qualcomm hardware (ADB shell)** |
| **Turnip Adreno ICD Load** | Schema & Path Validated | **Requires Snapdragon Adreno 6xx GPU** |
| **Vulkan Swapchain Presentation** | Native JNI Bridge Compiled | **Requires physical SurfaceView present test** |
| **In-game 30 FPS Performance** | Mathematical Config Ready | **Requires physical benchmark with telemetry** |

---

## 4. How to Build & Run Tests

### Prerequisites
- JDK 17 (Temurin recommended)
- Android SDK 35 (`platforms;android-35`, `build-tools;35.0.0`)
- Android NDK `27.2.12479018` & CMake `3.22.1`
- MinGW-w64 (`x86_64-w64-mingw32-gcc-posix`) for XInput DLL assets

### Compile Native Assets & Run Tests
```bash
# 1. Verify MO2X C++20 shared memory layout
g++ -std=c++20 -Wall -Wextra -Werror native/wine-xinput/layout_test.cpp -I native/wine-xinput -o /tmp/mo2x-test
/tmp/mo2x-test

# 2. Build Wine XInput DLLs
chmod +x native/wine-xinput/build_wine_xinput.sh
MINGW_CC=x86_64-w64-mingw32-gcc-posix native/wine-xinput/build_wine_xinput.sh native/wine-xinput/out
mkdir -p app/src/main/assets/runtime
cp native/wine-xinput/out/xinput1_3.dll app/src/main/assets/runtime/
cp native/wine-xinput/out/xinput1_4.dll app/src/main/assets/runtime/

# 3. Run all unit tests
gradle --no-daemon testDebugUnitTest

# 4. Assemble Debug APK
gradle --no-daemon assembleDebug
```

---

## 5. Physical Snapdragon ADB Verification Checklist

Connect target device (Snapdragon 855+ / 865 / 870 / 888) via ADB:

```bash
# 1. Install debug APK
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 2. Grant permissions
adb shell pm grant com.dnctf.movingout2 android.permission.READ_EXTERNAL_STORAGE

# 3. Verify native libraries in app directory
adb shell "run-as com.dnctf.movingout2 ls -la lib/"

# 4. Check Vulkan loader and Adreno driver on device
adb shell "dumpsys SurfaceFlinger | grep -i vulkan"

# 5. Monitor real-time logs during launch
adb logcat -s MO2XInput:V MO2Runtime:V AndroidExecProbe:V [WINESERVER]:V [BOX64-WINE]:V
```


---

## 7. Downloads & Pre-Releases

This project publishes the Android application and the emulation runtime as **separate release assets**. Install both components; the APK does not contain the full Wine/Box64 runtime bundle.

### Latest Runtime Bundle

- **Runtime release:** [Moving Out 2 Runtime 0.1.0 (pre-release)](https://github.com/dnctf/Moving-Out-2-Android/releases/tag/runtime-v0.1.0-23)
- **Runtime archive:** [moving-out-2-runtime-arm64-v8a.zip](https://github.com/dnctf/Moving-Out-2-Android/releases/download/runtime-v0.1.0-23/moving-out-2-runtime-arm64-v8a.zip)
- **Runtime checksums:** [SHA256SUMS.txt](https://github.com/dnctf/Moving-Out-2-Android/releases/download/runtime-v0.1.0-23/SHA256SUMS.txt)

Runtime package schema v2 contains the ARM64 Box64 host binary, x86_64 Wine executables and guest libraries, DXVK D3D11/DXGI DLLs, XInput 1.3/1.4 DLLs, and a manifest with component ABI declarations and SHA-256 hashes. Optional Turnip components are included only when supplied to the packager.

### Android APK Pre-Release

APK pre-releases are published independently by the **Publish Android APK Pre-Release** GitHub Actions workflow. Each release includes:
- ARM64 Android APK (release variant; currently unsigned unless signing is configured).
- `SHA256SUMS.txt` for integrity verification.
- Release notes describing the build, installation, limitations and test status.

Open [GitHub Releases](https://github.com/dnctf/Moving-Out-2-Android/releases) and select the latest `app-v*` pre-release. The APK release workflow is manually triggered from **Actions → Publish Android APK Pre-Release → Run workflow**. The default version is `0.2.0-alpha.1`.

### Installation Order

1. Install the APK. If Android reports a signature conflict with an older build, uninstall the older build first; this release workflow currently produces an unsigned APK.
2. Open the app and use its runtime setup/download flow, or download the runtime ZIP above and import it through the app if prompted.
3. Import your own legally obtained Moving Out 2 Windows game directory/archive. The game itself is not distributed here.
4. Connect a controller or configure the on-screen controls, then launch only after the app reports that game files and runtime validation have passed.

### Compatibility & Known Limitations

- APK target: Android ARM64 `arm64-v8a`; minimum Android 8.0 (API 26), target SDK 35.
- Intended test range: Qualcomm Snapdragon 855-class and newer Adreno devices. This is a target range, not a guarantee of compatibility.
- Huawei DBY2-W09 / Snapdragon platform validation, sustained gameplay, Vulkan presentation stability, thermal behavior and FPS are still physical-device test items unless a release explicitly reports otherwise.
- CI success means automated compilation, package-content checks and unit tests passed. It does **not** prove the game boots or reaches playable performance on every device.
- Users must provide their own game files. No proprietary game data is included.
- This is an independent compatibility project and is not affiliated with Team17 or SMG Studio.

### Verify an APK Download

Linux:
```bash
sha256sum -c SHA256SUMS.txt
```

If the checksum file contains a filename different from the downloaded APK filename, compare the hash manually:
```bash
sha256sum Moving-Out-2-Android-*-arm64-v8a-unsigned.apk
cat SHA256SUMS.txt
```


## 8. License & Trademarks

- Moving Out 2 is a registered trademark of Team17 / SMG Studio.
- Source code in this repository is licensed under the MIT License.
