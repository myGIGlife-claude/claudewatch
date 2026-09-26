<div align="center">

# ClaudeWatch

**Watch your Claude Code server from your phone's home screen.**

[![Build](https://github.com/myGIGlife-claude/claudewatch/actions/workflows/build.yml/badge.svg)](https://github.com/myGIGlife-claude/claudewatch/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/myGIGlife-claude/claudewatch)](https://github.com/myGIGlife-claude/claudewatch/releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)
![Server: Python 3 stdlib](https://img.shields.io/badge/server-Python%203%20stdlib-3776AB?logo=python&logoColor=white)

</div>

ClaudeWatch is an Android app and home-screen widget for people who run [Claude Code](https://docs.anthropic.com/en/docs/claude-code) on a VPS. It shows CPU, memory, zram, disk, network, Anthropic API latency and every running Claude Code session, and it warns you before the box starts swapping or the OOM killer steps in.

It connects over SSH with a key locked to a single read-only command. The app can't open a shell or run anything else on your servers.

## Features

- **Multiple servers.** Add as many as you like, switch between them in the app, and place one widget per server.
- **Home-screen widget** with CPU, memory and disk tiles, Claude plan usage with reset times, a Claude session count, API latency and the current top alert. It refreshes every 15 minutes, or when you tap ↻, and tapping the widget opens that server in the app.
- **Claude plan usage**: your 5-hour session and weekly limits as percentages, and when each one resets.
- **Live dashboard** in the app that polls every 5 seconds while it's open, with per-core CPU, memory pressure (PSI), zram, disk and network I/O, earlyoom status and failed systemd units.
- **Claude Code sessions**: CPU, memory including MCP child processes, working directory and age for each session.
- **Health alerts**: low RAM, memory/IO/CPU pressure, CPU steal, a full disk, swap overflow, an unreachable API, OOM kills and failed units.
- **Guided setup** with copy buttons for every command you need to run on the server.
- **No accounts, no cloud, no tracking.** Your phone talks directly to your servers.

## How it works

```
 Phone (ClaudeWatch)  ──SSH, restricted key──▶  server: /usr/local/bin/claude-dash --json
                      ◀────── JSON stats ──────  (reads /proc, /sys and Claude Code's login)
```

`claude-dash` is a single Python 3 script with no dependencies. On its own, it's also a live terminal dashboard: run `claude-dash`.

## Quick start

### 1. Install the app

Download the latest `ClaudeWatch-*.apk` from [**Releases**](https://github.com/myGIGlife-claude/claudewatch/releases/latest) on your phone and open it. Android will ask you to allow installs from your browser.

### 2. Add a server

The app walks you through this and gives you each command with a **Copy** button.

1. **Server**: enter the host, username and port (and a name if you like), then tap **Save**. Use the same user that runs Claude Code.
2. **Install the server script**: SSH into the server as that user and paste the command. It downloads `claude-dash` from this repo and installs it to `/usr/local/bin`, which needs `sudo` once:
   ```sh
   curl -fsSL https://raw.githubusercontent.com/myGIGlife-claude/claudewatch/main/server/claude-dash.py -o /tmp/claude-dash && sudo install -m 755 /tmp/claude-dash /usr/local/bin/claude-dash && rm /tmp/claude-dash && claude-dash --json > /dev/null && echo 'ClaudeWatch server script installed'
   ```
3. **Authorize this phone**: tap **Generate key** (only the first time; one key covers all your servers), then copy the command and paste it on the server. It adds the key to `~/.ssh/authorized_keys`, locked to `claude-dash --json`. Pasting it again is safe.
4. **Connect**.

Repeat for each server with **+ Add server**.

### 3. Add widgets

Long-press the home screen, open **Widgets** and add **ClaudeWatch**. If you have more than one server, it asks which one the widget should show. Add one widget per server. To change a widget's server later, long-press it and choose **Reconfigure** (the wording depends on your launcher).

### Plan usage

Plan usage needs Claude Code to be logged in on the server as the same user the app connects as. If the app shows a usage error:

1. SSH in as that user, run `claude`, and sign in with `/login`. If the login has expired, starting `claude` once refreshes it.
2. Check it with:
   ```sh
   claude-dash --json | python3 -c "import json,sys; print(json.load(sys.stdin)['usage'])"
   ```

## Permissions and what it accesses

**On your phone**, the app asks for:

| Permission | Why |
| --- | --- |
| `INTERNET` | SSH to your servers |
| `ACCESS_NETWORK_STATE` | Only run background refreshes when you're online |

It doesn't ask for location, contacts, storage, notifications or anything else, and it has no analytics, ads or crash reporting.

**On your server**, `claude-dash`:

- Reads `/proc`, `/sys`, `systemctl` and `journalctl` for system stats.
- Reads Claude Code's login token from `~/.claude/.credentials.json` and sends it only to Anthropic's usage endpoint, the same one Claude Code's `/usage` command uses. The token never goes to your phone.
- Opens a TCP connection to `api.anthropic.com:443` to measure latency.
- Writes only a 60-second usage cache at `~/.cache/claude-dash-usage.json`.
- Needs `sudo` once, to install itself to `/usr/local/bin`. It runs as your normal user after that.

**The SSH key**:

- It's RSA-3072, generated on the phone and encrypted with an AES-GCM key held in the Android Keystore. The private key never leaves the phone.
- It's named after your phone and the date, like `ClaudeWatch_Galaxy-Z-Fold5_2026-09-26`, so it's easy to find in `~/.ssh/authorized_keys`.
- The server only lets it run `claude-dash --json` (`command=` plus `restrict`), with no shell, port forwarding, agent forwarding or PTY.
- The server's host key is trusted on first use and pinned after that. If it changes, the app refuses to connect until you confirm.
- To revoke it, delete the line ending in `ClaudeWatch_...` from `~/.ssh/authorized_keys`.

See [SECURITY.md](SECURITY.md) to report a vulnerability, and [PRIVACY.md](PRIVACY.md) for the privacy policy.

## Build from source

You need JDK 17 and Gradle 8.11+.

```sh
gradle assembleRelease
# app/build/outputs/apk/release/app-release.apk (signed with the debug key unless KEYSTORE_FILE is set)
```

Every push is built by [GitHub Actions](.github/workflows/build.yml), which uploads the APK as a build artifact.

## Project layout

```
app/src/main/java/dev/vpsdash/
  MainActivity.kt      setup, dashboard and widget server picker (Compose)
  widget/StatsWidget.kt  home-screen widget (Glance)
  SshClient.kt         SSH fetch and host-key pinning (JSch)
  KeyManager.kt        on-device key generation, Keystore encryption
  Refresh.kt           WorkManager background refresh
  Stats.kt             JSON model and formatting
  Prefs.kt             servers, cached results, widget-to-server mapping
server/claude-dash.py  stats collector and terminal dashboard
```

## Contributing

Issues and pull requests are welcome. If you change the JSON produced by `claude-dash`, update `Stats.kt` in the same PR.

## License

[MIT](LICENSE)

---

ClaudeWatch is an independent project. It is not affiliated with, endorsed by or sponsored by Anthropic. Claude and Claude Code are trademarks of Anthropic, PBC.
