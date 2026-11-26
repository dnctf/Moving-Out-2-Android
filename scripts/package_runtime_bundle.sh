#!/usr/bin/env bash
set -euo pipefail
usage(){ echo "Usage: $0 --box64 FILE --wine-dir DIR --dxvk-dir DIR --xinput-dir DIR [--turnip-so FILE] [--bundle-version VERSION] --output ZIP" >&2; }
BOX64='' WINE_DIR='' DXVK_DIR='' XINPUT_DIR='' TURNIP_SO='' VERSION=1 OUTPUT=''
while (($#)); do case "$1" in
 --box64) BOX64=${2:?};shift 2;; --wine-dir) WINE_DIR=${2:?};shift 2;; --dxvk-dir) DXVK_DIR=${2:?};shift 2;; --xinput-dir) XINPUT_DIR=${2:?};shift 2;;
 --turnip-so) TURNIP_SO=${2:?};shift 2;; --bundle-version) VERSION=${2:?};shift 2;; --output) OUTPUT=${2:?};shift 2;;
 -h|--help) usage;exit 0;; *) echo "Unknown argument: $1" >&2;usage;exit 2;; esac; done
[[ -n "$BOX64" && -n "$WINE_DIR" && -n "$DXVK_DIR" && -n "$XINPUT_DIR" && -n "$OUTPUT" ]] || { usage;exit 2; }
[[ "$VERSION" =~ ^[A-Za-z0-9._+-]+$ ]] || { echo "Invalid bundle version." >&2; exit 2; }
command -v zip >/dev/null || { echo "zip command is required." >&2; exit 1; }
for f in "$BOX64" "$WINE_DIR/bin/wine64" "$WINE_DIR/bin/wineserver" "$DXVK_DIR/d3d11.dll" "$DXVK_DIR/dxgi.dll" "$XINPUT_DIR/xinput1_3.dll" "$XINPUT_DIR/xinput1_4.dll" "$XINPUT_DIR/xinput9_1_0.dll"; do [[ -s "$f" ]] || { echo "Required file missing/empty: $f" >&2;exit 1;}; done
[[ -d "$WINE_DIR/lib/wine" && -n "$(find -L "$WINE_DIR/lib/wine" -type f -size +0c -print -quit)" ]] || { echo "wine/lib/wine is missing or empty" >&2;exit 1; }
[[ -d "$WINE_DIR/lib/x86_64-linux-gnu" ]] || { echo "wine/lib/x86_64-linux-gnu is required" >&2; exit 1; }

# Reject an incomplete guest loader closure before writing a manifest. These
# are the canonical locations used by this packager (Wine Unix modules live
# under lib/wine, not bin/).
for module in \
  "$WINE_DIR/lib/wine/x86_64-unix/ntdll.so" \
  "$WINE_DIR/lib/wine/x86_64-unix/win32u.so" \
  "$WINE_DIR/lib/wine/x86_64-windows/kernel32.dll" \
  "$WINE_DIR/lib/wine/x86_64-windows/user32.dll"; do
  [[ -s "$module" ]] || { echo "Required Wine loader module missing/empty: $module" >&2; exit 1; }
done

# Require the named x86-64 glibc/loader closure and reject dangling symlinks.
for library in ld-linux-x86-64.so.2 libc.so.6 libm.so.6 libpthread.so.0 libdl.so.2 librt.so.1; do
  [[ -s "$WINE_DIR/lib/x86_64-linux-gnu/$library" ]] || {
    echo "Required guest glibc object missing/empty: $WINE_DIR/lib/x86_64-linux-gnu/$library" >&2
    exit 1
  }
done
if find "$WINE_DIR/lib/x86_64-linux-gnu" -xtype l -print -quit | grep -q .; then
  echo "Guest glibc contains a broken symlink; refusing to package an incomplete loader closure." >&2
  exit 1
fi
check_elf(){ local f="$1" machine="$2"; command -v readelf >/dev/null || { echo "readelf is required for ABI provenance validation." >&2; exit 1; }; readelf -h "$f" | grep -Eq "Class:.*ELF64" || { echo "ELF is not ELF64: $f" >&2;exit 1; }; readelf -h "$f" | grep -Eq "Machine:.*$machine" || { echo "Unexpected ELF machine (expected $machine): $f" >&2;exit 1;}; }
check_elf "$BOX64" "AArch64"
check_elf "$WINE_DIR/bin/wine64" "Advanced Micro Devices X86-64"
check_elf "$WINE_DIR/bin/wineserver" "Advanced Micro Devices X86-64"
check_elf_shared(){ check_elf "$1" "$2"; readelf -h "$1" | grep -Eq "Type:.*DYN" || { echo "Not an ELF shared object: $1" >&2; exit 1; }; }
if [[ -n "$TURNIP_SO" ]]; then check_elf_shared "$TURNIP_SO" "AArch64"; fi
check_guest_libs(){
  local count
  count="$(find -L "$WINE_DIR/lib/wine" -type f \( -name '*.so' -o -name '*.so.*' \) | wc -l)"
  [[ "$count" -gt 0 ]] || { echo "Wine guest library directory has no shared .so objects." >&2; exit 1; }
  while IFS= read -r -d '' lib; do check_elf_shared "$lib" "Advanced Micro Devices X86-64"; done < <(find "$WINE_DIR/lib/wine" -type f \( -name '*.so' -o -name '*.so.*' \) -print0)
}
check_guest_libs
for module in \
  "$WINE_DIR/lib/wine/x86_64-unix/ntdll.so" \
  "$WINE_DIR/lib/wine/x86_64-unix/win32u.so"; do
  check_elf_shared "$module" "Advanced Micro Devices X86-64"
