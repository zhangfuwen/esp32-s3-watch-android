# ESP32-S3 Watch Companion App

Android companion app for ESP32-S3 Smartwatch that provides:
- **Bluetooth LE connectivity** to the watch
- **Network proxy service** - routes HTTP requests from watch through Android device
- **Background service** for persistent connection

## Features

- 🔵 Bluetooth LE GATT client for ESP32-S3 watch
- 🌐 HTTP/HTTPS proxy service (configurable port)
- 📱 Foreground service for persistent connection
- 🔋 Battery-efficient background operation
- 📊 Connection status monitoring

## Build

### Requirements
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK 34 (API 34)

### Build Locally
```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### Build Release
```bash
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release-unsigned.apk
```

## GitHub Actions

APK is automatically built on every push to `main` branch.

**Download latest build:**
1. Go to [Actions](../../actions)
2. Select latest workflow run
3. Download APK from artifacts

## Usage

1. **Install APK** on your Android device
2. **Enable Bluetooth** and location permissions
3. **Open app** and scan for ESP32-S3 watch
4. **Connect** to your watch
5. **Start proxy service** (default port: 8080)
6. **Configure watch** to use Android device as HTTP proxy

## Architecture

```
┌─────────────────┐         ┌──────────────────┐         ┌─────────────┐
│  ESP32-S3 Watch │ ──────▶ │  Android App     │ ──────▶ │  Internet   │
│                 │  BLE    │  Proxy Service   │  HTTP   │             │
│  HTTP Request   │  GATT   │  (Port 8080)     │ /TLS   │  Servers    │
└─────────────────┘         └──────────────────┘         └─────────────┘
```

## Project Structure

```
app/
├── src/main/
│   ├── java/com/xjbcode/espwatch/
│   │   ├── MainActivity.kt          # UI for connection management
│   │   ├── BluetoothLeService.kt    # BLE GATT client
│   │   ├── ProxyService.kt          # HTTP proxy server
│   │   ├── data/                    # Data models
│   │   └── util/                    # Utilities
│   ├── res/                         # Resources
│   └── AndroidManifest.xml
├── build.gradle.kts
└── proguard-rules.pro
```

## License

MIT License - See LICENSE file
