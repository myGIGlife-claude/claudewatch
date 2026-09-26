<div align="center">

# ClaudeWatch

**Watch your Claude Code server from your phone's home screen.**

[![Build](https://github.com/myGIGlife-claude/claude-vps-widget/actions/workflows/build.yml/badge.svg)](https://github.com/myGIGlife-claude/claude-vps-widget/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/myGIGlife-claude/claude-vps-widget)](https://github.com/myGIGlife-claude/claude-vps-widget/releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)
![Server: Python 3 stdlib](https://img.shields.io/badge/server-Python%203%20stdlib-3776AB?logo=python&logoColor=white)

</div>

ClaudeWatch is an Android app and home-screen widget for people who run [Claude Code](https://docs.anthropic.com/en/docs/claude-code) on a VPS. It shows CPU, memory, zram, disk, network, Anthropic API latency and every running Claude Code session, and it warns you before the box starts swapping or the OOM killer steps in.

It connects over SSH with a key locked to a single read-only command. The app can't open a shell or run anything else on your server.

## Features

- **Home-screen widget** with CPU, memory, disk and zram bars, a Claude session count, API latency and the current top alert. It refreshes every 15 minutes, or when you tap ↻.
- **Live dashboard** in the app that polls every 5 seconds while it's open, with per-core CPU, memory pressure (PSI), disk and network I/O, earlyoom status and failed systemd units.
- **Claude Code sessions**: PID, CPU, memory including MCP child processes, working directory and age for each session.
- **Health alerts**: low RAM, memory/IO/CPU pressure, CPU steal, a full disk, swap overflow, an unreachable API, OOM kills and failed units.
- **No accounts, no cloud, no tracking.** Your phone talks directly to your server.

## How it works

```
 Phone (ClaudeWatch)  ──SSH, restricted key──▶  VPS: /usr/local/bin/claude-dash --json
                      ◀────── JSON stats ──────  (reads /proc and /sys, Python stdlib)
```

## Quick start

### 1. Install the server script on your VPS

```sh
git clone https://github.com/myGIGlife-claude/claude-vps-widget.git
sudo install -m 755 claude-vps-widget/server/claude-dash.py /usr/local/bin/claude-dash
claude-dash --json   # should print one line of JSON
```

It needs Linux and Python 3, with no other dependencies. Run `claude-dash` on its own to get a live terminal dashboard as well.

### 2. Install the app

Download the latest `ClaudeWatch-*.apk` from [**Releases**](https://github.com/myGIGlife-claude/claude-vps-widget/releases/latest) on your phone and open it. Android will ask you to allow installs from your browser.

### 3. Connect

1. Enter your server's **host**, **username** and **port**, then tap **Save**.
2. Tap **Generate key**. The key pair is created on the phone, and the private key never leaves it.
3. Tap **Copy command**, SSH into the VPS as that user and paste the command. It adds the key to `~/.ssh/authorized_keys` with `command="/usr/local/bin/claude-dash --json",restrict`.
4. Tap **Connect**.
5. Long-press the home screen, open **Widgets** and add **ClaudeWatch**.

## Security

- The private key is RSA-3072, generated on the phone and encrypted with an AES-GCM key held in the Android Keystore.
- The server-side key is restricted by `command=` and `restrict`, so it gets no shell, no port forwarding, no agent forwarding and no PTY.
- The server's host key is trusted on first use and pinned after that. If it changes, the app refuses to connect until you confirm the change.

See [SECURITY.md](SECURITY.md) to report a vulnerability, and [PRIVACY.md](PRIVACY.md) for the privacy policy.

## Build from source

You need JDK 17 and Gradle 8.11+.

```sh
gradle assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Every push is built by [GitHub Actions](.github/workflows/build.yml), which uploads the APK as a build artifact.

## Project layout

```
app/src/main/java/dev/vpsdash/
  MainActivity.kt      setup screen and live dashboard (Compose)
  widget/StatsWidget.kt  home-screen widget (Glance)
  SshClient.kt         SSH fetch and host-key pinning (JSch)
  KeyManager.kt        on-device key generation, Keystore encryption
  Refresh.kt           WorkManager background refresh
  Stats.kt             JSON model and formatting
server/claude-dash.py  stats collector and terminal dashboard
```

## Contributing

Issues and pull requests are welcome. If you change the JSON produced by `claude-dash`, update `Stats.kt` in the same PR.

## License

[MIT](LICENSE)

---

ClaudeWatch is an independent project. It is not affiliated with, endorsed by or sponsored by Anthropic. Claude and Claude Code are trademarks of Anthropic, PBC.
