#!/usr/bin/env bash
# Deploy portal-gphotos to a Meta Portal over adb in one shot:
#   install the APK -> push the OAuth client (+ optional pre-minted token)
#   -> grant the settings permissions -> register the screensaver -> launch.
#
# Idempotent: safe to re-run. Everything but the APK is optional, so this also
# works as a "reconfigure permissions/screensaver" pass on an already-installed app.
#
# Non-destructive: a debug<->release switch changes the signing key, which forces an
# uninstall+install. We preserve the downloaded media + OAuth token across that by
# stashing the app's files dir on /sdcard and moving it back after the fresh install.
#
# Usage:
#   scripts/deploy.sh [-s SERIAL] [--apk PATH] [--client client_secret.json]
#                     [--token token.json] [--build]
#
# Files default to ./client_secret.json and ./token.json if present (else skipped).
# adb is found via $ADB, $ANDROID_HOME/platform-tools, or PATH.
set -euo pipefail

PKG="com.ramnat.portalgphotos"
DREAM="$PKG/$PKG.PhotoDreamService"
FILES_DIR="/sdcard/Android/data/$PKG/files"

SERIAL="${SERIAL:-}"
if [[ -n "${APK:-}" ]]; then
  # Keep whatever the user explicitly passed
  :
elif [[ -f "app-release.apk" ]]; then
  APK="app-release.apk"
elif [[ -f "app/build/outputs/apk/release/app-release.apk" ]]; then
  APK="app/build/outputs/apk/release/app-release.apk"
else
  APK="app/build/outputs/apk/debug/app-debug.apk"
fi

CLIENT="${CLIENT:-client_secret.json}"
TOKEN="${TOKEN:-token.json}"
DO_BUILD=0

usage() { sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    -s|--serial) SERIAL="$2"; shift 2;;
    --apk)       APK="$2"; shift 2;;
    --client)    CLIENT="$2"; shift 2;;
    --token)     TOKEN="$2"; shift 2;;
    --build)     DO_BUILD=1; shift;;
    -h|--help)   usage; exit 0;;
    *) echo "unknown arg: $1" >&2; usage >&2; exit 1;;
  esac
done

# --- locate adb ---
ADB="${ADB:-}"
if [[ -z "$ADB" ]]; then
  if command -v adb >/dev/null 2>&1; then ADB="$(command -v adb)"
  elif [[ -n "${ANDROID_HOME:-}" && -x "$ANDROID_HOME/platform-tools/adb" ]]; then ADB="$ANDROID_HOME/platform-tools/adb"
  elif [[ -x "$HOME/Android/Sdk/platform-tools/adb" ]]; then ADB="$HOME/Android/Sdk/platform-tools/adb"
  else echo "adb not found — set ADB=/path/to/adb or ANDROID_HOME" >&2; exit 1; fi
fi

# --- pick the device ---
if [[ -z "$SERIAL" ]]; then
  mapfile -t DEVICES < <("$ADB" devices | awk 'NR>1 && $2=="device"{print $1}')
  case ${#DEVICES[@]} in
    0) echo "no authorized adb devices — connect the Portal and enable ADB" >&2; exit 1;;
    1) SERIAL="${DEVICES[0]}";;
    *) echo "multiple devices; pass -s SERIAL. Found: ${DEVICES[*]}" >&2; exit 1;;
  esac
fi
adb() { "$ADB" -s "$SERIAL" "$@"; }
echo ">> device: $SERIAL"

# --- optional build ---
if [[ $DO_BUILD -eq 1 ]]; then
  echo ">> ./gradlew assembleRelease"
  ./gradlew assembleRelease
  # Re-evaluate APK if we just built the release one and the user didn't specify one
  if [[ -z "${APK:-}" || "$APK" == "app/build/outputs/apk/debug/app-debug.apk" ]]; then
    APK="app/build/outputs/apk/release/app-release.apk"
  fi
fi
[[ -f "$APK" ]] || { echo "APK not found: $APK (download it, or run with --build)" >&2; exit 1; }

# --- install (non-destructive across debug<->release signature changes) ---
# `adb install -r` fails when the new APK's signing key differs from the installed
# one (debug vs release), and the only way through is uninstall+install — which
# normally wipes the app's external files, losing all downloaded media + the token.
# So on a signature mismatch we move the files dir to a top-level /sdcard backup
# (outside the app dir, so uninstall can't touch it; a rename on the same fs, instant),
# reinstall fresh, then move it back. A plain in-place update keeps data and skips all this.
echo ">> install $APK"
install_out="$(adb install -r "$APK" 2>&1 || true)"
if grep -q "Success" <<<"$install_out"; then
  echo "   ok (in-place update, data preserved)"
elif grep -qiE "INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match" <<<"$install_out"; then
  echo "   signature mismatch (debug<->release) — preserving media + token across reinstall"
  BAK="/sdcard/portal-gphotos-deploy.bak"
  adb shell "rm -rf '$BAK'"
  if adb shell "test -d '$FILES_DIR'"; then
    adb shell "mv '$FILES_DIR' '$BAK'" && echo "   backed up app files -> $BAK"
    HAVE_BAK=1
  else
    HAVE_BAK=0
  fi
  adb uninstall "$PKG" >/dev/null && echo "   uninstalled old build"
  adb install "$APK" >/dev/null && echo "   installed fresh"
  if [[ "${HAVE_BAK:-0}" -eq 1 ]]; then
    adb shell "mkdir -p '$(dirname "$FILES_DIR")' && rm -rf '$FILES_DIR' && mv '$BAK' '$FILES_DIR'" \
      && echo "   restored app files (media + token)"
  fi
else
  echo "   install failed:" >&2
  echo "$install_out" >&2
  exit 1
fi

# --- push config ---
adb shell mkdir -p "$FILES_DIR"
if [[ -f "$CLIENT" ]]; then echo ">> push $CLIENT"; adb push "$CLIENT" "$FILES_DIR/client_secret.json" >/dev/null && echo "   ok"
else echo ">> no $CLIENT found — pass --client PATH to push one, or use a build with baked-in creds"; fi
if [[ -f "$TOKEN" ]]; then echo ">> push $TOKEN (pre-minted)"; adb push "$TOKEN" "$FILES_DIR/token.json" >/dev/null && echo "   ok"; fi

# --- permissions (non-fatal: app degrades gracefully without them) ---
echo ">> grant settings permissions"
adb shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS && echo "   WRITE_SECURE_SETTINGS ok" \
  || echo "   WRITE_SECURE_SETTINGS failed (screensaver re-assert disabled)"
adb shell appops set "$PKG" WRITE_SETTINGS allow && echo "   WRITE_SETTINGS ok" \
  || echo "   WRITE_SETTINGS failed (sleep-when-alone timeout change disabled)"

# --- register our screensaver so an idle Portal drops into the frame ---
echo ">> register screensaver"
adb shell settings put secure screensaver_components "$DREAM"
adb shell settings put secure screensaver_activate_on_sleep 1

# --- launch ---
echo ">> launch"
adb shell am start -n "$PKG/.MainActivity" >/dev/null
echo ">> done. If this is a first install with no token, tap 'Sign in on this device'."
