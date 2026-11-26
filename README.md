# Moving Out 2 Android

[![Android CI](https://github.com/dnctf/Moving-Out-2-Android/actions/workflows/android-ci.yml/badge.svg?branch=main)](https://github.com/dnctf/Moving-Out-2-Android/actions/workflows/android-ci.yml)
[![Build Runtime Bundle](https://github.com/dnctf/Moving-Out-2-Android/actions/workflows/build-runtime.yml/badge.svg?branch=main)](https://github.com/dnctf/Moving-Out-2-Android/actions/workflows/build-runtime.yml)
[![Latest Pre-Release](https://img.shields.io/github/v/release/dnctf/Moving-Out-2-Android?include_prereleases&label=latest%20pre-release)](https://github.com/dnctf/Moving-Out-2-Android/releases)
![Target ABI](https://img.shields.io/badge/target%20ABI-arm64--v8a-blue)
![Minimum Android](https://img.shields.io/badge/minimum%20Android-8.0%20(API%2026)-green)

**Moving Out 2 Android** is a dedicated Android compatibility launcher and runtime integration project for the Windows x86-64 release of *Moving Out 2*. It combines an ARM64 Box64 host, an x86-64 Wine/DXVK guest stack, controller translation, and a Vulkan presentation path; it is not a general-purpose desktop container.

> **Legal notice:** This repository does **not** include *Moving Out 2* game files or copyrighted game assets. Users must provide their own legally obtained PC copy. This independent project is not affiliated with or endorsed by Team17 or SMG Studio.

## Contents

- [Architecture overview](#architecture-overview)
- [Android device requirements](#android-device-requirements)
- [Developer build requirements](#developer-build-requirements)
- [Build from source](#build-from-source)
- [Install and use on Android](#install-and-use-on-android)
- [CI and release automation](#ci-and-release-automation)
- [Development status and roadmap](#development-status-and-roadmap)
- [Troubleshooting and diagnostics](#troubleshooting-and-diagnostics)
- [License and trademarks](#license-and-trademarks)

## Architecture overview

The Android app owns the launcher UI, game-folder access, runtime installation, controller input, diagnostics, and session lifecycle. Box64 is the ARM64 host executable; Wine, DXVK, and the game remain x86-64 guest components.

```text
 Android UI / Play / SurfaceView / Touch & Gamepad
                       |
                       v
       libbox64.so (AArch64, ARM64 Dynarec)
                       |
                       v
 Wine x86_64 + XInput shared-memory bridge
                       |
                       v
          DXVK (D3D11 -> Vulkan)
                       |
                       v
          Android Vulkan loader
                       |
             Adreno GPU / optional Turnip
```

### Component map

| Component | Architecture / ABI | Storage location | Responsibility |
|---|---|---|---|
| Android launcher | Android / `arm64-v8a` | APK; app-private `filesDir` | Game selection, runtime setup, Play action, session supervision, settings, diagnostics |
| Box64 host | ELF64 AArch64 | APK `lib/arm64-v8a/libbox64.so`; Android `nativeLibraryDir` after extraction | Executes x86-64 Linux guest programs using ARM64 dynarec |
| Wine launchers / server | ELF64 x86-64 | Runtime ZIP `wine/bin/`; installed under `filesDir/native_runtime/bundle/` | Windows API compatibility and process management |
| Wine guest libraries | ELF64 x86-64 shared objects | Runtime ZIP `wine/lib/wine/` | Guest-side Wine libraries |
| DXVK | PE32+ x86-64 | Runtime ZIP `dxvk/x64/` | Translates Direct3D 11 to Vulkan |
| XInput bridge | PE32+ x86-64 DLLs | APK `assets/runtime/`; also staged into Wine `x86_64-windows/` | Maps controller state through the MO2X shared-memory protocol |
| Vulkan presentation bridge | Android native / ARM64 | APK `lib/arm64-v8a/libvulkan_probe.so` and Android `SurfaceView` | Probes Vulkan surface and swapchain presentation |
| Optional Mesa Turnip | ELF64 AArch64 shared object + ICD JSON | Optional runtime ZIP `turnip/` | Alternative Freedreno Vulkan userspace driver; not forced by default |
| Runtime manifest | JSON schema v2 | Runtime ZIP root: `runtime-manifest.json` | Role-based ABI declarations, inclusion flags, paths, and SHA-256 validation |

### Runtime and launch flow

1. The app checks the Android host ABI and validates the selected game installation.
2. The installer locates the latest compatible integrated app pre-release, downloads its runtime ZIP and SHA-256 sidecar, and verifies the archive.
3. The ZIP is extracted into staging. Manifest v2, component hashes, binary formats, and executable permissions are checked before activation.
4. Box64 is executed from Android's extracted native-library directory when available. Wine and x86-64 guest libraries remain in the private runtime bundle.
5. The session supervisor starts Wine and the game process. Touch and physical controller input are translated through the XInput shared-memory bridge.
6. DXVK translates D3D11 to Vulkan for the Android Vulkan driver and Adreno GPU.

**Native executable packaging:** `app/build.gradle.kts` sets `packaging.jniLibs.useLegacyPackaging = true` and retains Box64 debug symbols. The integrated release workflow stages the runtime's AArch64 Box64 executable as `app/src/main/jniLibs/arm64-v8a/libbox64.so` before building the APK. The checked-in `AndroidManifest.xml` does not explicitly declare `android:extractNativeLibs`; AGP derives the effective extraction behavior from the packaging configuration. APK packaging checks alone do not prove successful execution under a device's SELinux policy.

See [Runtime Manifest v2](docs/RUNTIME_MANIFEST_V2.md) for the manifest contract.

## Android device requirements

These are intended compatibility targets, not guarantees of performance. Actual results depend on the Vulkan driver, thermal limits, memory pressure, Wine/Box64 compatibility, and game settings.

| Specification | Minimum | Recommended (target benchmark) |
|---|---|---|
| Android OS | Android 8.0 Oreo (API 26) | Android 11–14 (API 30–34). Android 15 / API 35 is the current target SDK. On Android 10+, Box64 should use the packaged native-library path when app-data execution is denied by SELinux policy. |
| CPU / SoC | ARMv8.2-A, `arm64-v8a`, octa-core; Snapdragon 855-class or equivalent | Qualcomm Snapdragon 870 or Snapdragon 888+ (Kryo 585/680 class) |
| GPU / graphics API | Vulkan 1.1-capable GPU and driver | Qualcomm Adreno 650 / 660 or newer. DXVK compatibility depends on driver support for features/extensions such as `VK_KHR_timeline_semaphore`, `VK_EXT_extended_dynamic_state`, and `VK_KHR_buffer_device_address`. |
| RAM | 6 GB | 8 GB or more, with approximately 3 GB free at launch |
| Storage | 6 GB free as an initial floor | More is strongly recommended for the runtime archive, approximately 1.3 GB expanded runtime, Wine prefix, staging/backup space, and the user's game installation. Actual requirements vary by game copy and update state. |
| Input | Touchscreen | Touchscreen virtual controller or Bluetooth / USB gamepad (XInput bridge) |

### Reference device

| Item | Reference configuration |
|---|---|
| Device | Huawei MatePad DBY2-W09 |
| SoC | Qualcomm Snapdragon 888 / SM8350 |
| GPU | Adreno 660 |
| Vulkan | Vulkan 1.1.128 (reported device capability) |
| RAM | 8 GB |
| OS | Android 12 / API 31 |

The reference device is a development target. CI success is not evidence of a successful physical-device game launch or sustained gameplay benchmark.

## Developer build requirements

Versions below are taken from the current Gradle project and Actions workflows. Hosted workflows use GitHub's `ubuntu-latest` x86-64 image; its exact Ubuntu point release is managed by GitHub and can change.

| Tool / environment | Verified configuration |
|---|---|
| Build host | GitHub Actions `ubuntu-latest` x86-64; local Linux is the closest reproduction environment |
| JDK | Eclipse Temurin Java 17 |
| Gradle | 8.9 in Actions |
| Android Gradle Plugin | 8.7.3 |
| Kotlin Gradle Plugin | 2.0.21 |
| Android SDK | Compile SDK 35; target SDK 35; build-tools 35.0.0 |
| Android NDK | 27.2.12479018 |
| Minimum SDK | API 26 |
| CMake | 3.22.1 |
| Native build tools | NDK Clang, CMake, GNU Make / build-essential; Ninja is optional and not explicitly required by current CI |
| XInput cross-compiler | MinGW-w64 POSIX: `gcc-mingw-w64-x86-64-posix`, executable `x86_64-w64-mingw32-gcc-posix` |
| ELF / PE inspection | `readelf` (GNU binutils), `objdump`, `file` |
| Packaging / integrity | `python3`, `zip`, `unzip`, `sha256sum` |
| Workflow validation | `python3-yaml`, `shellcheck`, Bash |

> **Gradle wrapper:** the current repository root does not contain `gradlew` or `gradle/wrapper/gradle-wrapper.properties`. CI explicitly provisions Gradle 8.9. Install/use Gradle 8.9 locally and run the `gradle` commands below; do not use `./gradlew` until a wrapper is added.

## Build from source

### 1. Clone the repository

```bash
git clone https://github.com/dnctf/Moving-Out-2-Android.git
cd Moving-Out-2-Android
```

Install the JDK, Android SDK/NDK, CMake, Gradle 8.9, MinGW-w64, GNU binutils, Python 3, ZIP utilities, and ShellCheck. Set `ANDROID_SDK_ROOT` to your Android SDK installation.

### 2. Build the native XInput bridge DLLs

The script creates both x86-64 Windows DLLs in the output directory passed as its first argument.

```bash
sudo apt-get update
sudo apt-get install -y gcc-mingw-w64-x86-64-posix

chmod +x native/wine-xinput/build_wine_xinput.sh
MINGW_CC=x86_64-w64-mingw32-gcc-posix \
  native/wine-xinput/build_wine_xinput.sh native/wine-xinput/out

file native/wine-xinput/out/xinput1_3.dll
file native/wine-xinput/out/xinput1_4.dll
```

Android CI copies these outputs into `app/src/main/assets/runtime/`. The integrated release workflow also builds and stages them into the APK and runtime bundle.

### 3. Build Box64 and package the runtime bundle

The reproducible Box64 build is defined in [build-runtime.yml](.github/workflows/build-runtime.yml). It fetches a pinned upstream Box64 revision, applies `scripts/patch_box64_standalone.py`, and cross-compiles for `arm64-v8a`, Android API 28, Release mode, with `-DARM64=ON -DARM_DYNAREC=ON`. The workflow also stages Wine x86-64 binaries, downloads a pinned DXVK archive and verifies its SHA-256, builds XInput DLLs, and audits the binary roles.

For local packaging, prepare these inputs first:
- Box64 AArch64 executable.
- Wine x86-64 directory containing `bin/wine64`, `bin/wineserver`, and `lib/wine/`.
- DXVK x86-64 directory containing `d3d11.dll` and `dxgi.dll`.
- XInput output directory containing `xinput1_3.dll` and `xinput1_4.dll`.

Then run the actual packager (replace the example paths with your local paths):

```bash
scripts/package_runtime_bundle.sh \
  --box64 /absolute/path/to/box64 \
  --wine-dir /absolute/path/to/wine \
  --dxvk-dir /absolute/path/to/dxvk/x64 \
  --xinput-dir /absolute/path/to/xinput \
  --bundle-version 0.1.1 \
  --output dist/moving-out-2-runtime-arm64-v8a.zip
```

Optional Turnip support uses `--turnip-so /absolute/path/to/libvulkan_freedreno.so`; the matching `freedreno_icd.aarch64.json` must be beside it. The packager creates a schema-v2 `runtime-manifest.json`, validates its JSON, writes the ZIP, and creates `dist/SHA256SUMS.txt`. CI additionally writes a `.zip.sha256` sidecar and runs the multi-ABI audit.

Audit an extracted bundle and its ZIP:

```bash
mkdir -p /tmp/mo2-runtime-audit
unzip -q dist/moving-out-2-runtime-arm64-v8a.zip -d /tmp/mo2-runtime-audit
scripts/audit_runtime_bundle.sh /tmp/mo2-runtime-audit dist/moving-out-2-runtime-arm64-v8a.zip
unzip -p dist/moving-out-2-runtime-arm64-v8a.zip runtime-manifest.json | python3 -m json.tool >/dev/null
sha256sum --check dist/SHA256SUMS.txt
```

### 4. Run unit tests and build the APK

These are the current CI commands (Gradle 8.9):

```bash
gradle --no-daemon testDebugUnitTest
gradle --no-daemon assembleDebug
```

Build the release variant with:

```bash
gradle --no-daemon assembleRelease
```

The integrated release workflow supplies `libbox64.so`, builds XInput assets, runs tests, assembles the release APK, verifies its signature using Android `apksigner`, checks APK ZIP integrity, confirms the embedded Box64 bytes match the runtime bundle, and verifies required native libraries and DLL assets.

## Install and use on Android

1. **Install the integrated APK.** Download it from [GitHub Pre-Releases](https://github.com/dnctf/Moving-Out-2-Android/releases). The APK includes the ARM64 Box64 native executable and XInput DLL assets. The larger Wine/DXVK runtime is a separate asset in the same integrated release and is downloaded by the app on first launch.
2. **Set up the runtime.** Open the app and allow automatic download and validation. If it fails, use **Repair / download runtime**. Alternatively, choose **Import runtime ZIP and extract** and select `moving-out-2-runtime-arm64-v8a.zip`.
3. **Select the game installation.** Use **Select game folder** or import a game ZIP. The selected game root must contain:
   - `mo2.exe`
   - `GameAssembly.dll`
   - `UnityPlayer.dll`
   - `baselib.dll`
4. **Check readiness.** Wait for runtime execution preflight and game-file validation. Connect a supported gamepad or use the on-screen controls.
5. **Press Play.** Tap **Play Moving Out 2** to start the supervised Wine/Box64 session. The button is enabled only when runtime execution preflight and game-file readiness checks pass.
6. **Export diagnostics if launch fails.** Use **Export diagnostics ZIP** and share the generated archive when reporting an issue. Review it before sharing because logs may contain device and local-path information.

### Release assets and checksums

An integrated pre-release is designed to contain:
- `Moving-Out-2-Android-v<version>-arm64-v8a.apk`
- `moving-out-2-runtime-arm64-v8a.zip`
- `moving-out-2-runtime-arm64-v8a.zip.sha256`
- `SHA256SUMS.txt` covering the APK and runtime ZIP

Verify downloads on Linux:

```bash
sha256sum --check SHA256SUMS.txt
sha256sum --check moving-out-2-runtime-arm64-v8a.zip.sha256
```

Alpha APKs currently use the Gradle debug signing configuration until a persistent private release keystore is configured. CI-generated debug signing identities may differ between runners. If Android rejects an update because the signing certificate differs, uninstall the previous alpha first, then install the new APK. **Uninstalling removes that app's private data**, including the downloaded runtime and settings.

## CI and release automation

| Workflow | Trigger | Main checks / outputs |
|---|---|---|
| [Android CI](.github/workflows/android-ci.yml) | Push / pull request to `main`, or manual dispatch | SDK/NDK setup, protocol layout test, MinGW XInput build, Kotlin unit tests, debug APK build and APK content checks |
| [Build Runtime Bundle](.github/workflows/build-runtime.yml) | Relevant source changes on `main`, or manual dispatch | YAML/shell validation, Python packager tests, XInput build, Box64 ARM64 dynarec build, Wine/DXVK staging, multi-ABI audit, manifest-v2 JSON validation, Kotlin unit tests, runtime ZIP artifact |
| [Publish Integrated Android APK Pre-Release](.github/workflows/publish-apk-prerelease.yml) | Successful Build Runtime Bundle completion on `main` | Downloads matching runtime artifact, embeds `libbox64.so`, builds XInput assets, runs tests, assembles/signature-verifies APK, creates checksums, removes prior releases/tags, publishes one integrated pre-release |

The runtime ZIP is an intermediate artifact. The release workflow consumes the artifact from the matching successful runtime run. The launcher selects the newest published, non-draft integrated app pre-release tagged `app-v*` that contains an APK and the required runtime ZIP/checksum assets. It does not use GitHub's `/releases/latest` endpoint, which excludes pre-releases.

CI success confirms only the automated checks in the workflow. It does **not** prove that the game boots, renders correctly, accepts all controller input, or sustains a particular frame rate on every Android device.

## Development status and roadmap

### Implemented / covered by automated checks

- Box64 standalone AArch64 build configured with ARM dynarec and packaged as APK native library `libbox64.so` by the integrated release workflow.
- Wine x86-64 and DXVK runtime staging, ZIP packaging, SHA-256 sidecars, and schema-v2 manifest generation.
- Multi-ABI validation for AArch64 ELF, x86-64 ELF/shared libraries, and x86-64 PE32+ DLLs.
- Optional Turnip component representation in the manifest and runtime package.
- XInput 1.3 / 1.4 DLL build and shared-memory controller bridge.
- Runtime download with HTTP Range resume/retry, checksum verification, safe ZIP extraction, staging/rollback, and validation.
- Runtime execution preflight with native-library-directory Box64 fallback for Android executable policy restrictions.
- Game folder validation, launcher readiness state, Play action, Wine session supervision, Vulkan surface/swapchain probe, and diagnostic ZIP export.

### Next validation priorities

1. Verify on Huawei DBY2-W09 / Snapdragon 888 / Adreno 660 that Android extracts `libbox64.so` to `ApplicationInfo.nativeLibraryDir` and the real Box64 `--version` preflight succeeds under device SELinux policy.
2. Complete an end-to-end game boot with a legally owned game directory; verify Wine prefix initialization, wineserver lifecycle, Unity startup, and clean shutdown.
3. Validate Vulkan swapchain presentation and DXVK extension compatibility on the physical Adreno driver; test optional Turnip separately rather than assuming it is preferable.
4. Test touchscreen and Bluetooth/USB gamepad behavior in-game, including pause/resume, multitouch, disconnect/reconnect, and neutral-input reset.
5. Collect repeatable FPS, frame-time, RAM, thermal, and long-session stability measurements before making performance claims.

## Troubleshooting and diagnostics

- **Runtime not ready:** retry **Repair / download runtime** and check free storage/network connectivity. The installer verifies release assets and manifest hashes before activation.
- **Game folder not detected:** select the directory containing `mo2.exe`, `GameAssembly.dll`, `UnityPlayer.dll`, and `baselib.dll`, not a parent directory.
- **Box64 permission denied:** export diagnostics and inspect the execution preflight result. The integrated APK must contain `lib/arm64-v8a/libbox64.so`; device-side resolution should use `nativeLibraryDir` when app-private `filesDir` execution is denied.
- **Black screen / Vulkan failure:** export the Diagnostic ZIP and include the reported Vulkan device, driver version, and extension support. A successful native build does not prove swapchain presentation on the device.
- **APK signature conflict:** uninstall the previous alpha before installing a new CI-signed APK if its signing certificate changed. Uninstalling removes app-private runtime data and settings.

## License and trademarks

- Source code is distributed under the [MIT License](LICENSE).
- *Moving Out 2* and related game trademarks/assets belong to their respective rights holders, including Team17 and SMG Studio.
- This repository does not distribute the game executable, game data, or copyrighted game assets.
