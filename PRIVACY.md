# Privacy Policy

_Last updated: September 26, 2026_

ClaudeWatch doesn't collect, store or share any personal data with the developer or any third party.

## What the app stores on your device

- The host, port, username and optional name of each server you add.
- An SSH key pair it generates. The private key is encrypted with the Android Keystore.
- Your server's SSH host key fingerprint.
- The most recent stats received from your server, so the widget can show them.

All of this stays on your device. Uninstalling the app deletes it.

## Network connections

The app connects only to the servers you add, over SSH. It doesn't use analytics, advertising, crash reporting or any other third-party service, and it has no backend of its own.

## Data from your server

The stats come from your server. They include CPU, memory and disk usage, hostname, process IDs and the working directories of Claude Code sessions. They travel over encrypted SSH to your device and aren't sent anywhere else.

To show your Claude plan usage, the server script sends the Claude Code login token already on your server to Anthropic's usage endpoint, as Claude Code itself does. The app never receives or stores that token.

## Children

The app isn't directed at children and doesn't knowingly collect data from anyone.

## Changes

Any changes to this policy will be published in this file.

## Contact

Open an issue at https://github.com/myGIGlife-claude/claudewatch/issues.
