#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CI_DIR="${ROOT_DIR}/.ci"
GRADLE_VERSION="${GRADLE_VERSION:-8.10.2}"
GRADLE_HOME="${CI_DIR}/gradle-${GRADLE_VERSION}"
ANDROID_HOME="${ANDROID_HOME:-${CI_DIR}/android-sdk}"
CMDLINE_TOOLS="${ANDROID_HOME}/cmdline-tools/latest"
BUILD_NUMBER="${BUILD_NUMBER:-$(date -u +%m%d%H%M)}"
VERSION_CODE="${VERSION_CODE:-$((900000000 + 10#${BUILD_NUMBER}))}"
VERSION_NAME="${VERSION_NAME:-preview-${BUILD_NUMBER}}"

if [ ! -x "${GRADLE_HOME}/bin/gradle" ] || [ ! -x "${CMDLINE_TOOLS}/bin/sdkmanager" ]; then
  "${ROOT_DIR}/scripts/setup-android-local.sh"
fi

export ANDROID_HOME
export ANDROID_SDK_ROOT="${ANDROID_HOME}"
export PATH="${GRADLE_HOME}/bin:${CMDLINE_TOOLS}/bin:${ANDROID_HOME}/platform-tools:${PATH}"

cd "${ROOT_DIR}"
gradle :app:assembleDebug \
  -PversionCode="${VERSION_CODE}" \
  -PversionName="${VERSION_NAME}" \
  -PrepoSlug="neatstudio/tmux-browser-android"

OUT_DIR="${ROOT_DIR}/release/preview"
mkdir -p "${OUT_DIR}"
APK_PATH="$(find app/build/outputs/apk/debug -name '*.apk' | sort | tail -n 1)"
cp "${APK_PATH}" "${OUT_DIR}/tmux-android-preview.apk"
SHA256="$(sha256sum "${OUT_DIR}/tmux-android-preview.apk" | cut -d " " -f 1)"

cat > "${OUT_DIR}/latest.json" <<JSON
{
  "versionCode": ${VERSION_CODE},
  "versionName": "${VERSION_NAME}",
  "apkUrl": "https://gitea.neatcn.com/tmux/tmux-browser-android/releases/download/preview/tmux-android-preview.apk",
  "sha256": "${SHA256}",
  "releasePageUrl": "https://gitea.neatcn.com/tmux/tmux-browser-android/releases/tag/preview",
  "minSdk": 26
}
JSON

ls -lh "${OUT_DIR}/tmux-android-preview.apk" "${OUT_DIR}/latest.json"
sha256sum "${OUT_DIR}/tmux-android-preview.apk"
