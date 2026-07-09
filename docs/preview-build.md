# Preview APK workflow

Use this path for fast UI testing before a formal tag/release.

The preview build is a debug APK with package id `com.neatstudio.tmuxandroid.debug`.
It can be installed next to the formal release app, so preview version codes do not
block future formal releases.

## Build locally

```bash
scripts/setup-android-local.sh
scripts/build-preview-apk.sh
```

Output:

- `release/preview/tmux-android-preview.apk`
- `release/preview/latest.json`

## Upload to Gitea preview release

```bash
scripts/upload-gitea-preview.sh
```

The script reads `TMUX_GITEA_TOKEN` or prompts for a hidden token.
Before uploading, it deletes existing assets with the same names, so the preview
release keeps only one current APK and one current manifest.

Fixed preview URLs:

- Manifest: `https://gitea.neatcn.com/tmux/tmux-browser-android/releases/download/preview/latest.json`
- APK: `https://gitea.neatcn.com/tmux/tmux-browser-android/releases/download/preview/tmux-android-preview.apk`

## Notes

- Preview APKs are not formal releases and should not be tagged as `v*`.
- Preview uses debug signing unless a separate debug signing setup is added.
- The installed preview app is separate from the release app because Gradle applies
  `applicationIdSuffix = ".debug"` for debug builds.
