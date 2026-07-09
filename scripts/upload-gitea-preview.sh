#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK_PATH="${1:-${ROOT_DIR}/release/preview/tmux-android-preview.apk}"
LATEST_JSON="${2:-${ROOT_DIR}/release/preview/latest.json}"
TAG="preview"
API_ROOT="https://gitea.neatcn.com/api/v1/repos/tmux/tmux-browser-android"

if [ ! -f "${APK_PATH}" ]; then
  echo "APK not found: ${APK_PATH}" >&2
  exit 1
fi
if [ ! -f "${LATEST_JSON}" ]; then
  echo "latest.json not found: ${LATEST_JSON}" >&2
  exit 1
fi

TOKEN="${TMUX_GITEA_TOKEN:-}"
if [ -z "${TOKEN}" ]; then
  printf "Gitea token: " >&2
  IFS= read -rs TOKEN
  printf "\n" >&2
fi
if [ -z "${TOKEN}" ]; then
  echo "Missing Gitea token." >&2
  exit 1
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT

RELEASE_JSON="${WORK_DIR}/release.json"
BODY='{"tag_name":"preview","target_commitish":"main","name":"tmux Android Preview","body":"Mutable preview APK for fast UI testing. This is not a formal release.","draft":false,"prerelease":true}'

if ! curl -fsSL \
  -X POST \
  -H "Authorization: token ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "${BODY}" \
  "${API_ROOT}/releases" \
  -o "${RELEASE_JSON}"; then
  curl -fsSL \
    -H "Authorization: token ${TOKEN}" \
    "${API_ROOT}/releases/tags/${TAG}" \
    -o "${RELEASE_JSON}"
fi

RELEASE_ID="$(sed -n 's/^{"id":\([0-9][0-9]*\),.*/\1/p' "${RELEASE_JSON}" | head -1)"
if [ -z "${RELEASE_ID}" ]; then
  echo "Cannot resolve Gitea release id for preview." >&2
  exit 1
fi

upload_asset() {
  local file="$1"
  local name="$2"
  local type="$3"
  local assets_json old_ids old_id
  assets_json="${WORK_DIR}/assets-${name}.json"
  curl -fsSL -H "Authorization: token ${TOKEN}" \
    "${API_ROOT}/releases/${RELEASE_ID}/assets" \
    -o "${assets_json}"
  old_ids="$(python3 - "${assets_json}" "${name}" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as handle:
    assets = json.load(handle)
for asset in assets:
    if asset.get("name") == sys.argv[2]:
        print(asset.get("id"))
PY
)"
  for old_id in ${old_ids}; do
    curl -fsSL -X DELETE -H "Authorization: token ${TOKEN}" \
      "${API_ROOT}/releases/${RELEASE_ID}/assets/${old_id}" \
      -o /dev/null
  done
  curl -fsSL \
    -X POST \
    -H "Authorization: token ${TOKEN}" \
    -F "attachment=@${file};type=${type}" \
    "${API_ROOT}/releases/${RELEASE_ID}/assets?name=${name}" \
    -o "${WORK_DIR}/${name}.asset.json"
}

upload_asset "${APK_PATH}" "tmux-android-preview.apk" "application/vnd.android.package-archive"
upload_asset "${LATEST_JSON}" "latest.json" "application/json"

echo "Uploaded preview release ${RELEASE_ID}"
echo "Manifest: https://gitea.neatcn.com/tmux/tmux-browser-android/releases/download/preview/latest.json"
echo "APK: https://gitea.neatcn.com/tmux/tmux-browser-android/releases/download/preview/tmux-android-preview.apk"
