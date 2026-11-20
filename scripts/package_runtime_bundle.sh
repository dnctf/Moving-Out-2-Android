#!/usr/bin/env bash
set -euo pipefail
usage(){ echo "Usage: $0 --box64 FILE --wine-dir DIR --dxvk-dir DIR [--turnip-so FILE] [--bundle-version VERSION] --output ZIP" >&2; }
BOX64= WINE_DIR= DXVK_DIR= TURNIP_SO= VERSION=1 OUTPUT=
while (($#)); do case "$1" in
 --box64) BOX64=${2:?};shift 2;; --wine-dir) WINE_DIR=${2:?};shift 2;; --dxvk-dir) DXVK_DIR=${2:?};shift 2;;
 --turnip-so) TURNIP_SO=${2:?};shift 2;; --bundle-version) VERSION=${2:?};shift 2;; --output) OUTPUT=${2:?};shift 2;;
 -h|--help) usage;exit 0;; *) echo "Unknown argument: $1" >&2;usage;exit 2;; esac; done
[[ -n "$BOX64" && -n "$WINE_DIR" && -n "$DXVK_DIR" && -n "$OUTPUT" ]] || { usage;exit 2; }
[[ "$VERSION" =~ ^[A-Za-z0-9._+-]+$ ]] || { echo "Invalid bundle version." >&2; exit 2; }
command -v zip >/dev/null || { echo "zip command is required." >&2; exit 1; }
for f in "$BOX64" "$WINE_DIR/bin/wine64" "$WINE_DIR/bin/wineserver" "$DXVK_DIR/d3d11.dll" "$DXVK_DIR/dxgi.dll"; do [[ -s "$f" ]] || { echo "Required file missing/empty: $f" >&2;exit 1;}; done
[[ -d "$WINE_DIR/lib/wine" && -n "$(find "$WINE_DIR/lib/wine" -type f -size +0c -print -quit)" ]] || { echo "wine/lib/wine is missing or empty" >&2;exit 1; }
check_elf(){ local f="$1"; if command -v readelf >/dev/null;then readelf -h "$f" | grep -Eq 'Machine:.*AArch64' || { echo "Not AArch64 ELF: $f" >&2;exit 1;}; fi; }
check_elf "$BOX64";check_elf "$WINE_DIR/bin/wine64";check_elf "$WINE_DIR/bin/wineserver"
if [[ -n "$TURNIP_SO" ]];then [[ -s "$TURNIP_SO" ]] || { echo "Turnip SO missing/empty" >&2;exit 1;}; ICD="$(dirname "$TURNIP_SO")/freedreno_icd.aarch64.json"; [[ -s "$ICD" ]] || { echo "Expected ICD JSON beside Turnip SO: $ICD" >&2;exit 1;};fi
OUT_DIR="$(dirname "$OUTPUT")";mkdir -p "$OUT_DIR";OUTPUT="$(cd "$OUT_DIR" && pwd)/$(basename "$OUTPUT")";TMP="$(mktemp -d)";trap "rm -rf \"$TMP\"" EXIT
ROOT="$TMP/root";mkdir -p "$ROOT/box64/bin" "$ROOT/wine/bin" "$ROOT/wine/lib" "$ROOT/dxvk/x64"
cp "$BOX64" "$ROOT/box64/bin/box64";cp "$WINE_DIR/bin/wine64" "$ROOT/wine/bin/wine64";cp "$WINE_DIR/bin/wineserver" "$ROOT/wine/bin/wineserver"
cp -a "$WINE_DIR/lib/wine" "$ROOT/wine/lib/wine";cp "$DXVK_DIR/d3d11.dll" "$ROOT/dxvk/x64/";cp "$DXVK_DIR/dxgi.dll" "$ROOT/dxvk/x64/"
if [[ -n "$TURNIP_SO" ]];then mkdir -p "$ROOT/turnip";cp "$TURNIP_SO" "$ROOT/turnip/libvulkan_freedreno.so";cp "$(dirname "$TURNIP_SO")/freedreno_icd.aarch64.json" "$ROOT/turnip/";fi
sha(){ sha256sum "$1" | awk '{print $1}';}
hash_dir(){ (cd "$1" && find . -type f -print0 | sort -z | xargs -0 sha256sum) | sha256sum | awk '{print $1}';}
cat > "$ROOT/runtime-manifest.json" <<JSON
{
 "schemaVersion":1,"targetAbi":"arm64-v8a","bundleVersion":"$VERSION",
 "components":{
  "box64":{"included":true,"path":"box64/bin/box64","sha256":"$(sha "$ROOT/box64/bin/box64")"},
  "wine64":{"included":true,"path":"wine/bin/wine64","sha256":"$(sha "$ROOT/wine/bin/wine64")"},
  "wineserver":{"included":true,"path":"wine/bin/wineserver","sha256":"$(sha "$ROOT/wine/bin/wineserver")"},
  "wineLib":{"included":true,"path":"wine/lib/wine","sha256":"$(hash_dir "$ROOT/wine/lib/wine")"},
  "d3d11":{"included":true,"path":"dxvk/x64/d3d11.dll","sha256":"$(sha "$ROOT/dxvk/x64/d3d11.dll")"},
  "dxgi":{"included":true,"path":"dxvk/x64/dxgi.dll","sha256":"$(sha "$ROOT/dxvk/x64/dxgi.dll")"},
  "turnip":{"included":$([[ -n "$TURNIP_SO" ]] && echo true || echo false)},
  "turnipSo":{"included":$([[ -n "$TURNIP_SO" ]] && echo true || echo false), "path":"turnip/libvulkan_freedreno.so","sha256":"$([[ -n "$TURNIP_SO" ]] && sha "$ROOT/turnip/libvulkan_freedreno.so" || echo "")"},
  "turnipIcd":{"included":$([[ -n "$TURNIP_SO" ]] && echo true || echo false), "path":"turnip/freedreno_icd.aarch64.json","sha256":"$([[ -n "$TURNIP_SO" ]] && sha "$ROOT/turnip/freedreno_icd.aarch64.json" || echo "")}
 },
 "defaultLaunchProfile":{"arguments":["-force-d3d11"],"environment":{"WINEDLLOVERRIDES":"d3d11,dxgi,xinput1_3,xinput1_4=n,b","BOX64_DYNAREC":"1","BOX64_DYNAREC_BIGBLOCK":"1","BOX64_DYNAREC_STRONGMEM":"1","BOX64_DYNAREC_SAFEFLAGS":"1"}}
}
JSON
mkdir -p "$OUT_DIR";rm -f "$OUTPUT";(cd "$ROOT" && zip -qr "$OUTPUT" .)
echo "Runtime bundle created: $OUTPUT"
