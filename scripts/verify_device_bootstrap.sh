#!/usr/bin/env bash
set -Eeuo pipefail

PACKAGE="com.dnctf.movingout2"
ACTIVITY="$PACKAGE/.MainActivity"
EXPECTED_MODEL="DBY2-W09"
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
OUT_DIR="${2:-device-bootstrap-diagnostics}"
mkdir -p "$OUT_DIR"

command -v adb >/dev/null || { echo "ERROR: adb not found" >&2; exit 2; }
[[ -f "$APK" ]] || { echo "ERROR: debug APK not found: $APK" >&2; exit 2; }

DEVICE="${ADB_SERIAL:-}"
if [[ -z "$DEVICE" ]]; then
  mapfile -t DEVICES < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  [[ ${#DEVICES[@]} -eq 1 ]] || { echo "ERROR: connect exactly one authorized device or set ADB_SERIAL" >&2; exit 2; }
  DEVICE="${DEVICES[0]}"
fi
ADB=(adb -s "$DEVICE")
MODEL="$("${ADB[@]}" shell getprop ro.product.model | tr -d '\r')"
[[ "$MODEL" == *"$EXPECTED_MODEL"* ]] || echo "WARNING: expected $EXPECTED_MODEL; detected '$MODEL'"

echo "[1/6] Install debug APK on $DEVICE ($MODEL)"
"${ADB[@]}" install -r "$APK"
echo "[2/6] Clear logcat"
"${ADB[@]}" logcat -c
echo "[3/6] Trigger diagnostic bootstrap launch"
"${ADB[@]}" shell am force-stop "$PACKAGE" || true
"${ADB[@]}" shell am start -W -n "$ACTIVITY" --ez mo2.diagnostic.bootstrap true
echo "[4/6] Stream MO2_STAGE milestones; press Ctrl-C when diagnosis is complete"
set +e
"${ADB[@]}" logcat -v time -s MO2_STAGE:I AndroidRuntime:E libc:F DEBUG:F
LOGCAT_STATUS=$?
set -e
"${ADB[@]}" logcat -d -v threadtime > "$OUT_DIR/logcat-full.txt"
echo "[5/6] Stop app and verify no orphan native runtime processes"
"${ADB[@]}" shell am force-stop "$PACKAGE" || true
sleep 2
PROCESS_LIST="$("${ADB[@]}" shell ps -A 2>/dev/null | grep -E '[b]ox64|[w]ineserver|[m]o2_process_group' || true)"
if [[ -n "$PROCESS_LIST" ]]; then
  printf '%s\n' "$PROCESS_LIST" | tee "$OUT_DIR/orphan-processes.txt"
  echo "ERROR: native runtime processes remain after force-stop" >&2
  FAILURE=1
else
  echo "PASS: no matching orphan processes"
  FAILURE=0
fi
echo "[6/6] Pull app-private native stdout/stderr diagnostics"
if ! "${ADB[@]}" exec-out run-as "$PACKAGE" cat files/diagnostics/native-runtime.log > "$OUT_DIR/native-runtime.log" 2>/dev/null; then
  echo "WARNING: native-runtime.log unavailable"
  : > "$OUT_DIR/native-runtime.log"
fi
if [[ "$FAILURE" -ne 0 || "$LOGCAT_STATUS" -ne 0 ]]; then
  echo "Diagnostic collection saved under $OUT_DIR"
  exit 1
fi
echo "Device bootstrap diagnostic collection complete: $OUT_DIR"
