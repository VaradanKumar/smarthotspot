# SmartHotspot - "Instant Hotspot" for Android & Windows

SmartHotspot is a utility that allows your Windows laptop to trigger your phone's mobile hotspot using Bluetooth Low Energy (BLE), without requiring root, Shizuku, or specialized system modifications.

> [!CAUTION]
> **Samsung Device Exclusive**: This project currently requires a **Samsung Galaxy** smartphone with **Modes and Routines** enabled. It uses the system's routine engine as a secure bridge to toggle the hotspot.

## 🚀 Key Features

- **BLE Discovery and Control**: Uses a BLE GATT service for device discovery and command delivery.
- **WhatsApp-style Automation**: Background service starts automatically on boot and recovers if Bluetooth is toggled.
- **Samsung Optimization**: Specifically designed for Samsung Galaxy S-series (Android 16 / One UI 8) with deep system stability fixes.
- **Invisible Windows Agent**: Lives in the Windows System Tray for one-click access. No messy console windows.
- **Zero Configuration**: Remembers your phone and last successful port for an "Instant" feel.

## 🛠 How it Works

1.  **The Android App**: Runs a background service that advertises a BLE GATT command service.
2.  **The Windows Agent**: Scans for that service, connects with BLE, and writes a `HOTSPOT_ON` or `HOTSPOT_OFF` command.
3.  **The Magic Bridge**: The Android app posts a notification with a specific keyword. **Samsung Modes & Routines** detects this keyword and toggles the system hotspot.

## 📦 Setup Instructions

### 1. Android Phone (Samsung Only)
- Install the app and grant all permissions (**Notification**, **Bluetooth**, **Nearby Devices**).
- **IMPORTANT**: Set Battery usage to **"Unrestricted"** in App Info to prevent the OS from killing the background service.

#### 🔧 Create Samsung Routines (2 Required)

You must create exactly two routines in **Settings > Modes and Routines > Routines**:

**Routine 1: Hotspot ON**
- **IF**: `Notification received`
    - **App**: `SmartHotspot`
    - **Keyword**: `HOTSPOT_ON`
- **THEN**: `Mobile Hotspot` -> `On`

**Routine 2: Hotspot OFF**
- **IF**: `Notification received`
    - **App**: `SmartHotspot`
    - **Keyword**: `HOTSPOT_OFF`
- **THEN**: `Mobile Hotspot` -> `Off`

---

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
