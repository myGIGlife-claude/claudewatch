# VPS Dash

Android app + home-screen widget that shows CPU, memory, disk, API latency and Claude Code session stats for your VPS. It connects over SSH and runs `/usr/local/bin/claude-dash --json`.

## 1. Install claude-dash on the VPS

```sh
sudo install -m 755 server/claude-dash.py /usr/local/bin/claude-dash
claude-dash --json   # should print one line of JSON
```

Python 3 stdlib only. Run `claude-dash` with no arguments for a live terminal dashboard.

## 2. Install the app

On your phone, open this repo's **Releases** page, download the latest `app-debug.apk` and install it. You'll need to allow installs from your browser.

## 3. Connect

1. Open VPS Dash and enter the **host**, **username** and **port**, then tap **Save**.
2. Tap **Generate key**. The private key stays on the phone, encrypted with the Android Keystore.
3. Tap **Copy command**, SSH into the VPS as that same user, and paste the command. The key only works for `claude-dash --json`. It can't open a shell or run anything else.
4. Tap **Connect**.
5. Long-press your home screen, open **Widgets**, and add **VPS Health**.

The widget refreshes every 15 minutes, and you can tap its ↻ icon to refresh it straight away. While the app is open it polls every 5 seconds.
