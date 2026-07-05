# tmux Android

Android API client for `tmux-ui` / `tmux-browser`.

The server project is expected to already be running on port 3000. This app does
not host or load the existing web UI. It calls the existing HTTP and WebSocket
APIs directly:

- server URL management for Tailscale `100.x.y.z:3000` APIs
- quick server selectors for `100.89.0.2`, `100.89.0.4`, `100.89.0.9`,
  `100.89.0.11`, and `100.89.0.116`
- native tmux session list
- create, rename, send command, split pane, select pane, kill pane, pin, mute,
  and kill session through HTTP API
- open one live terminal viewer through `/ws/terminal`
- native `/ws/events` listener for session invalidation and hook notifications
- GitHub and Gitea update manifest mirrors with fallback
- native API action center for health, server status, timeline, preferences,
  kanban projects, group messages, hook events, image file/URL upload, image
  preview info, and native image preview display
- mobile soft-key row for tmux-oriented input
- automatic update checks against a GitHub Release manifest
- APK download, SHA-256 verification, and installer handoff

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

Publish a test build by pushing a `v*` tag. That creates a GitHub Release with:

```text
https://github.com/neatstudio/tmux-browser-android/releases/latest/download/tmux-android.apk
https://github.com/neatstudio/tmux-browser-android/releases/latest/download/latest.json
https://gitea.neatcn.com/tmux/tmux-browser-android/releases/latest/download/tmux-android.apk
https://gitea.neatcn.com/tmux/tmux-browser-android/releases/latest/download/latest.json
```

Plain branch builds only create Actions artifacts; they are useful for CI
verification, but releases are the stable download/update channel.

Unsigned/debug workflow artifacts are useful only for smoke testing install and
launch. Automatic in-place updates require release APKs signed with the same
keystore every time; otherwise Android treats the APK as a different or
incompatible package.

## Current Terminal Scope

The terminal screen connects to `/ws/terminal` and sends the upstream protocol
messages unchanged: `attach`, `input`, `resize`, `scroll`, and `clear-history`.
The first Android UI renders terminal output as basic monospace text with ANSI
escape filtering. It is enough for shell-oriented remote testing, but it is not
yet a complete xterm-compatible renderer for full-screen TUIs such as `vim` or
`top`.

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

The Gitea mirror is:

```text
https://gitea.neatcn.com/tmux/tmux-browser-android/releases/latest/download/latest.json
```

The app tries the selected update source first, then falls back to the GitHub
and Gitea mirrors.

The workflow uploads both the APK and `latest.json` to each release.
