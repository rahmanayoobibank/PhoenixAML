#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
if ! command -v java >/dev/null 2>&1; then echo "ERROR: Java is required"; exit 2; fi
if [ -x ./gradlew ]; then ./gradlew assembleDebug; exit $?; fi
if command -v gradle >/dev/null 2>&1; then gradle assembleDebug; exit $?; fi
echo "ERROR: Gradle wrapper/system Gradle is missing. Install Android Studio/SDK + Gradle, then run this script."
exit 3
