# tmux Android

Android API client for `tmux-ui` / `tmux-browser`.

The server project is expected to already be running on port 3000. This app does
not host or load the existing web UI. It calls the existing HTTP and WebSocket
APIs directly:

- server URL management for `http://127.0.0.1:3000` and Tailscale `100.x.y.z:3000`
- native tmux session list
- create, rename, send command, split pane, select pane, kill pane, pin, mute,
  and kill session through HTTP API
- open one live terminal viewer through `/ws/terminal`
- native `/ws/events` listener for session invalidation and hook notifications
- native API action center for health, server status, timeline, preferences,
  kanban projects, group messages, hook events, image file/URL upload, image
  preview info, and native image preview display
- mobile soft-key row for tmux-oriented input
- automatic update checks against a GitHub Release manifest
- APK download, SHA-256 verification, and installer handoff

## Server URL

Default:

```text
http://127.0.0.1:3000
```

On a physical Android device, `127.0.0.1` means the phone itself. For remote
testing, install Tailscale on the phone and use:

```text
http://100.x.y.z:3000
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

Publish a test build by running the `Android APK` workflow manually with
`publish_release=true`, or by pushing a `v*` tag.

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

## Auto Update

Normal Android apps cannot silently replace themselves. This app checks the
release manifest automatically, downloads the newer APK, verifies its SHA-256,
then opens Android's package installer. The user still has to approve the
install, and Android 8+ may require allowing this app to install unknown apps.

The default update manifest is:

```text
https://github.com/<owner>/<repo>/releases/latest/download/latest.json
```

The workflow uploads both the APK and `latest.json` to each release.
