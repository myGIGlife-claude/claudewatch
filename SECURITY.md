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
- **Server script**: `claude-dash` only reads from `/proc`, `/sys` and `systemctl`/`journalctl`, and opens one TCP connection to `api.anthropic.com:443` to measure latency. It writes nothing.

## Revoking access

Delete the line ending in `claudewatch@android` (or `vps-dash@android` for v1.0 keys) from `~/.ssh/authorized_keys` on the server.
