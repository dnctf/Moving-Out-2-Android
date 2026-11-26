# Moving Out 2 Android — Project Skill & Forensic Runtime Notes

> Living project guidance for future engineering, audits, CI triage, and device validation.
> Repository: `dnctf/Moving-Out-2-Android`
> Last updated: 2026-11-26
> Source basis: user-provided forensic inventory report and verified GitHub PR/CI state.
> This document is project guidance, not proof that the game currently runs on Android.

## 1. Mission and engineering boundaries

Build an Android ARM64 compatibility launcher/runtime for the user's legitimately owned Windows x86-64 copy of Moving Out 2. Preserve the existing architecture and prioritize reproducible runtime evidence over feature claims.

- Android host ABI: `arm64-v8a`.
- Windows guest: x86-64 PE/ELF components under Box64/Wine.
- Target game: Moving Out 2 Windows build 1.2.274.
- Unity: 2021.3.16f1, IL2CPP metadata v29, Direct3D 11.
- Reference device: Huawei DBY2-W09, Snapdragon 888 / Adreno 660, Android 12.
- Performance objective: stable 30 FPS is a target only; do not claim it until measured on-device.
- Do not bundle or redistribute proprietary game data. Game files are user-imported through SAF/ZIP.
- Keep Android ARM64 host libraries distinct from x86-64 Wine guest files and Windows PE DLLs.
- Do not integrate cracks, Steam emulators, modified Steam API wrappers, or DRM circumvention. Prefer the legitimate original Steamworks DLL supplied by the user's lawful installation. Do not infer that a game payload is clean merely because it is structurally complete.

## 2. Runtime architecture

Expected execution chain:

1. Android launcher and SAF/ZIP game importer.
2. Validate and stage the game directory transactionally.
3. Validate/extract the runtime bundle and its manifest/checksums.
4. Launch ARM64 Box64 from Android's native library location.
5. Box64 runs x86-64 Wine and its guest dependencies.
6. Wine initializes a win64 prefix and loads `ntdll.so`.
7. Wine launches `mo2.exe` from the imported game directory.
8. D3D11 is translated through DXVK to Vulkan.
9. Vulkan ICD/driver presents through the Android display surface.
10. XInput bridge carries touch/physical controller input into the game.

Important ABI rule:
- `jniLibs/arm64-v8a/` is for Android-loadable ARM64 native libraries (for example Box64 when packaged as the Android native executable, the input bridge, and Vulkan probe).
- Wine x86-64 ELF files, guest glibc, and Windows PE/DLL files are runtime assets, not Android JNI libraries.
- `extractNativeLibs=true` / legacy native packaging and 16 KB ELF LOAD alignment must be checked on the actual APK, not assumed from Gradle configuration.

## 3. Forensic game-data inventory (source report)

The supplied audit report describes the Google Drive folder as containing:

- Game directory: 153 files, 1,425,506,437 bytes (about 1.33 GiB).
- Parent folder plus redistributables: 10 additional files, 49,220,115 bytes (about 46.94 MiB).
- Combined inventory: 163 files, 1,474,726,552 bytes (about 1.37 GiB).
- No zero-byte files were reported in that inventory.
- Build metadata: Moving Out 2 v1.2.274, StandaloneWindows64, release/steam build.
- Main files:
  - `mo2.exe`: 653,824 bytes.
  - `UnityPlayer.dll`: 29,255,192 bytes.
  - `GameAssembly.dll`: 79,041,536 bytes.
  - `baselib.dll`: 405,016 bytes.
  - `mo2_Data/il2cpp_data/Metadata/global-metadata.dat`: 15,397,704 bytes; metadata v29.
  - `mo2_Data/StreamingAssets/aa/StandaloneWindows64/`: 53 Addressables bundles, 884,878,837 bytes.
  - `mo2_Data/StreamingAssets/Audio/`: 58 FMOD banks, 336,634,900 bytes.
  - Unity Burst plugin `lib_burst_generated.dll` is listed.
- The expected Unity root markers for importer detection are `mo2.exe`, `UnityPlayer.dll`, `GameAssembly.dll`, `baselib.dll`, `mo2_Data/globalgamemanagers`, and `mo2_Data/il2cpp_data/Metadata/global-metadata.dat`.
- The report describes an unexpected modified `steam_api64.dll` (426,344 bytes), an original Steamworks DLL retained as `steam_api64.rne` (265,504 bytes), and `steam_emu.ini`. Treat these as a forensic warning, not as a supported runtime requirement. Never wire in the emulator or advise DRM bypass.

