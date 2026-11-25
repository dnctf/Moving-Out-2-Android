#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${1:-$ROOT/build}"
CC="${MINGW_CC:-x86_64-w64-mingw32-gcc}"
command -v "$CC" >/dev/null || { echo "Missing compiler: $CC (install mingw-w64)" >&2; exit 2; }
mkdir -p "$OUT"
DEF="$OUT/xinput-exports.def"
cat > "$DEF" <<'EOF'
LIBRARY XINPUT
EXPORTS
    XInputGetState
    XInputSetState
    XInputGetCapabilities
    XInputEnable
    XInputGetBatteryInformation
    XInputGetKeystroke
    XInputGetStateEx @100 NONAME
    XInputWaitForGuideButton @101
    XInputCancelGuideButton @102
    XInputPowerOffController @103
EOF
for name in xinput1_3 xinput1_4 xinput9_1_0; do
 "$CC" -std=c11 -O2 -Wall -Wextra -Werror -shared -static-libgcc \
   -Wl,--kill-at -Wl,--enable-stdcall-fixup -o "$OUT/$name.dll" \
   "$ROOT/wine_xinput_dll.c" "$DEF"
 file "$OUT/$name.dll"
 objdump -p "$OUT/$name.dll" | grep -q 'Ordinal/Name Pointer'
done
