#!/usr/bin/env bash
set -euo pipefail
ROOT="${1:?Usage: audit_runtime_bundle.sh <bundle-root> [bundle.zip]}"
BUNDLE_ZIP="${2:-}"
need(){ command -v "$1" >/dev/null 2>&1 || { echo "Missing required command: $1" >&2; exit 2; }; }
need readelf
need objdump
need sha256sum
need find
if [[ -n "$BUNDLE_ZIP" ]]; then
  need unzip
  need python3
  [[ -s "$BUNDLE_ZIP" ]] || { echo "Runtime ZIP missing/empty: $BUNDLE_ZIP" >&2; exit 1; }
  unzip -p "$BUNDLE_ZIP" runtime-manifest.json | python3 -m json.tool > /dev/null
fi
check_elf(){
  local file="$1" machine="$2"
  [[ -s "$file" ]] || { echo "Missing/empty ELF: $file" >&2; exit 1; }
  readelf -h "$file" | grep -Eq 'Class:.*ELF64'
  readelf -h "$file" | grep -Eqi "Machine:.*$machine"
}
check_shared(){
  check_elf "$1" "$2"
  readelf -h "$1" | grep -Eq 'Type:.*DYN'
}
check_pe(){
  local file="$1"
  [[ -s "$file" ]] || { echo "Missing/empty PE: $file" >&2; exit 1; }
  objdump -f "$file" | grep -q 'architecture: i386:x86-64'
  objdump -p "$file" | grep -Eqi 'Magic[[:space:]]+020b'
}
check_elf "$ROOT/box64/bin/box64" AArch64
check_elf "$ROOT/wine/bin/wine64" 'X86-64'
check_elf "$ROOT/wine/bin/wineserver" 'X86-64'
count=0
while IFS= read -r -d '' lib; do
  check_shared "$lib" 'X86-64'
  count=$((count+1))
done < <(find -L "$ROOT/wine/lib/wine" -type f -name '*.so*' -print0)
(( count > 0 )) || { echo "No Wine x86_64 shared objects found." >&2; exit 1; }
GLIBC_DIR="$ROOT/wine/lib/x86_64-linux-gnu"
[[ -s "$GLIBC_DIR/libc.so.6" ]] || { echo "Guest glibc libc.so.6 missing." >&2; exit 1; }
glibc_count=0
while IFS= read -r -d '' lib; do
  check_shared "$lib" 'X86-64'
  glibc_count=$((glibc_count+1))
done < <(find "$GLIBC_DIR" -type f -name '*.so*' -print0)
(( glibc_count > 0 )) || { echo "No guest glibc shared objects found." >&2; exit 1; }
UNIX_DIR="$ROOT/wine/lib/wine/x86_64-unix"
resolve_needed() {
  local owner="$1" needed="$2"
  if [[ -e "$GLIBC_DIR/$needed" || -e "$UNIX_DIR/$needed" ]]; then return 0; fi
  case "$needed" in
    libGL.so*|libGLX.so*|libEGL.so*|libGLESv1.so*|libGLESv2.so*|libGLESv3.so*|libvulkan.so*|libX11.so*|libXext.so*|libXrender.so*|libXcursor.so*|libXi.so*|libXrandr.so*|libXfixes.so*|libXinerama.so*|libXcomposite.so*|libXdamage.so*|libXss.so*|libXxf86vm.so*|libXau.so*|libXdmcp.so*|libxcb.so*|libwayland-client.so*|libpulse.so*|libpulsecommon*.so*|libasound.so*|libandroid.so*) return 0 ;;
  esac
  echo "Unresolved DT_NEEDED '$needed' referenced by $owner" >&2
  return 1
}
check_needed() {
  local owner="$1" needed
  while IFS= read -r needed; do [[ -z "$needed" ]] || resolve_needed "$owner" "$needed"; done < <(readelf -d "$owner" | grep -oE 'Shared library: \[[^]]+\]' | cut -d '[' -f2 | tr -d ']')
}
check_needed "$ROOT/wine/bin/wine64"
check_needed "$ROOT/wine/bin/wineserver"
while IFS= read -r -d '' module; do check_needed "$module"; done < <(find "$UNIX_DIR" -type f -name '*.so*' -print0)
if find "$ROOT/wine/lib" -type l -print -quit | grep -q .; then
  echo "Symlink found in wine/lib." >&2
  exit 1
fi
for dll in dxvk/x64/d3d11.dll dxvk/x64/dxgi.dll wine/lib/wine/x86_64-windows/xinput1_3.dll wine/lib/wine/x86_64-windows/xinput1_4.dll wine/lib/wine/x86_64-windows/xinput9_1_0.dll wine/lib/wine/x86_64-windows/vcruntime140.dll wine/lib/wine/x86_64-windows/vcruntime140_1.dll wine/lib/wine/x86_64-windows/msvcp140.dll; do
  check_pe "$ROOT/$dll"
done
echo "Multi-ABI runtime audit passed."