### Evidence limitations

The inventory was a structural/metadata audit of a Drive folder, not a binary-level forensic examination. It did not establish:
- SHA-256 hashes or PE header/signature validation for every file.
- Runtime execution on Windows or Android.
- Successful Wine bootstrap, `ntdll.so` loading, DXVK initialization, Vulkan presentation, audio, input, or gameplay.
- Presence of the Android APK/source/runtime artifacts in that Drive folder.

Accordingly, all compatibility causes derived from the inventory (including memory-ordering, Turnip, Media Foundation, crash-handler, and input hypotheses) must be labeled as hypotheses until logs or controlled tests prove them.

## 4. Critical engineering risks and checks

### P0 — Wine guest loader closure
- Validate Wine modules exist and are non-empty, especially `wine/lib/wine/x86_64-unix/ntdll.so` and `win32u.so`.
- Validate corresponding Windows modules such as `kernel32.dll` and `user32.dll`.
- Validate the guest x86-64 loader and glibc closure (for example `ld-linux-x86-64.so.2`, `libc.so.6`, `libm.so.6`, `libpthread.so.0`, `libdl.so.2`, `librt.so.1`) at the actual paths used by Box64.
- Check symlinks and dynamic dependencies; file presence alone is insufficient.
- Set `WINEARCH=win64`, isolate `WINEPREFIX`, and wait for `wineboot --init` to exit successfully.
- Confirm non-empty `system.reg` and `user.reg` before marking prefix bootstrap ready.
- If the error is `could not load ntdll.so`, distinguish missing Wine Unix module, missing guest loader/glibc, bad library search path, wrong architecture, and broken symlink. Do not assume a single cause.

### P0 — Android native execution
- Verify Box64 is the correct ELF64 AArch64 binary and executable from the actual `nativeLibraryDir`.
- Diagnose SELinux/W^X and Android execution restrictions using device logs and a minimal execution probe.
- Verify APK native library extraction/compression behavior and permissions on the installed APK.
- Check all packaged native libraries for 16 KB LOAD segment alignment where required.

### P1 — Game import and staging
- SAF import must support the real nested folder layout, including `Moving.Out.2/Moving Out 2/`.
- Detect the game root by required marker files rather than assuming the selected folder itself is the root.
- Ignore parent-level `_Redist/` and unrelated shortcuts/instructions.
- Prevent path traversal, duplicate/case-colliding paths, truncated archives, and unsafe ZIP expansion.
- Stage to a temporary directory; validate file count/bytes and required markers; atomically promote only after success.
- Never enable Play on a partial or unverified installation.
- Do not hard-code the audit's 153-file count as a universal requirement: different legitimate builds may differ. Use build-aware manifests or marker/size integrity checks.

### P1 — Graphics and surface
- A successful Vulkan device probe is not proof that DXVK or the game can present.
- Validate the Turnip/system ICD selected, DXVK DLL loading, swapchain creation, Android Surface lifecycle, and first presented frame independently.
- Collect DXVK/Wine logs on black screen. Do not claim SurfaceView/WSI integration without a real rendered frame on device.

### P1 — Input and runtime lifecycle
- Validate XInput DLL exports and ABI, shared-memory layout/version, file path translation, and disconnect cleanup.
- Test touch input and physical controller input separately.
- Supervisor must reliably stop child processes, clean shared-memory state, and preserve logs after crashes/timeouts.

### P2 — Audio, video, and crash handling
- Treat FMOD, Windows media codecs, Crashpad, and Unity crash-handler behavior as follow-up compatibility areas.
- Disable or override components only when logs reproduce a specific failure and the change is tested. Do not cargo-cult overrides from an audit hypothesis.

## 5. Launch environment: policy, not universal truth

The project has previously used Box64 Dynarec and Wine/DXVK environment settings. These are tunable runtime settings, not guaranteed compatibility fixes. Every setting must be justified by tests and device logs.

- Keep environment construction centralized and unit-tested.
- Keep DXVK enabled/disabled paths explicit.
- Keep guest `BOX64_LD_LIBRARY_PATH` separate from Android host `LD_LIBRARY_PATH`.
- Keep Wine module search path explicit.
- Keep game working directory equal to the validated game root.
- Preserve diagnostic log locations and include the effective environment (with secrets redacted) in reports.
- Do not assume `BOX64_AVX`, StrongMem, or other Dynarec flags are optimal for every build/device; benchmark and test regressions.

