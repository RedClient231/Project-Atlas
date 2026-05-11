# Project Atlas

A lightweight virtual space engine for Android 13+ — run cloned apps and games in an isolated environment without root.

## Features

- **Virtual App Engine** — Run apps in an isolated sandbox using method hooking (Pine framework)
- **32-bit & 64-bit Support** — Dual ABI for full game compatibility (armeabi-v7a + arm64-v8a)
- **GameGuardian Compatible** — Exposes virtual process memory maps and PIDs for GG integration
- **APK & XAPK Support** — Install from file or clone from device, including split APKs and OBB
- **Built-in Logcat** — Real-time log viewer with filtering by app, level, and search
- **Shizuku Integration** — Elevated operations without root via Shizuku
- **Lightweight** — Aggressive R8 optimization, ABI splits, minimal resource footprint
- **Material 3 UI** — Modern Jetpack Compose interface with dynamic theming

## Architecture

```
┌─────────────────────────────────────────────┐
│                  UI Layer                    │
│  (Jetpack Compose + Material 3 + Navigation)│
├─────────────────────────────────────────────┤
│              Feature Layer                   │
│  Home │ Install │ Logcat │ Settings          │
├─────────────────────────────────────────────┤
│              Core Engine                     │
│  VirtualEngine │ ActivityManager │ Service   │
├─────────────────────────────────────────────┤
│         Hook & IPC Layer                     │
│  Pine Hooks │ SystemServiceHooks │ GG Compat │
│  Binder IPC │ ProcessConnector │ Native      │
├─────────────────────────────────────────────┤
│          Virtual Filesystem                  │
│  VirtualFS │ MountManager │ PackageManager   │
├─────────────────────────────────────────────┤
│              Data Layer                      │
│  Room Database │ DataStore │ Repository      │
└─────────────────────────────────────────────┘
```

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Kotlin 2.1 + C++17 |
| UI | Jetpack Compose + Material 3 |
| DI | Hilt |
| Database | Room |
| Hooking | Pine (top.canyie) |
| Elevated Ops | Shizuku |
| Navigation | Navigation Compose |
| Image Loading | Coil 3 |
| Async | Kotlin Coroutines + Flow |
| Build | Gradle 8.11 + AGP 8.7 |

## Requirements

- Android 13+ (API 33)
- ARM device (armeabi-v7a or arm64-v8a)
- No root required

## Building

```bash
./gradlew assembleRelease
```

Split APKs will be generated in `app/build/outputs/apk/release/`.

## License

MIT