done
for library in ld-linux-x86-64.so.2 libc.so.6 libm.so.6 libpthread.so.0 libdl.so.2 librt.so.1; do
  check_elf_shared "$WINE_DIR/lib/x86_64-linux-gnu/$library" "Advanced Micro Devices X86-64"
done
check_pe_x64(){
  command -v objdump >/dev/null || { echo "objdump is required for PE32+ ABI validation." >&2; exit 1; }
  objdump -f "$1" | grep -q "architecture: i386:x86-64" || { echo "Not x86-64 PE: $1" >&2; exit 1; }
  objdump -p "$1" | grep -Eqi "Magic[[:space:]]+020b.*PE32\+" || { echo "Not PE32+: $1" >&2; exit 1; }
}
check_pe_x64 "$DXVK_DIR/d3d11.dll"
check_pe_x64 "$DXVK_DIR/dxgi.dll"
check_pe_x64 "$XINPUT_DIR/xinput1_3.dll"
check_pe_x64 "$XINPUT_DIR/xinput1_4.dll"
check_pe_x64 "$XINPUT_DIR/xinput9_1_0.dll"
for redistributable in vcruntime140.dll vcruntime140_1.dll msvcp140.dll; do
  check_pe_x64 "$WINE_DIR/lib/wine/x86_64-windows/$redistributable"
done
for module in kernel32.dll user32.dll; do
  check_pe_x64 "$WINE_DIR/lib/wine/x86_64-windows/$module"
done
if [[ -n "$TURNIP_SO" ]];then [[ -s "$TURNIP_SO" ]] || { echo "Turnip SO missing/empty" >&2;exit 1;}; ICD="$(dirname "$TURNIP_SO")/freedreno_icd.aarch64.json"; [[ -s "$ICD" ]] || { echo "Expected ICD JSON beside Turnip SO: $ICD" >&2;exit 1;};fi
OUT_DIR="$(dirname "$OUTPUT")";mkdir -p "$OUT_DIR";OUTPUT="$(cd "$OUT_DIR" && pwd)/$(basename "$OUTPUT")";TMP="$(mktemp -d)";trap 'rm -rf "$TMP"' EXIT
ROOT="$TMP/root";mkdir -p "$ROOT/box64/bin" "$ROOT/wine/bin" "$ROOT/wine/lib" "$ROOT/dxvk/x64"
cp "$BOX64" "$ROOT/box64/bin/box64";cp "$WINE_DIR/bin/wine64" "$ROOT/wine/bin/wine64";cp "$WINE_DIR/bin/wineserver" "$ROOT/wine/bin/wineserver"
cp -aL "$WINE_DIR/lib/wine" "$ROOT/wine/lib/wine";mkdir -p "$ROOT/wine/lib/wine/x86_64-windows";cp -aL "$WINE_DIR/lib/x86_64-linux-gnu" "$ROOT/wine/lib/x86_64-linux-gnu";cp "$DXVK_DIR/d3d11.dll" "$ROOT/dxvk/x64/";cp "$DXVK_DIR/dxgi.dll" "$ROOT/dxvk/x64/"
cp "$XINPUT_DIR/xinput1_3.dll" "$ROOT/wine/lib/wine/x86_64-windows/";cp "$XINPUT_DIR/xinput1_4.dll" "$ROOT/wine/lib/wine/x86_64-windows/";cp "$XINPUT_DIR/xinput9_1_0.dll" "$ROOT/wine/lib/wine/x86_64-windows/"
for redistributable in vcruntime140.dll vcruntime140_1.dll msvcp140.dll; do
  source="$WINE_DIR/lib/wine/x86_64-windows/$redistributable"
  [[ -s "$source" ]] || { echo "Required VC++ runtime DLL missing from Wine runtime: $source" >&2; exit 1; }
  cp "$source" "$ROOT/wine/lib/wine/x86_64-windows/$redistributable"
done
check_guest_glibc(){
  local count=0
  [[ -s "$ROOT/wine/lib/x86_64-linux-gnu/libc.so.6" ]] || { echo "Guest glibc libc.so.6 missing" >&2; exit 1; }
  while IFS= read -r -d '' lib; do check_elf_shared "$lib" "Advanced Micro Devices X86-64"; count=$((count+1)); done < <(find "$ROOT/wine/lib/x86_64-linux-gnu" -type f \( -name '*.so' -o -name '*.so.*' \) -print0)
  [[ "$count" -gt 0 ]] || { echo "Guest glibc has no ELF shared objects" >&2; exit 1; }
}
check_guest_glibc
if find "$ROOT/wine/lib" -type l -print -quit | grep -q .; then echo "Symlink found under wine/lib; refusing non-portable Android ZIP." >&2; exit 1; fi
if [[ -n "$TURNIP_SO" ]];then mkdir -p "$ROOT/turnip";cp "$TURNIP_SO" "$ROOT/turnip/libvulkan_freedreno.so";cp "$(dirname "$TURNIP_SO")/freedreno_icd.aarch64.json" "$ROOT/turnip/";fi
MANIFEST_FILE="$ROOT/runtime-manifest.json"
python3 scripts/write_runtime_manifest.py --root "$ROOT" --version "$VERSION"
python3 -m json.tool "$MANIFEST_FILE" > /dev/null
mkdir -p "$OUT_DIR"
rm -f "$OUTPUT"
(cd "$ROOT" && zip -qr "$OUTPUT" .)
unzip -p "$OUTPUT" runtime-manifest.json | python3 -m json.tool > /dev/null
(cd "$OUT_DIR" && sha256sum "$(basename "$OUTPUT")" > SHA256SUMS.txt)
test -s "$OUT_DIR/SHA256SUMS.txt"
echo "Runtime bundle created: $OUTPUT"
echo "SHA256SUMS: $OUT_DIR/SHA256SUMS.txt"