## 6. Current verified repository / CI state (2026-11-26)

- PR #9 is open, draft, and mergeable:
  https://github.com/dnctf/Moving-Out-2-Android/pull/9
- Branch: `fix/p0-wine-bootstrap-p1-launch-env`.
- Head commit: `4a5e1cf6e394c0996f5b67f0ad99fd4f503a50f7`.
- Android CI run #834 (run ID `45791049387`) for that commit completed with conclusion `success`:
  https://github.com/dnctf/Moving-Out-2-Android/actions/runs/45791049387
- PR scope includes P0 Wine bootstrap/launch environment hardening and the additional Gradle `androidResources.noCompress` extensions `exe,dll,bundle,bank,tar,xz,zst`.
- The noCompress change preserves staged binary payload bytes if such assets are used; it does not embed the 1.37 GiB game folder into the APK.
- PR validation notes still require a physical DBY2-W09 test proving Box64 execution, Wine prefix bootstrap, ntdll load, Vulkan presentation, and game launch.
- PR explicitly does not claim playable gameplay or verified Vulkan SurfaceView/WSI integration.
- CI success is build/test evidence only. It is not physical-device or gameplay evidence.
- Do not merge, publish a new release, or hand off an APK as a working game runtime based solely on CI green.

## 7. Required evidence standard for future work

Every future task/report must separate these categories:

1. **Verified fact** — directly supported by source code, command output, CI run, binary inspection, or device log.
2. **Test result** — include exact command/test name, commit SHA, run ID, device/OS/GPU/driver, and pass/fail.
3. **Inference** — plausible interpretation of evidence, explicitly labeled.
4. **Hypothesis** — unproven root-cause candidate and the experiment needed to confirm/refute it.
5. **Not tested / unknown** — state plainly; never fill gaps with assumptions.

For each patch:
- Inspect the current branch and working tree before editing.
- Make the smallest scoped change; preserve established architecture.
- Add/update tests for the changed behavior.
- Run the relevant CI and fetch the actual final conclusion and failed-job logs.
- Record commit SHA and CI run URL.
- For native/runtime changes, inspect ELF class, machine, dependencies, alignment, checksums, and APK contents.
- For device-specific behavior, require a reproducible physical test and diagnostic archive.
- Never invent CI status, device output, hashes, benchmark numbers, or test coverage.

## 8. Device validation checklist

Do not mark runtime ready until the reference device test records all of the following:

- [ ] APK installs and starts without blank launcher screen.
- [ ] Game folder can be selected through SAF and imported from the actual nested root.
- [ ] Import validation confirms required files and no truncation.
- [ ] Runtime archive downloads/imports, manifest parses, and SHA-256 checks pass.
- [ ] Box64 starts from the installed app's actual native library path.
- [ ] Wine guest architecture and loader dependencies validate.
- [ ] `wineboot --init` exits 0 and creates non-empty registry files.
- [ ] `ntdll.so` loads successfully.
- [ ] `mo2.exe` process starts with the validated game root as working directory.
- [ ] DXVK loads and creates a Vulkan swapchain.
- [ ] At least one actual game frame is presented on the Android surface.
- [ ] Main menu is reached; gameplay/input/audio tested separately.
- [ ] Diagnostic ZIP is exported and reviewed.
- [ ] Sustained FPS, frame pacing, thermal behavior, and memory are measured before performance claims.

## 9. Standard diagnostic bundle

Collect at minimum:
- `device-vulkan-report.json`
- `game-installation-report.json`
- `runtime-bundle-report.json`
- `runtime-process.log` and raw stdout/stderr
- `wine_session.log`
- `dxvk.log` when DXVK is attempted
- Unity `Player.log` when created
- APK version, commit SHA, runtime manifest version, and SHA-256 values
- Device model, Android build, SoC/GPU, Vulkan driver/ICD, and test timestamp

Redact credentials, tokens, private user paths where unnecessary, and personal information before sharing logs.

## 10. Future task execution protocol

When asked to continue development:
1. Fetch current PR/branch/commit and working tree state; do not assume prior state is unchanged.
2. Read the relevant source files and workflows before patching.
3. Identify whether the task is code, build, packaging, runtime, graphics, import, or device validation.
4. State the acceptance criteria and evidence needed.
5. Patch minimally and add regression tests.
6. Run CI and inspect the final workflow/job status.
7. Update this document when architecture, evidence, risks, or CI state materially changes.
8. Report remaining blockers explicitly; never describe an untested runtime as playable.

---
End of project skill notes.
