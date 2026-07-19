# Agent Instructions

## What this project is

**QuotaWatch** — a wearable AI-quota monitor. An M5StickC Plus (ESP32-PICO-D4)
displays your Claude Code, Codex, and GitHub Actions usage limits on a tiny
wrist screen. An Android companion app scrapes the usage numbers (Claude/Codex
via in-app WebView login, GitHub via REST API) and pushes them to the M5 over
Bluetooth Low Energy.

```
M5StickC Plus  ◄──BLE──  Android phone  ──HTTPS──►  claude.ai / chatgpt.com / api.github.com
 (display)               (scraper+app)
```

See `README.md` for the end-user setup guide (flashing, sideloading, account
setup, controls, known limitations).

## Where things live

```
firmware/                  ESP32 Arduino firmware (PlatformIO)
  platformio.ini           board = m5stick-c, huge_app partitions, /dev/ttyUSB0
  src/main.cpp             BLE GATT server + TFT display + button handling
  .pio/build/m5stickc-plus/  build output: bootloader.bin, partitions.bin, firmware.bin

companion/                 Android project (Gradle/Kotlin, Jetpack Compose)
  app/src/.../api/          Data models, GitHub API client, key/token persistence
                           - QuotaFetcher.kt orchestrates scrapers + GitHub API
  app/src/.../ble/         BLE GATT client with auto-reconnect (BleClient)
  app/src/.../scraper/     WebView usage scrapers
                           - UsageScraper.kt (generic; injectDelayMs, default 4s)
                           - ClaudeScraper.kt (claude.ai/settings/usage, 6s delay — React SPA)
                           - CodexScraper.kt (chatgpt.com/codex/cloud/settings/usage)
  app/src/.../ui/          Jetpack Compose UI (MainActivity, QuotaViewModel)
  wear/                    Wear OS companion (syncs via Data Layer — experimental)
  app/build/outputs/apk/debug/app-debug.apk   built phone APK

flake.nix                  Nix dev shell: JDK17, Gradle, Android SDK, PlatformIO, esptool, android-tools
.github/workflows/         CI: builds APK on push to main (updates `latest` prerelease) and on v* tags
```

Issue tracking (`ditz`) lives on the `ditz-metadata` git branch, not as files in
this tree — see "ditz (issue tracking)" below.

**BLE protocol:** newline-separated `Name:used:limit:unit`, e.g.
`Claude 5h:22:100:%\nCodex wk:5:100:%\nActions:465:3000:min`. Firmware shows
up to 6 quotas; a compact layout (font 1, 8px bars) kicks in above 3.

This is a **Nix project** — always run tooling through `nix develop --command <cmd>`.

## Hardware / machine map

The two devices are plugged into **different** machines. Know which one you need.

| Device | Plugged into | Why |
|--------|--------------|-----|
| M5StickC Plus (`/dev/ttyUSB0`) | **tower0** | Only enumerates reliably on tower0. framework0 fails with USB error -110 (see m5-7mt). |
| Android phone (adb, serial `59171FDCG000Y1`) | **framework0** | The dev machine — this repo's usual working directory. |

## Flashing the M5 firmware (on tower0)

The M5 is plugged into **tower0**, not the local dev machine. Build locally, copy
the three binaries to tower0, and flash them with esptool over SSH.

**CRITICAL: flash all three binaries at their correct offsets. NEVER flash
`firmware.bin` alone at `0x0` — that overwrites the bootloader and bricks boot.**

```bash
# 1. Build locally (produces the three .bin files under firmware/.pio/build/)
nix develop --command bash -c 'pio run -d firmware'

# 2. Copy the three binaries to tower0
scp firmware/.pio/build/m5stickc-plus/bootloader.bin \
    firmware/.pio/build/m5stickc-plus/partitions.bin \
    firmware/.pio/build/m5stickc-plus/firmware.bin \
    tower0.local:/tmp/

# 3. Flash them at the correct offsets
ssh tower0.local "nix run nixpkgs#esptool -- --port /dev/ttyUSB0 --baud 1500000 \
  write-flash 0x1000 /tmp/bootloader.bin 0x8000 /tmp/partitions.bin 0x10000 /tmp/firmware.bin"
```

Offsets: bootloader `0x1000`, partition table `0x8000`, app `0x10000`.

**Alternative (full build + flash on tower0).** tower0 has a synced checkout at
`~/Projects/m5` with the Nix flake. If it's up to date, `pio -t upload` flashes
all three binaries at the right offsets for you:

```bash
scp firmware/src/main.cpp tower0.local:~/Projects/m5/firmware/src/main.cpp
ssh tower0.local "cd ~/Projects/m5 && nix develop --command bash -c 'pio run -d firmware -t upload'"
```

Check the M5 is connected first: `ssh tower0.local "ls /dev/ttyUSB0"`.

## Reading adb / deploying the APK (on framework0)

The phone is plugged into **framework0** (the local dev machine). adb ships in
the Nix dev shell (`android-tools`), so run it through `nix develop`.

**Always target the phone by serial (`-s 59171FDCG000Y1`).** An emulator is
often also attached, and bare `adb install`/`adb logcat` fails with "more than
one device/emulator" when it is.

