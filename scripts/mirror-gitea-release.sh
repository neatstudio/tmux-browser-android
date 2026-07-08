#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
Usage: scripts/mirror-gitea-release.sh TAG APK_PATH [VERSION_CODE] [VERSION_NAME]

Mirrors an already-built APK to the Gitea release for TAG and uploads a
Gitea-specific latest.json.

Token source:
  TMUX_GITEA_TOKEN environment variable, or hidden stdin prompt.
EOF
}

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
  usage
  exit 0
fi

if [ "$#" -lt 2 ] || [ "$#" -gt 4 ]; then
  usage
  exit 2
fi

TAG="$1"
APK_PATH="$2"
VERSION_NAME="${4:-${TAG#v}}"

if [ ! -f "$APK_PATH" ]; then
  echo "APK not found: $APK_PATH" >&2
  exit 1
fi

if [ -n "${3:-}" ]; then
  VERSION_CODE="$3"
else
  IFS=. read -r MAJOR MINOR PATCH <<EOF
$VERSION_NAME
EOF
  VERSION_CODE=$((MAJOR * 1000000 + MINOR * 1000 + PATCH))
fi

TOKEN="${TMUX_GITEA_TOKEN:-}"
if [ -z "$TOKEN" ]; then
  printf "Gitea token: " >&2
  IFS= read -rs TOKEN
  printf "\n" >&2
fi

if [ -z "$TOKEN" ]; then
  echo "Missing Gitea token." >&2
  exit 1
fi

API_ROOT="https://gitea.neatcn.com/api/v1/repos/tmux/tmux-browser-android"
RELEASE_PAGE_URL="https://gitea.neatcn.com/tmux/tmux-browser-android/releases/tag/${TAG}"
APK_URL="https://gitea.neatcn.com/tmux/tmux-browser-android/releases/download/${TAG}/tmux-android.apk"
SHA256="$(sha256sum "$APK_PATH" | cut -d " " -f 1)"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

LATEST_JSON="${WORK_DIR}/latest.json"
cat > "$LATEST_JSON" <<JSON
{
  "versionCode": ${VERSION_CODE},
  "versionName": "${VERSION_NAME}",
  "apkUrl": "${APK_URL}",
  "sha256": "${SHA256}",
  "releasePageUrl": "${RELEASE_PAGE_URL}",
  "minSdk": 26
}
JSON

RELEASE_JSON="${WORK_DIR}/release.json"
BODY='{"tag_name":"'"${TAG}"'","target_commitish":"main","name":"tmux Android '"${VERSION_NAME}"'","body":"Android APK for tmux-ui remote testing.","draft":false,"prerelease":false}'

if ! curl -fsSL \
  -X POST \
  -H "Authorization: token ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "$BODY" \
  "${API_ROOT}/releases" \
  -o "$RELEASE_JSON"; then
  curl -fsSL \
    -H "Authorization: token ${TOKEN}" \
    "${API_ROOT}/releases/tags/${TAG}" \
    -o "$RELEASE_JSON"
fi

RELEASE_ID="$(sed -n 's/^{"id":\([0-9][0-9]*\),.*/\1/p' "$RELEASE_JSON" | head -1)"
if [ -z "$RELEASE_ID" ]; then
  echo "Cannot resolve Gitea release id for ${TAG}." >&2
  exit 1
fi

upload_asset() {
  local file="$1"
  local name="$2"
  local type="$3"
  curl -fsSL \
    -X POST \
    -H "Authorization: token ${TOKEN}" \
    -F "attachment=@${file};type=${type}" \
    "${API_ROOT}/releases/${RELEASE_ID}/assets?name=${name}" \
    -o "${WORK_DIR}/${name}.asset.json"
}

upload_asset "$APK_PATH" "tmux-android.apk" "application/vnd.android.package-archive"
upload_asset "$LATEST_JSON" "latest.json" "application/json"

echo "Mirrored ${TAG} to Gitea release ${RELEASE_ID}"
echo "versionCode=${VERSION_CODE}"
echo "versionName=${VERSION_NAME}"
echo "sha256=${SHA256}"
