# SmartHotspot - "Instant Hotspot" for Android & Windows

SmartHotspot is a professional-grade utility that allows your Windows laptop to remotely trigger and connect to your phone's mobile hotspot seamlessly, similar to the Apple ecosystem experience. It uses a **Hybrid Bluetooth Protocol** for instant discovery and reliable control without requiring root, Shizuku, or any specialized system modifications.

## 🚀 Key Features

- **Hybrid Discovery**: Uses Bluetooth Low Energy (BLE) beacons for near-instant discovery (< 2s).
- **Secure Control**: Uses Bluetooth Classic (RFCOMM) with a custom handshake for reliable command delivery.
- **WhatsApp-style Automation**: Background service starts automatically on boot and recovers if Bluetooth is toggled.
- **Samsung Optimization**: Specifically designed for Samsung Galaxy S-series (Android 16 / One UI 8) with deep system stability fixes.
- **Invisible Windows Agent**: Lives in the Windows System Tray for one-click access. No messy console windows.
- **Zero Configuration**: Remembers your phone and last successful port for an "Instant" feel.

## 🛠 How it Works

1.  **The Android App**: Runs a 24/7 background service that broadcasts a tiny BLE beacon containing the app's current "Door Number" (Port).
2.  **The Windows Agent**: "Hears" the beacon, connects to the phone via Bluetooth Classic, and sends a secure `HOTSPOT_ON` command.
3.  **The Magic Bridge**: The Android app posts a notification with a specific keyword. **Samsung Modes & Routines** detects this keyword and toggles the system hotspot.

## 📦 Setup Instructions

### 1. Android Phone (S22+ / Android 16+)
- Install the app and grant all permissions (Notification, Bluetooth, Nearby Devices).
- **IMPORTANT**: Set Battery usage to **"Unrestricted"** in App Info to prevent the OS from killing the background service.
- Create a Samsung Routine:
    - **IF**: Notification received (App: SmartHotspot, Keyword: `HOTSPOT_ON`)
    - **THEN**: Mobile Hotspot -> ON

### 2. Windows Laptop
- Install Python 3.9+.
- Install required libraries:
  ```bash
  pip install PySide6 bleak
  ```
- Run `install_to_startup.bat` from the `windows_client` folder to enable auto-start.

## 📂 Project Structure

- `/app`: Android Studio project (Kotlin + Jetpack Compose).
- `/windows_client`: Python-based Windows Tray application and launcher.

---
*Created as a first professional engineering project integrating Android and Windows ecosystems.*
