#!/usr/bin/env bash
# Usage: rebrand.sh <path-to-pojav-checkout>
set -euo pipefail
cd "$1"
GRADLE=$(ls app_pojavlauncher/build.gradle* | head -n1)
sed -E -i 's/applicationId\s*=?\s*["'\''][^"'\'']*["'\'']/applicationId "net.dlauncher.app"/' "$GRADLE"
grep -q 'net.dlauncher.app' "$GRADLE" || { echo "applicationId not rebranded in $GRADLE; edit scripts/rebrand.sh"; exit 1; }
STR=app_pojavlauncher/src/main/res/values/strings.xml
[ -f "$STR" ] && sed -i 's#<string name="app_name">[^<]*</string>#<string name="app_name">DLauncher</string>#' "$STR" || echo "WARN: strings.xml not found, app name unchanged"
echo "Rebranded OK. Do NOT rename the Java package (JNI symbols depend on it)."
