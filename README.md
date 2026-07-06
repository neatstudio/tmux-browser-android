# tmux Android

Android API client for `tmux-ui` / `tmux-browser`.

The server project is expected to already be running on port 3000. This app does
not host or load the existing web UI. It calls the existing HTTP and WebSocket
APIs directly:

- server URL management for Tailscale `100.x.y.z:3000` APIs
- quick server selectors for `100.89.0.2`, `100.89.0.4`, `100.89.0.9`,
  `100.89.0.11`, and `100.89.0.116`
- one-tap probe for the preset Tailscale APIs, showing health/version and
  session counts
- native tmux session list
- Sessions page with current API/server and loaded session count
- create, rename, send command, split pane, select pane, kill pane, pin, mute,
  and kill session through HTTP API
- open one live terminal viewer through `/ws/terminal`
- native `/ws/events` listener for session invalidation and hook notifications
- selectable GitHub or Gitea update manifest source
- native Tools page for health, server status, timeline, preferences,
  kanban projects, group messages, hook events, image file/URL upload, image
  preview info, and native image preview display
- mobile soft-key row for tmux-oriented input, including tmux prefix, detach,
  new window, previous/next window, Ctrl keys, arrows, page keys, and paste
- automatic update checks against the selected release manifest
- one-download-per-version APK cache, SHA-256 verification, and installer
  handoff
- native Update and About pages for version, protocol, permission, and release
  information

## Server URL

Default:

```text
http://100.89.0.116:3000
```

On a physical Android device, `127.0.0.1` means the phone itself. The app
therefore defaults to Tailscale and includes quick selectors for:

```text
http://100.89.0.2:3000
http://100.89.0.4:3000
http://100.89.0.9:3000
http://100.89.0.11:3000
http://100.89.0.116:3000
```

The upstream server should stay bound to localhost or a private Tailscale IP.
Do not expose terminal control on `0.0.0.0` to the public internet.

## GitHub Actions Build

The workflow in `.github/workflows/android.yml` builds APKs online.

Required environment on Actions:

- `ubuntu-latest`
- JDK 17
- Android SDK platform 35 installed by `android-actions/setup-android`
- Gradle 8.10.2 installed by `gradle/actions/setup-gradle`
- Android Gradle Plugin 8.7.3 from Google Maven

For release builds that can update an already installed app, configure these
repository secrets:

```text
ANDROID_KEYSTORE_BASE64
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

Create the base64 value from your release keystore:

```bash
base64 -w 0 tmux-android-release.jks
```

Branch builds and manual workflow runs create Actions artifacts only. Use them
to verify grouped changes before publishing.

Publish a release build by pushing a `v*` tag. A tag should be reserved for a
coherent feature/test batch, not every small UI or text change. Tag publishing
creates a GitHub Release with:

```text
https://github.com/neatstudio/tmux-browser-android/releases/latest/download/tmux-android.apk
https://github.com/neatstudio/tmux-browser-android/releases/latest/download/latest.json
```

Those GitHub links are the primary public install/update channel. Gitea releases
are mirrored as a second public source. This Gitea instance does not support the
GitHub-style `/releases/latest/download/...` URL, so the app uses the Gitea
Release API as the stable Gitea update entrypoint.

Plain branch builds only create Actions artifacts; they are useful for CI
verification, but releases are the stable download/update channel.

Unsigned/debug workflow artifacts are useful only for smoke testing install and
launch. Automatic in-place updates require release APKs signed with the same
keystore every time; otherwise Android treats the APK as a different or
incompatible package.

## Current Terminal Scope

The terminal screen connects to `/ws/terminal` and sends the upstream protocol
messages unchanged: `attach`, `input`, `resize`, `scroll`, and `clear-history`.
The first Android UI renders terminal output as monospace text with basic ANSI
SGR color support. The terminal view stays bottom-aligned when output is short,
auto-scrolls as data arrives, and adjusts its bottom inset when the soft keyboard
opens. It is enough for shell-oriented remote testing, but it is not yet a
complete xterm-compatible renderer for full-screen TUIs such as `vim` or `top`.

The terminal toolbar and shortcut row include tmux prefix helpers. The app sends
the same control bytes a keyboard would send, for example `Ctrl+B`, `Ctrl+B d`,
`Ctrl+B c`, `Ctrl+B n`, and `Ctrl+B p`.

All app features are native Android controls. Complex server objects such as
kanban projects, preferences, timeline events, group messages, and image metadata
currently use native forms plus native JSON detail dialogs; image preview uses a
native `ImageView`. The app does not load the browser UI.

## Permissions

The app needs network access and package install handoff for updates. Android
does not ask at runtime for normal internet access. Android 8+ requires the user
to allow this app to install unknown apps before automatic update installation
can continue. Android 13+ may ask for notification permission.

SMS permission is intentionally not requested because tmux-browser-android does
not read or send SMS.

## Auto Update

Normal Android apps cannot silently replace themselves. This app checks the
release manifest automatically, downloads the newer APK, verifies its SHA-256,
then opens Android's package installer. The user still has to approve the
install, and Android 8+ may require allowing this app to install unknown apps.

The default update manifest is:

```text
https://github.com/neatstudio/tmux-browser-android/releases/latest/download/latest.json
```

The public manual APK download is:

```text
https://github.com/neatstudio/tmux-browser-android/releases/latest/download/tmux-android.apk
```

The app checks only the selected update source. It does not probe GitHub and
Gitea during the same update check. Choose the source in the app's `Update`
page, or use a custom manifest/API URL.

The built-in Gitea API endpoint is:

```text
https://gitea.neatcn.com/api/v1/repos/tmux/tmux-browser-android/releases/latest
```

Gitea tag-specific assets are also public, for example:

```text
https://gitea.neatcn.com/tmux/tmux-browser-android/releases/download/v0.1.7/latest.json
https://gitea.neatcn.com/tmux/tmux-browser-android/releases/download/v0.1.7/tmux-android.apk
```

In the app:

- Open the `Update` page to check `latest.json`, download the APK, verify
  SHA-256, and open Android's installer.
- If the same version APK was already downloaded and its SHA-256 still matches,
  the app reuses that file instead of downloading it again.
- If Android sends you to the unknown-app install permission screen, return to
  the app after allowing it; the app continues installing the already downloaded
  APK without another update check or download.
- Open the `Update` page and tap `APK` to resolve the APK from the selected
  update source and open it in a browser.
- Open the `Update` page and tap `Details` to see the installed version,
  selected update source, APK/Release resolver behavior, install permission
  state, and a `Check update` button.
- Open the `Update` page and tap `App settings` for Android's full per-app
  permission/settings screen.
- Open the `About` page to see the app version, package name, API/protocol
  summary, selected update source, and update policy.