```bash
# See what's attached (phone should show as 59171FDCG000Y1; an emulator may too)
nix develop --command bash -c 'adb devices'

# Build the APK (the gradle wrapper lives in companion/, so cd in — there is no root gradlew)
nix develop --command bash -c 'cd companion && ./gradlew assembleDebug'
# APK: companion/app/build/outputs/apk/debug/app-debug.apk

# Install (use -r to keep app data; CI/local share a debug keystore so -r works)
nix develop --command bash -c 'adb -s 59171FDCG000Y1 install -r companion/app/build/outputs/apk/debug/app-debug.apk'

# Restart the app fresh
nix develop --command bash -c 'adb -s 59171FDCG000Y1 shell am force-stop com.quotawatch && \
  adb -s 59171FDCG000Y1 shell am start -n com.quotawatch/.ui.MainActivity'

# Tail scraper/BLE logs (clear, exercise the app, then dump)
nix develop --command bash -c 'adb -s 59171FDCG000Y1 logcat -c'   # then tap Refresh in the app
nix develop --command bash -c 'adb -s 59171FDCG000Y1 logcat -d' | grep -E \
  "ClaudeScraper|CodexScraper|UsageScraper|QuotaViewModel|QuotaFetcher|BleClient"

# Screenshot the phone
nix develop --command bash -c 'adb -s 59171FDCG000Y1 shell screencap -p /sdcard/screen.png && \
  adb -s 59171FDCG000Y1 pull /sdcard/screen.png /tmp/screen.png'
```

**adb troubleshooting.** If `adb devices` hangs or lists nothing, a stale adb
server socket is the usual cause. Remove it and let adb restart the server:

```bash
rm -f ~/.android/adb.5037 /tmp/adb.1000.log
nix develop --command bash -c 'adb kill-server; adb start-server; adb devices'
```

Do **not** run `adb` under `sudo` — a root-owned server fights the user one.

<!-- ditz:onboard -->
## Issue tracking with ditz

This project uses `ditz` (not beads). Issues are plain-text YAML on the
`ditz-metadata` git branch; the `ditz` CLI reads and writes them.

The loop:
- `ditz ready` — what to work on now (unblocked, ranked by how much each unblocks)
- `ditz start <id>` — mark in progress
- `ditz close <id> --reason "..."` — close with why (or `--wontfix` / `--reorg`)
- `ditz reopen <id>` — revive a closed issue

Create / inspect:
- `ditz add "title" -t bugfix|feature|task -c <component> --desc "..."`
- `ditz show <id>` · `ditz list --status unstarted|in_progress|paused|closed` · `ditz search <q>`
- `--json` on any command for machine output; `--ids-only` for just ids
- ids: copy them from output; a unique prefix works (like git hashes);
  `--id <name>` sets a deterministic id (re-creating with it is idempotent)

Structure (there are no priority / epic / parent fields — urgency is derived,
hierarchy is expressed in the graph):
- grouping: `-c <component>` + `ditz list --component <c>`
- sequencing: `ditz blocks <a> <b>` (a blocks b); an "epic" is just an issue
  blocked by its members — it stays out of `ready` until they close
- `ditz deps <id>` shows the dependency tree; `ditz deps --check` validates it

Sync: `ditz sync` fetches/merges/pushes the metadata branch (`git fetch`/`git
push` of `ditz-metadata` against `origin`, same as any other branch).

<!-- /ditz:onboard -->

Notes for agents (not part of the auto-generated block above, so
`ditz onboard` reruns won't touch these):
- `ditz add` with no `--id` mints a full-length hash id — always pass
  `--id <name>` for a short mnemonic id consistent with the existing
  `m5-xxx` issues (still referenced by id in old PR/commit messages and in
  this file, e.g. `m5-7mt` above).
- Issues live only on the `ditz-metadata` branch, not as files in this
  working tree — there's nothing to `git add` for issue changes.
- A plain `git clone` already fetches `ditz-metadata`, so `ditz list`/`ditz
  show` work immediately in a fresh clone (reading `origin/ditz-metadata`)
  even before the first `ditz sync`.
- Beads comments do not carry over via `ditz import`; see the migration PR
  for how existing beads comments were backfilled with `ditz comment`.

## Landing the Plane (Session Completion)

**When ending a work session**, you MUST complete ALL steps below. Work is NOT complete until `git push` succeeds.

**MANDATORY WORKFLOW:**

1. **File issues for remaining work** - Create issues for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **PUSH TO REMOTE** - This is MANDATORY:
   ```bash
   git pull --rebase
   ditz sync
   git push
   git status  # MUST show "up to date with origin"
   ```
5. **Clean up** - Clear stashes, prune remote branches
6. **Verify** - All changes committed AND pushed
7. **Hand off** - Provide context for next session

**CRITICAL RULES:**
- Work is NOT complete until `git push` succeeds
- NEVER stop before pushing - that leaves work stranded locally
- NEVER say "ready to push when you are" - YOU must push
- If push fails, resolve and retry until it succeeds
