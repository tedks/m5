# QuotaWatch

A wearable quota monitor built on the M5StickC Plus. Shows your Claude Code, Codex, and GitHub Actions usage limits on a tiny screen you can glance at, pushed from your Android phone over BLE.

## How it works

```
┌──────────┐    BLE     ┌──────────┐    HTTPS    ┌─────────────────┐
│ M5StickC │ ◄───────── │ Android  │ ──────────► │ claude.ai       │
│  Plus    │            │  Phone   │             │ chatgpt.com     │
│ (display)│            │  (app)   │             │ api.github.com  │
└──────────┘            └──────────┘             └─────────────────┘
```

The phone app scrapes usage data from Claude and Codex web dashboards (via in-app WebView login), fetches GitHub Actions minutes via API, and pushes everything to the M5 over Bluetooth Low Energy.

## What you need

- **M5StickC Plus** (ESP32-PICO-D4) — ~$15
- **Android phone** (8.0+) with Bluetooth
- **GitHub classic token** with `user` scope

## Quick start

### 1. Flash the M5 firmware

Download the latest `firmware.bin` from [Releases](https://github.com/tedks/m5/releases), or build from source:

```bash
pip install platformio
pio run -d firmware -t upload
```

The M5 needs to be connected via USB. It shows up as `/dev/ttyUSB0` (Linux) or a COM port (Windows/Mac). If you get permission errors:

```bash
sudo usermod -aG dialout $USER   # then log out and back in
```

### 2. Install the Android app

Download `app-debug.apk` from [Releases](https://github.com/tedks/m5/releases) and sideload it:

```bash
adb install app-debug.apk
```

Or transfer the APK to your phone and tap to install (enable "Install from unknown sources" for your file manager).

### 3. Set up accounts

Open QuotaWatch on your phone:

1. Tap **Settings**
2. **Claude Code** → tap **Log in** → sign in to claude.ai in the WebView → tap **Done**
3. **Codex** → tap **Log in** → sign in to chatgpt.com in the WebView → tap **Done**
4. **GitHub** → paste a [classic token](https://github.com/settings/tokens) with `user` scope

### 4. Connect the M5

1. Power on the M5 (short press the left side button)
2. In the app, tap **Connect** — it scans for "QuotaWatch" over BLE
3. Tap **Refresh** — data appears on both the phone and M5

Auto-refresh runs every 5 minutes. Toggle it with the **Auto** chip.

## What it shows

| Source | Data | How |
|--------|------|-----|
| **Claude Code** | 5-hour usage % | WebView scrapes claude.ai/settings/usage |
| **Codex** | 5-hour remaining % → converted to used | WebView scrapes chatgpt.com/codex analytics |
| **GitHub Actions** | Minutes used / 3000 included | REST API with classic token |

## M5 controls

| Button | Action |
|--------|--------|
| **A** (front) short press | Toggle screen on/off |
| **A** long press (>600ms) | Force redraw |
| **B** (right side) | Cycle brightness |
| **Power** (left side) | Short = on, 6s hold = off |

## Building from source

### Prerequisites

If you use Nix:

```bash
nix develop   # provides JDK, Gradle, Android SDK, PlatformIO, esptool
```

Otherwise:
- JDK 17
- Android SDK (API 35, build-tools 34+35)
- PlatformIO (for firmware)

### Firmware

```bash
pio run -d firmware              # build only
pio run -d firmware -t upload    # build + flash
```

### Android app

```bash
cd companion
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

### Wear OS companion (experimental)

```bash
cd companion
./gradlew :wear:assembleDebug
# APK at wear/build/outputs/apk/debug/wear-debug.apk
```

## Architecture

```
firmware/              ESP32 Arduino firmware (PlatformIO)
  src/main.cpp         BLE GATT server + TFT display

companion/             Android project (Gradle/Kotlin)
  app/                 Phone app
    api/               Data models, GitHub API client, key persistence
    ble/               BLE GATT client with auto-reconnect
    scraper/           WebView-based usage scrapers for Claude + Codex
    ui/                Jetpack Compose UI
  wear/                Wear OS companion (syncs via Data Layer)
```

## Known limitations

- Claude and Codex scraping depends on page DOM structure — may break if the sites redesign
- WebView sessions expire; the app detects this and prompts re-login
- The M5 and phone must be within BLE range (~10m)
- GitHub fine-grained PATs don't work for billing endpoints — must use classic tokens
- Codex shows "remaining %" which is inverted to "used %" — may not match the dashboard exactly

## License

MIT
