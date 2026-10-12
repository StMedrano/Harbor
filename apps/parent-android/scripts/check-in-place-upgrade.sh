#!/usr/bin/env bash
# Read-only Android upgrade guard. Does NOT install or uninstall an app.
set -euo pipefail
usage() {
  echo "Usage: $0 /path/to/Harbor-Family-0.17-development-debug.apk" >&2
  echo "Requires an authorized Android device connected via adb and Android SDK aapt/apksigner in PATH." >&2
}
if [[ $# != 1 || ! -f "$1" ]]; then usage; exit 2; fi
for tool in adb aapt apksigner; do
  if ! command -v "$tool" >/dev/null 2>&1; then echo "Missing Android SDK tool: $tool" >&2; exit 2; fi
done
app="dev.stmedrano.harbor.parent"
candidate="$1"
# adb fails closed if no device or more than one device has been selected.
if [[ "$(adb get-state 2>/dev/null)" != "device" ]]; then
  echo "Connect and authorize one test phone; use ANDROID_SERIAL to select a device." >&2
  exit 2
fi
installed_path="$(adb shell pm path "$app" | tr -d '\r' | sed -n 's/^package:\(.*\/base\.apk\)$/\1/p' | head -n 1)"
if [[ -z "$installed_path" ]]; then
  echo "Harbor Family is not installed under $app. This is not an in-place upgrade." >&2
  exit 3
fi
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
adb pull "$installed_path" "$tmp/installed.apk" >/dev/null
metadata() {
  aapt dump badging "$1" | sed -n "s/^package: name='\([^']*\)' versionCode='\([0-9][0-9]*\)'.*/\1 \2/p" | head -n 1
}
read -r old_package old_code < <(metadata "$tmp/installed.apk")
read -r new_package new_code < <(metadata "$candidate")
if [[ "$old_package" != "$app" || "$new_package" != "$app" ||
      ! "$old_code" =~ ^[0-9]+$ || ! "$new_code" =~ ^[0-9]+$ ]]; then
  echo "Package/version metadata mismatch; will not recommend an update." >&2
  exit 4
fi
if (( 10#$new_code <= 10#$old_code )); then
  echo "Candidate versionCode $new_code is not newer than installed versionCode $old_code." >&2
  exit 4
fi
certificate() {
  apksigner verify --print-certs "$1" |
    sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -n 1
}
old_cert="$(certificate "$tmp/installed.apk")"
new_cert="$(certificate "$candidate")"
if [[ -z "$old_cert" || -z "$new_cert" || "$old_cert" != "$new_cert" ]]; then
  echo "SIGNATURE MISMATCH. Do not uninstall Harbor Family or install over it." >&2
  echo "Rebuild/sign v0.17 using the same signing key as your installed v0.16." >&2
  exit 5
fi
echo "SAFE_TO_UPGRADE: matching package ($app), signature, and increasing versionCode ($old_code -> $new_code)."
echo "No installation was performed. When ready, use: adb install -r \"$candidate\""
