# Android Client Notes

This project is based on the upstream `tmux-browser` documents under
`docs/`, especially:

- `docs/api.md`
- `docs/superpowers/specs/2026-04-17-browser-tmux-dashboard-design.md`
- `docs/superpowers/specs/2026-04-21-pty-streaming-terminal-design.md`
- `docs/superpowers/specs/2026-04-30-harmonyos-mobile-tmux-manager-design.md`

## What The Upstream Docs Say

The mobile design document is HarmonyOS-first and recommends a native ArkTS
client with these modules:

- connection profile store
- session API client
- terminal WebSocket client
- terminal core
- terminal view
- shortcut bar
- session list and terminal screens

It also says Android is second priority and WebView terminal rendering is out of
scope for that first HarmonyOS version.

## Android Request Reconciliation

The current request is different from that document in two important ways:

- target platform is Android first
- remote testing requires online APK builds and app-side update checks

For that reason, this repository starts with an Android API-client MVP. The
existing server is expected to already be running on port 3000. The app calls
the server HTTP API and terminal WebSocket directly instead of loading the
existing browser UI.

This is not yet the full native-terminal architecture described by the HarmonyOS
design document, but every exposed Android feature is native and calls the
server API directly. There is no WebView fallback.

## Backend Contract

The Android client must not change the server protocol.

HTTP:

- `GET /api/sessions`
- `POST /api/sessions`
- `DELETE /api/sessions/:name`
- `POST /api/sessions/:name/input`

WebSocket:

- `/ws/terminal`
- `/ws/events`
- `attach`
- `input`
- `resize`
- `scroll`
- `clear-history`

The server remains the source of truth. Closing the app or terminal viewer must
not kill a tmux session.

## Current MVP

Implemented now:

- configurable base URL, defaulting to `http://100.89.0.116:3000`
- support for Tailscale URLs such as `http://100.x.y.z:3000`
- native multi-page shell with `Sessions`, `Projects`, `Tools`, `Update`, and
  `About`
- native session list from `GET /api/sessions`
- create, rename, command send, split, pane select, pane kill, pin, mute, and
  kill session through documented session/preference endpoints
- basic live terminal through `/ws/terminal`
- native event stream through `/ws/events`
- bottom shortcut bar for `Esc`, `Tab`, `Ctrl+C`, `Ctrl+V`, arrows, page keys,
  tmux prefix actions, and paste
- shortcut delivery through the terminal WebSocket `input` message
- native Projects page for kanban project grouping, project agents, project
  messages, add/remove session, create, and delete actions
- native Tools page for health, server status, timeline, preferences, hook
  events, image file/URL upload, image preview metadata, and native image
  preview display
- GitHub Actions APK build
- release manifest `latest.json`
- selected-source update checks; Gitea and GitHub are not probed in the same
  update check
- one-download-per-version APK cache, SHA-256 verification, and installer
  handoff
- permission/about surfaces for unknown-app install status, notification status,
  app settings, app version/build type, package name, selected update source, and
  HTTP/WebSocket API/protocol summary

## Update And Release Policy

The Android app cannot silently replace itself. It may download a newer APK and
open Android's package installer, but the user must approve the install. On
Android 8+, the user may also need to allow this app to install unknown apps.

The app provides explicit update checks for Auto, Gitea, GitHub, and Selected.
Auto checks Gitea first because phones may not reach GitHub reliably, then tries
GitHub only if Gitea cannot be reached. The manual Gitea, GitHub, and Selected
buttons force one source. Transient network failures are retried against the
current source before a source is considered failed.

Downloaded APKs are cached by `versionCode`. If a cached APK exists and its
SHA-256 matches the manifest, the app reuses it instead of downloading the same
version again. This matters when Android redirects the user to unknown-app
install settings before the installer can run. After the user grants that
permission and returns to the app, the app resumes installation of the pending
APK instead of asking the user to run update again.

Only `v*` tags publish GitHub Releases. Main branch builds and manual workflow
runs are for CI artifacts and should be used to validate grouped changes. Do not
publish a new tag for every small UI copy or layout change; publish when there
is a useful feature or test batch for phone-side validation.

## Native Roadmap

To converge with the upstream mobile design, the next implementation should add
native Android modules in this order:

1. richer native layouts for group messages, timeline, and preferences
2. `TerminalCore` with ANSI parsing, cursor state, colors, and dirty rows
3. configurable shortcut bar backed directly by WebSocket `input`
