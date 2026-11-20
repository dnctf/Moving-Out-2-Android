#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${1:-$ROOT/build}"
CC="${MINGW_CC:-x86_64-w64-mingw32-gcc}"
command -v "$CC" >/dev/null || { echo "Missing compiler: $CC (install mingw-w64)" >&2; exit 2; }
mkdir -p "$OUT"
for name in xinput1_3 xinput1_4; do
 "$CC" -std=c11 -O2 -Wall -Wextra -Werror -shared -static-libgcc -Wl,--kill-at -o "$OUT/$name.dll" "$ROOT/wine_xinput_dll.c"
 file "$OUT/$name.dll"
done
