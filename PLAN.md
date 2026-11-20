Last updated: 2026-11-20 23:05 UTC

## Goal
ClaudeWatch: Android app + Glance widget that shows a Claude Code VPS's health and plan usage over a locked-down SSH key. Sideloaded from GitHub Releases (v1.7, versionCode 8). Keep it small, safe and current.

## Current task
Branch `upgrade-2026-11`: tasks 1-3 done. Opus subagent doing task 4+5 (toolchain, R8); Sonnet subagent auditing all source for bugs. Next: apply audit fixes + task 6 tests (Sonnet), push branch, CI green, merge, release v1.8.
Open question: "move the github owner to the claude user" - repo is already in org myGIGlife-claude and this session has admin; waiting for the owner to say what to transfer.

## Tasks
Model per task follows the 2026-11-16 model rules (cheapest tier that does it reliably).

- [x] 1. Repo hygiene: `git rm --cached server/__pycache__`, add `__pycache__/` to `.gitignore`; add a `python3 server/test_claude_dash.py` step to `build.yml`. **Haiku/this session** (mechanical). Done when: CI green and the test step shows "ok".
- [x] 2. Clean up widget mappings: implement `onDeleted` in `StatsWidgetReceiver` to remove `widget_<id>`; on `deleteServer`, re-point or drop widgets that used it. **Sonnet** (small, touches Prefs + receiver). Done when: deleting a widget/server leaves no stale `widget_*` key (check via unit test or manual prefs dump).
- [x] 3. Don't swallow coroutine cancellation: `Refresh.fetchOne` catches `Throwable`; rethrow `CancellationException`. **Haiku** (one line). Done when: CI build green.
- [ ] 4. Toolchain upgrade to targetSdk 36 / compileSdk 37: AGP 8.7.3 → 9.x (built-in Kotlin, drop `kotlin.android` plugin and `kotlinOptions`), Kotlin 2.1 → 2.4.x, Gradle 8.11 → 9.x in CI, Compose BOM 2026.09.00, WorkManager/Glance/DataStore/Lifecycle/Activity/JSch to current stable; add a Gradle wrapper. Check predictive back, edge-to-edge and large-screen behavior at 36. **Opus** (system-wide build change, many breaking-change risks; route to a cluster worker if one is attached). Done when: CI builds a signed APK, it installs over v1.7 on a phone (same key), widget and dashboard work.
- [ ] 5. Release hardening: enable R8 (`isMinifyEnabled`, `isShrinkResources`) with keep rules for JSch; add a network security config (cleartext off). **Sonnet** (needs testing that SSH still works after shrinking). Depends on 4. Done when: release APK connects and refreshes on a real server.
- [ ] 6. Android unit tests: JVM tests for `Stats.kt` JSON parsing (against a fixture captured from `claude-dash --json`) and `Prefs` migration; run `testDebugUnitTest` in CI. **Sonnet**. Done when: CI runs and passes the tests.
- [ ] 7. Release v1.8 (bump versionCode 9 / versionName 1.8, GitHub release with APK). **This session**. Depends on 1-6. Done when: release page has the APK and it updates in place.
- [ ] 8. Developer verification for sideloaded APKs (global rollout 2027+): register package `life.mygig.cdash` + signing key. **Owner** (account action). QUESTION for later, not blocking.

## Decisions
- 2026-09-26: v1.0-1.7 shipped (multi-server, plan usage, widget per server, fixed release key via `KEYSTORE_BASE64` secret).
- 2026-11-20: New analysis under the 2026-11-16 model rules; created PLAN.md and CLAUDE.md. Not on Play, so the Play target-API deadline doesn't block releases, but targetSdk 35 is a year behind (task 4).

## Known issues
- `server/__pycache__/*.pyc` is committed (task 1).
- No Android tests; CI doesn't run the Python test (tasks 1, 6).
- Stale `widget_<id>` prefs after a widget or server is removed (task 2).
- No Gradle on this server; builds happen in GitHub Actions only.
- Plan usage depends on an undocumented Anthropic endpoint (`/api/oauth/usage`); may break without notice.
