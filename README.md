# Ear Scope

A lightweight, standalone viewer for iTiMO / Mo-Link compatible Wi-Fi endoscope and ear-cleaner cameras without requiring proprietary telemetry or bloated third-party apps[cite: 2].

Features native clients for both **Windows (Desktop)** and **Android**[cite: 2].

---

## Downloads

Download the latest pre-compiled binaries from the **[Releases](../../releases)** tab[cite: 2]:
* **Windows:** `EarScope-Windows.exe` (Single standalone executable, no Python required)[cite: 2]
* **Android:** `EarScope-Android.apk` (Installs directly on Android 7.0+)[cite: 2]

---

## Features

- **Zero Cloud Dependence:** Works entirely on the camera's offline local UDP network[cite: 2].
- **Live Battery Monitoring:** Tracks hardware voltage grades and warns when the battery is critical[cite: 2].
- **Orientation Control:** Live 90° clockwise rotation cycling[cite: 2].
- **High-Resolution Snapshots:** Save JPEG stills instantly[cite: 2].
- **Cross-Platform:** Native Kotlin + Jetpack Compose on Android; lightweight Tkinter + Pillow on Windows[cite: 2].

---

## Connection Guide

1. Power on your ear-scope camera[cite: 2].
2. Connect your computer or phone to the camera's Wi-Fi network (typically named `iTiMO-xxxx`, or similar)[cite: 2].
3. **On Android:** If a notification appears stating *"Wi-Fi has no internet access"*, tap it and confirm **Stay Connected**[cite: 2].
4. Open **Ear Scope**, verify the camera IP (default: `192.168.10.123`), and click **Connect**[cite: 2].

---

## Protocol Overview

The application communicates over raw UDP:
- **Video Feed (Port 8031):** Client sends a 24-byte trigger packet to start JPEG frame streaming[cite: 2]. Frames arrive chunked with a 24-byte header containing index, expected size, and hardware status[cite: 2].
- **Keepalive / Heartbeat (Port 50000):** Continuous `SETCMD` packet sequence sent every 80ms to maintain steady link recovery[cite: 2].

---

## Building from Source

### Windows (`.exe`)
```cmd
cd windows
pip install -r requirements.txt
build_exe.bat