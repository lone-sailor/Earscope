# Ear Scope

A lightweight, standalone viewer for iTiMO / Mo-Link compatible Wi-Fi endoscope and ear-cleaner cameras without requiring proprietary telemetry or bloated third-party apps.

Features native clients for both **Windows (Desktop)** and **Android**.

---

## Downloads

Download the latest pre-compiled binaries from the **[Releases](../../releases)** tab:
* **Windows:** `EarScope-Windows.exe` (Single standalone executable, no Python required)
* **Android:** `EarScope-Android.apk` (Installs directly on Android 7.0+)

---

## Features

- **Zero Cloud Dependence:** Works entirely on the camera's offline local UDP network.
- **Live Battery Monitoring:** Tracks hardware voltage grades and warns when the battery is critical.
- **Orientation Control:** Live 90° clockwise rotation cycling.
- **High-Resolution Snapshots:** Save JPEG stills instantly.
- **Cross-Platform:** Native Kotlin + Jetpack Compose on Android; lightweight Tkinter + Pillow on Windows.

---

## Connection Guide

1. Power on your ear-scope camera.
2. Connect your computer or phone to the camera's Wi-Fi network (typically named `iTiMO-xxxx`, or similar).
3. **On Android:** If a notification appears stating *"Wi-Fi has no internet access"*, tap it and confirm **Stay Connected**.
4. Open **Ear Scope**, verify the camera IP (default: `192.168.10.123`), and click **Connect**.

---

## Protocol Overview

The application communicates over raw UDP:
- **Video Feed (Port 8031):** Client sends a 24-byte trigger packet to start JPEG frame streaming. Frames arrive chunked with a 24-byte header containing index, expected size, and hardware status.
- **Keepalive / Heartbeat (Port 50000):** Continuous `SETCMD` packet sequence sent every 80ms to maintain steady link recovery.

---

## Building from Source

### Windows (`.exe`)
```cmd
cd windows
pip install -r requirements.txt
build_exe.bat
