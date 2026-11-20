# ClaudeWatch

Android app + home-screen widget (Kotlin, Compose, Glance) that reads VPS stats over SSH from `server/claude-dash.py` (Python 3 stdlib). Public repo: keep org names, emails and addresses out of it.

## Project notes
- Tasks live in `PLAN.md`.
- Build: no Gradle on this server. CI (`.github/workflows/build.yml`, JDK 17, Gradle 8.11.1) runs `gradle assembleRelease` on every push and uploads the APK artifact. Release APKs are signed with the fixed key from secrets `KEYSTORE_BASE64`/`KEYSTORE_PASSWORD` (alias `claudewatch`); never change the key or updates stop installing in place.
- Server test: `python3 server/test_claude_dash.py` (prints `ok`).
- Package is `dev.vpsdash` (source), applicationId `life.mygig.cdash`. Bump `versionCode`/`versionName` in `app/build.gradle.kts` for each release.
- JSON contract: if `claude-dash --json` output changes, update `Stats.kt` in the same commit.
- Security model (don't weaken): SSH key generated on device, AES-GCM wrapped with an Android Keystore key; server key line is `command="/usr/local/bin/claude-dash --json",restrict`; host key is TOFU-pinned per server (`fp_<id>` in prefs, cleared when host/port changes).
- Prefs (`SharedPreferences "vpsdash"`): `servers` JSON list, per-server `fp_/json_/time_/error_<id>`, `widget_<appWidgetId>` → server id. v1.0-1.5 flat keys are migrated in `Prefs.migrate`.
- Background refresh: WorkManager periodic 15 min (`vps-refresh`) + one-shot `vps-refresh-now`; the app polls every 5 s while open.
