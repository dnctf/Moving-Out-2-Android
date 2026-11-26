# Runtime bundle format (Phase 2)

The launcher does not ship Wine/Box64 binaries. Users can import a self-built or legally redistributable runtime bundle through the Android SAF folder picker. The importer copies into app-private storage with a staging folder, validates the staged bundle, activates it, and keeps/restore the previous bundle if import fails.

Bundle root must contain `runtime.properties`:

```properties
format_version=1
target_abi=arm64-v8a
box64=bin/box64
wine64=wine/bin/wine64
wineserver=wine/bin/wineserver
library_dir=lib
# Optional graphics payload directories:
dxvk_dir=graphics/dxvk
turnip_dir=graphics/turnip
```

Expected files:
- `bin/box64` — Box64 ARM64 executable
- `wine/bin/wine64` — Wine ARM64 executable
- `wine/bin/wineserver` — Wine ARM64 server executable
- `lib/` — runtime library directory (nonempty)

Importer guardrails: rejects unsafe entry names, limits nesting to 24 levels, limits total entries (files and directories) to 20,000, and limits copied content to 6 GiB. Copy is streamed, not loaded whole into memory. Executable bits are set on the three manifest-declared runtime executables after import. Import occurs in a staging directory and is validated before activation; previous bundle is backed up and rollback attempted on failure.

Validator checks manifest version/ABI, canonical paths confined to bundle root, files are nonempty/readable/executable, ELF64 little-endian AArch64 executable headers, and a readable nonempty library directory.

**Limitations:** Structural checks do not prove dynamic-library dependencies, Android linker compatibility, Wine/Box64 versions, successful process launch, Wine prefix setup, game compatibility, DXVK, Turnip, or first-frame output. A bundle may validate but still fail to run. RuntimeManager intentionally continues to report the runtime as not installed and blocks launch until integration is implemented.

## Optional graphics payloads

- `dxvk_dir`: directory containing x64 `d3d11.dll` and `dxgi.dll`. The launcher checks PE x64 headers and reports `FILES_PRESENT_UNVERIFIED`; this does not prove Wine loads DXVK or Vulkan works.
- `turnip_dir`: directory containing an ARM64 ELF `.so` driver and an ICD JSON file with `library_path`. The launcher checks file/header presence only. It does not inject drivers into Android's Vulkan loader, replace system drivers, or prove Turnip works on the GPU.
- Both properties are optional for backwards compatibility with version 1 bundles. If omitted, status is `NOT_CONFIGURED`.


## Offline-first and Wine loader refactor

The integrated APK embeds `assets/runtime-bundle.zip`. On first launch, the installer copies the asset to private staging, safely extracts it, restores executable permissions, and validates schema-v2 manifest hashes and ELF/PE ABI before activation. A staging/backup rename preserves the previous valid bundle if activation fails. GitHub Releases are used only when the embedded asset is absent; the standalone ZIP remains a recovery artifact.

Gradle stores the ZIP asset without recompression. CI compares the APK asset byte-for-byte with the runtime artifact.

Both wineboot and game launch set `WINEDLLPATH`, `WINELOADER`, and `WINESERVER` from the Wine bundle root. `BOX64_LD_LIBRARY_PATH` searches guest glibc before Wine UNIX modules; `LD_LIBRARY_PATH` remains reserved for Android ARM64 native libraries. The runtime audit checks `DT_NEEDED` for Wine launchers and `x86_64-unix/*.so` modules while allowing libraries supplied by Box64 native wrappers.

The default DXVK is upstream 1.10.3, pinned by SHA-256 `8d1a3c912761b450c879f98478ae64f6f6639e40ce6848170a0f6b8596fd53c6`, the Vulkan 1.1-compatible upstream line. Packaging prunes selected desktop-only Wine components, strips symbols, removes non-runtime metadata, and uses ZIP level 9. CI enforces a compressed bundle budget below 110 MiB. This size gate deliberately fails instead of removing additional core Windows/Unity components without validation.
