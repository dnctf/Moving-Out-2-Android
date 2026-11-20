# Runtime bundle format (Phase 2 foundation)

The launcher accepts no runtime binaries by default. A future import UI may copy a user-built, legally redistributable runtime bundle into app-private storage. Do not download or bundle Wine/Box64 binaries automatically.

The bundle root must contain `runtime.properties`:

```properties
format_version=1
target_abi=arm64-v8a
box64=bin/box64
wine64=wine/bin/wine64
wineserver=wine/bin/wineserver
library_dir=lib
```

Required executable paths are checked for readability, executable permission, and ELF64 little-endian AArch64 machine type. Library directory must be present, readable, and non-empty. Canonical-path validation prevents manifest paths from escaping the bundle root.

**Limitations:** validator checks structure only. It does not validate dynamic-library dependencies, Wine/Box64 versions, Android linker compatibility, Wine prefix initialization, game compatibility, DXVK, Turnip, or first-frame output. A bundle can pass validation and still fail to execute.
