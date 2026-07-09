#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CI_DIR="${ROOT_DIR}/.ci"
GRADLE_VERSION="${GRADLE_VERSION:-8.10.2}"
ANDROID_TOOLS_ZIP="${ANDROID_TOOLS_ZIP:-commandlinetools-linux-11076708_latest.zip}"
GRADLE_HOME="${CI_DIR}/gradle-${GRADLE_VERSION}"
ANDROID_HOME="${ANDROID_HOME:-${CI_DIR}/android-sdk}"
CMDLINE_TOOLS="${ANDROID_HOME}/cmdline-tools/latest"

mkdir -p "${CI_DIR}"

if [ ! -x "${GRADLE_HOME}/bin/gradle" ]; then
  curl -fSL --connect-timeout 20 --retry 3 --retry-delay 2 --max-time 600 \
    -o "${CI_DIR}/gradle.zip" \
    "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
  unzip -q "${CI_DIR}/gradle.zip" -d "${CI_DIR}"
fi

if [ ! -x "${CMDLINE_TOOLS}/bin/sdkmanager" ]; then
  mkdir -p "${ANDROID_HOME}/cmdline-tools"
  curl -fSL --connect-timeout 20 --retry 3 --retry-delay 2 --max-time 600 \
    -o "${CI_DIR}/android-tools.zip" \
    "https://dl.google.com/android/repository/${ANDROID_TOOLS_ZIP}"
  unzip -q "${CI_DIR}/android-tools.zip" -d "${ANDROID_HOME}/cmdline-tools"
  rm -rf "${CMDLINE_TOOLS}"
  mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${CMDLINE_TOOLS}"
fi

export ANDROID_HOME
export ANDROID_SDK_ROOT="${ANDROID_HOME}"
export PATH="${GRADLE_HOME}/bin:${CMDLINE_TOOLS}/bin:${ANDROID_HOME}/platform-tools:${PATH}"

yes | sdkmanager --licenses >/dev/null || true
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"

echo "ANDROID_HOME=${ANDROID_HOME}"
echo "GRADLE_HOME=${GRADLE_HOME}"
