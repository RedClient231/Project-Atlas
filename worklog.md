---
Task ID: 1
Agent: Main Agent
Task: Comprehensive deep analysis and fix of ALL Project Atlas issues

Work Log:
- Read ALL 55+ source files in the project to understand the complete architecture
- Identified 15+ root causes across the entire codebase
- Implemented 10+ critical fixes covering all reported issues

Stage Summary:
- **VirtualStubActivity**: Created proxy activity for launching virtual apps (Android requires declared activities for UI; virtual packages are NOT installed on device)
- **EngineConfig path conflict**: Fixed dual path issue where VirtualFileSystem used context.dataDir but EngineConfig used context.filesDir — app registry was stored at wrong path
- **virtualRoot lateinit crash**: Replaced `lateinit var` with `@Volatile nullable var` + safe getter that throws descriptive error
- **launchActivity detection**: Changed from system PackageManager (only works for installed apps) to direct APK manifest parsing via apk-parser library
- **Shizuku status**: Replaced one-shot flowOf() with 3-second polling flow; handle more exception types instead of defaulting to "unknown"
- **XAPK detection**: Preserved original file extension in temp file so detectXapk() can identify .xapk correctly
- **30+ missing permissions**: Added camera, microphone, location, contacts, calendar, SMS, phone, Bluetooth, sensors, nearby devices, alarms, install/delete packages, etc.
- **MANAGE_EXTERNAL_STORAGE**: Added settings-based permission request for Android 11+
- **Uninstall fixes**: Added snackbar feedback; made sub-operations fault-tolerant (one failure doesn't block others)
- **Storage info fix**: SettingsViewModel was using wrong path (filesDir instead of dataDir)
- Pushed all changes to GitHub and triggered rebuild
