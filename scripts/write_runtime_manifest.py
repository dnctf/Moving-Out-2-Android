#!/usr/bin/env python3
"""Write a deterministic, strictly valid runtime-manifest.json."""
import argparse
import hashlib
import json
from pathlib import Path

def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()

def sha256_directory(path: Path) -> str:
    rows = []
    for item in sorted((p for p in path.rglob("*") if p.is_file()),
                       key=lambda p: p.relative_to(path).as_posix()):
        rows.append(f"{sha256_file(item)}  ./{item.relative_to(path).as_posix()}\n")
    return hashlib.sha256("".join(rows).encode("utf-8")).hexdigest()

def component(root: Path, key: str, relative: str, abi: str, included: bool, directory: bool = False):
    entry = {"included": included, "path": relative, "abi": abi, "sha256": ""}
    if included:
        path = root / relative
        if not path.exists() or (not directory and not path.is_file()):
            raise FileNotFoundError(f"Manifest component {key} missing: {path}")
        entry["sha256"] = sha256_directory(path) if directory else sha256_file(path)
    return entry

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--version", required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    has_turnip = (root / "turnip/libvulkan_freedreno.so").is_file()
    components = {
        "box64": component(root, "box64", "box64/bin/box64", "ELF64_AARCH64", True),
        "wine64": component(root, "wine64", "wine/bin/wine64", "ELF64_X86_64", True),
        "wineserver": component(root, "wineserver", "wine/bin/wineserver", "ELF64_X86_64", True),
        "wineLib": component(root, "wineLib", "wine/lib/wine", "DIRECTORY_SHA256_X86_64", True, True),
        "d3d11": component(root, "d3d11", "dxvk/x64/d3d11.dll", "PE32_PLUS_X86_64", True),
        "dxgi": component(root, "dxgi", "dxvk/x64/dxgi.dll", "PE32_PLUS_X86_64", True),
        "xinput1_3": component(root, "xinput1_3", "wine/lib/wine/x86_64-windows/xinput1_3.dll", "PE32_PLUS_X86_64", True),
        "xinput1_4": component(root, "xinput1_4", "wine/lib/wine/x86_64-windows/xinput1_4.dll", "PE32_PLUS_X86_64", True),
        "turnip": {"included": has_turnip},
        "turnipSo": component(root, "turnipSo", "turnip/libvulkan_freedreno.so", "ELF64_AARCH64_SHARED", has_turnip),
        "turnipIcd": component(root, "turnipIcd", "turnip/freedreno_icd.aarch64.json", "JSON", has_turnip),
    }
    manifest = {
        "schemaVersion": 2,
        "targetAbi": "arm64-v8a",
        "bundleVersion": args.version,
        "provenance": {"packager": "scripts/package_runtime_bundle.sh", "manifestFormat": "role-validated-abi-v2"},
        "components": components,
        "defaultLaunchProfile": {
            "arguments": ["-force-d3d11"],
            "environment": {
                "WINEDLLOVERRIDES": "d3d11,dxgi,xinput1_3,xinput1_4=n,b",
                "BOX64_DYNAREC": "1",
                "BOX64_DYNAREC_BIGBLOCK": "1",
                "BOX64_DYNAREC_STRONGMEM": "1",
                "BOX64_DYNAREC_SAFEFLAGS": "1",
            },
        },
    }
    output = root / "runtime-manifest.json"
    output.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    # Parse the bytes back before the packager is allowed to archive them.
    json.loads(output.read_text(encoding="utf-8"))
    print(f"Runtime manifest generated and parsed: {output}")

if __name__ == "__main__":
    main()
