# Runtime release build

The runtime downloader expects a GitHub Release with these exact assets:

- `moving-out-2-runtime-arm64-v8a.zip`
- `moving-out-2-runtime-arm64-v8a.zip.sha256`

The `Build and Publish ARM64 Runtime Bundle` workflow creates those assets. Run it from
Actions > Build and Publish ARM64 Runtime Bundle > Run workflow.

## Initial compatibility profile

The first profile is deliberately conservative for the current test device, whose Android
diagnostics reported Vulkan 1.1.128:

- Box64 is compiled from the official `ptitSeb/box64` source for Android arm64-v8a
  using Android NDK 27.2.12479018 (API 26).
- Wine x86_64 is fetched from the selected Kron4ek/Wine-Builds release and verified
  against that release's published SHA-256 sums.
- DXVK 1.10.3 is fetched from the official `doitsujin/dxvk` release and pinned to its
  published SHA-256. It is selected because DXVK 1.10.3 is the final upstream line
  supporting Vulkan 1.1; newer DXVK releases require newer Vulkan feature levels.

The workflow checks ELF machine types, PE32+ x86_64 DLLs, manifest v2, ZIP integrity,
and emits a SHA-256 sidecar. It does not package game data.

## Important runtime limitations

A successful bundle build and manifest validation do not prove that Wine starts on every
Android vendor build or that the game renders. The Android Box64 execution probe, Wine
startup, dynamic dependency resolution, Vulkan/DXVK device feature checks, audio, input,
and game launch must still be tested on the target device.

The first bundle intentionally does not inject a Turnip driver. The Android Vulkan probe
currently reports the system driver; shipping a driver binary is not equivalent to safely
loading it through Android's Vulkan loader. Driver integration must be implemented and
validated separately before it is enabled.

Only publish legally distributable components. The build records upstream versions and
checksums; retain upstream license and notice files when redistributing binaries.
