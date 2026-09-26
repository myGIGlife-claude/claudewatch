# Security Policy

ClaudeWatch holds an SSH key to your server, so security reports are taken seriously.

## Reporting a vulnerability

Please **don't open a public issue**. Report it privately through [GitHub Security Advisories](https://github.com/myGIGlife-claude/claudewatch/security/advisories/new).

Include the steps to reproduce it, the affected version and the impact. You'll get a reply within 7 days.

## Supported versions

Only the latest release gets security fixes.

## Security model

- **Key generation**: an RSA-3072 key pair is created on the device. The private key is encrypted with AES-256-GCM using a key held in the Android Keystore (hardware-backed on most devices), and it is never exported or transmitted.
- **Server access**: the install command adds the public key with `command="/usr/local/bin/claude-dash --json",restrict`. OpenSSH then runs only that command, whatever the client asks for, and disables PTY, port, agent and X11 forwarding.
- **Host verification**: the server's host key fingerprint is pinned the first time the app connects. If it changes later, the app refuses to connect until you explicitly trust the new key.
- **Server script**: `claude-dash` only reads from `/proc`, `/sys` and `systemctl`/`journalctl`, and opens one TCP connection to `api.anthropic.com:443` to measure latency. It opens one more HTTPS request to `api.anthropic.com` for plan usage (see below), and its only write is a 60-second usage cache at `~/.cache/claude-dash-usage.json`.

## Claude plan usage

To show your 5-hour and weekly limits, `claude-dash` reads the OAuth token Claude Code stores in `~/.claude/.credentials.json` and sends it only to `https://api.anthropic.com/api/oauth/usage`, the same endpoint Claude Code's `/usage` command uses. The token never leaves the server: the phone receives only the percentages and reset times. That endpoint is undocumented and may change. If it does, the app shows an error instead of usage.

## Revoking access

Delete the line ending in `ClaudeWatch_<device>_<date>` from `~/.ssh/authorized_keys` on the server. Keys from v1.0-1.5 end in `vps-dash@android` or `claudewatch@android`.
