#!/usr/bin/env bash
set -euo pipefail
ROOT="${1:?Usage: audit_runtime_bundle.sh <bundle-root>}"
need(){ command -v "$1" >/dev/null 2>&1 || { echo "Missing required command: $1" >&2; exit 2; }; }
need llvm-readelf
need llvm-readobj
need sha256sum
need find
check_elf(){
  local file="$1" machine="$2"
  [[ -s "$file" ]] || { echo "Missing/empty ELF: $file" >&2; exit 1; }
  llvm-readelf -h "$file" | grep -Eq 'Class:.*ELF64'
  llvm-readelf -h "$file" | grep -Eqi "Machine:.*$machine"
}
check_shared(){
  check_elf "$1" "$2"
  llvm-readelf -h "$1" | grep -Eq 'Type:.*DYN'
}
check_pe(){
  local file="$1"
  [[ -s "$file" ]] || { echo "Missing/empty PE: $file" >&2; exit 1; }
  llvm-readobj --file-headers "$file" | grep -Eq 'Format:.*COFF-x86-64'
  llvm-readobj --file-headers "$file" | grep -Eqi 'Magic:.*0x20b'
}
check_elf "$ROOT/box64/bin/box64" AArch64
check_elf "$ROOT/wine/bin/wine64" 'X86-64'
check_elf "$ROOT/wine/bin/wineserver" 'X86-64'
count=0
while IFS= read -r -d '' lib; do check_shared "$lib" 'X86-64'; count=$((count+1)); done < <(find -L "$ROOT/wine/lib/wine" -type f -name '*.so*' -print0)
(( count > 0 )) || { echo "No Wine x86_64 shared objects found." >&2; exit 1; }
for dll in dxvk/x64/d3d11.dll dxvk/x64/dxgi.dll wine/lib/wine/x86_64-windows/xinput1_3.dll wine/lib/wine/x86_64-windows/xinput1_4.dll; do check_pe "$ROOT/$dll"; done
echo "Multi-ABI runtime audit passed."
