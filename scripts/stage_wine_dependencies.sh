#!/usr/bin/env bash
set -euo pipefail

WINE_ROOT="${1:?Usage: stage_wine_dependencies.sh <staged-wine-root>}"
WINE_LIB="${WINE_ROOT}/lib/wine"
GLIBC_DIR="${WINE_ROOT}/lib/x86_64-linux-gnu"
UNIX_DIR="${WINE_LIB}/x86_64-unix"
[[ -d "$WINE_LIB" && -d "$UNIX_DIR" && -d "$GLIBC_DIR" ]] || {
  echo "Wine library, UNIX module, or guest glibc directory is missing." >&2
  exit 1
}

# Android/Box64 supplies these through its native ARM64 wrapper layer.
is_box64_wrapper() {
  case "$1" in
    libGL.so.*|libGLX.so.*|libEGL.so.*|libGLESv1_CM.so.*|libGLESv2.so.*|libvulkan.so.*|    libX11.so.*|libXext.so.*|libXrender.so.*|libXcursor.so.*|libXi.so.*|libXrandr.so.*|    libXfixes.so.*|libXinerama.so.*|libXcomposite.so.*|libXdamage.so.*|libXss.so.*|    libXxf86vm.so.*|libXau.so.*|libXdmcp.so.*|libxcb.so.*)
      return 0 ;;
  esac
  return 1
}

already_staged() {
  local name="$1"
  [[ -e "$GLIBC_DIR/$name" || -e "$UNIX_DIR/$name" ]] && return 0
  find "$WINE_LIB" -name "$name" -print -quit 2>/dev/null | grep -q .
}

resolve_system_library() {
  local name="$1" candidate
  for candidate in "/lib/x86_64-linux-gnu/$name" "/usr/lib/x86_64-linux-gnu/$name"; do
    if [[ -e "$candidate" ]]; then readlink -f "$candidate"; return 0; fi
  done
  find /lib/x86_64-linux-gnu /usr/lib/x86_64-linux-gnu -name "$name" -print -quit 2>/dev/null | while IFS= read -r candidate; do readlink -f "$candidate"; done
}

declare -a queue=("$WINE_ROOT/bin/wine64" "$WINE_ROOT/bin/wineserver")
while IFS= read -r -d '' module; do queue+=("$module"); done < <(find "$WINE_LIB" -type f -name '*.so*' -print0)
 while IFS= read -r -d '' library; do queue+=("$library"); done < <(find "$GLIBC_DIR" -type f -name '*.so*' -print0)
declare -A visited=()
declare -A queued=()
for item in "${queue[@]}"; do queued["$item"]=1; done

index=0
while (( index < ${#queue[@]} )); do
  owner="${queue[$index]}"
  index=$((index + 1))
  [[ -f "$owner" ]] || { echo "Missing Wine ELF dependency owner: $owner" >&2; exit 1; }
  [[ -z "${visited[$owner]:-}" ]] || continue
  visited["$owner"]=1
  readelf -h "$owner" | grep -Eq 'Class:.*ELF64'
  readelf -h "$owner" | grep -Eqi 'Machine:.*(Advanced Micro Devices X86-64|X86-64)'
  while IFS= read -r needed; do
    [[ -n "$needed" ]] || continue
    if already_staged "$needed" || is_box64_wrapper "$needed"; then continue; fi
    source="$(resolve_system_library "$needed" || true)"
    [[ -n "$source" && -f "$source" ]] || {
      echo "Unresolved DT_NEEDED '$needed' referenced by $owner" >&2
      exit 1
    }
    destination="$GLIBC_DIR/$needed"
    if [[ -e "$destination" ]]; then
      if ! cmp -s "$destination" "$source"; then
        echo "Conflicting guest dependency basename '$needed'." >&2
        exit 1
      fi
    else
      cp -aL "$source" "$destination"
    fi
    if [[ -z "${queued[$destination]:-}" ]]; then
      queue+=("$destination")
      queued["$destination"]=1
    fi
  done < <(readelf -d "$owner" | grep -oE 'Shared library: \[[^]]+\]' | cut -d '[' -f2 | tr -d ']')
done

for library in "$GLIBC_DIR"/*.so*; do
  [[ -f "$library" && ! -L "$library" ]] || { echo "Guest dependency is not a regular file: $library" >&2; exit 1; }
  readelf -h "$library" | grep -Eq 'Class:.*ELF64'
  readelf -h "$library" | grep -Eqi 'Machine:.*(Advanced Micro Devices X86-64|X86-64)'
  readelf -h "$library" | grep -Eq 'Type:.*DYN'
done

echo "Recursive Wine DT_NEEDED staging passed: scanned ${#visited[@]} ELF objects; guest libs=$(find "$GLIBC_DIR" -maxdepth 1 -type f | wc -l)."
