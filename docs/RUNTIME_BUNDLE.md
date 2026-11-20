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
