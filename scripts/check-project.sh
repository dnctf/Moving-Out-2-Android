#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if [[ -x ./gradlew ]]; then
  ./gradlew --no-daemon assembleDebug testDebugUnitTest
else
  gradle --no-daemon assembleDebug testDebugUnitTest
fi
