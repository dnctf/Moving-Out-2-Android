# Runtime bundle manifest v2

The runtime bundle manifest separates the Android host ABI from the Windows guest ABI. A checksum proves that a component matches the bytes named by the manifest; it does not prove that the binary is executable or compatible. The validator therefore checks both SHA-256 and the binary format declared for each component role.

## Component roles

| Component | Required format | Role |
|---|---|---|
| `box64` | `ELF64_AARCH64` | Android ARM64 host executable |
| `wine64`, `wineserver` | `ELF64_X86_64` | Linux x86-64 guest executables launched through Box64 |
| `wineLib` | `DIRECTORY_SHA256_X86_64` | Directory whose shared objects are individually checked as ELF64 x86-64 ET_DYN |
| `d3d11`, `dxgi` | `PE32_PLUS_X86_64` | Windows x86-64 PE32+ DLLs |
| `turnipSo` | `ELF64_AARCH64_SHARED` | ARM64 Vulkan ICD shared object |
| `turnipIcd` | `JSON` | Vulkan ICD descriptor |

Every included file component has `included`, `path`, `abi`, and `sha256`. Directory components use the same fields, with a deterministic SHA-256 over sorted relative paths and file hashes.

## Provenance

Schema version 2 requires a `provenance` object containing `packager` and `manifestFormat=role-validated-abi-v2`. This is build traceability metadata, not a digital signature. Runtime integrity is still checked by recomputing each file or directory digest after import.

## Android executable preflight

Before any Wine server is started, the app executes the validated ARM64 Box64 binary with `--version` from app-private storage. The result is classified as `EXEC_ALLOWED_APP_DATA`, `EXEC_ALLOWED_NATIVE_LIB_DIR`, `EXEC_BLOCKED_SELINUX_EACCES`, or `EXEC_FAILED`.

The native-library-directory fallback is considered only when `libbox64.so` is present, executable, structurally valid ELF64 AArch64, and byte-for-byte identical (SHA-256) to the validated bundle Box64. The manifest sets `extractNativeLibs=true` and Gradle uses legacy JNI packaging so a future `jniLibs/arm64-v8a/libbox64.so` payload can be extracted. This repository does not currently bundle Box64 in `jniLibs`; the fallback is conditional.

A successful probe confirms only that the tested binary can be executed with the current Android policy. It does not prove that Wine, Box64 dynamic dependencies, DXVK, Turnip, or a game can run correctly.
