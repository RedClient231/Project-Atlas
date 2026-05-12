# Atlas Virtual Space — Architecture (v2, from scratch)

> This document describes the **new** `com.atlas.vspace` engine.
> The old `com.atlas.virtualspace.core.engine.*` code is deprecated and should
> not be used for new launches. It remains in the tree only for UI code that
> hasn't been migrated yet.

## Non-Goals

- Apps with Play Integrity / SafetyNet attestation — **will not work**
- Apps with aggressive anti-tamper checks (Denuvo, PubG anti-cheat) — not supported
- Apps requiring signature-verified IPC to other installed packages
- Apps that must register as accessibility services / input methods

## Goals

- Run ordinary Android games inside Atlas's process space
- Isolated data directories (the game cannot see Atlas's files or vice versa)
- GameGuardian can attach to the stub process and scan the game's memory
- No "install on real device" prompts — ever
- Multiple guest apps can run concurrently in separate stub processes

## Model

### Process topology

```
com.atlas.virtualspace          ← Atlas host (UI, settings, launcher)
com.atlas.virtualspace:p0       ← Stub process 0 — runs at most one guest
com.atlas.virtualspace:p1       ← Stub process 1
...
com.atlas.virtualspace:p9       ← Stub process 9
```

Each `:pN` is declared in the manifest via `android:process=":pN"`. Android
forks them on demand when a component with that process label is started.
They share Atlas's UID so they can read the virtual root directory.

### Launch flow (end-to-end)

1. **UI tap** → `AtlasVirtualLauncher.launch("com.some.game")`
2. **Host-side planning** in `VSpaceCore`:
   - Parse guest APK manifest → discover main activity + `launchMode`
   - Allocate stub process slot (`p0`-`p9`) if one is free, else evict LRU
   - Pick a stub Activity of matching `launchMode` in the chosen slot
3. **Dispatch** — `ActivityManagerProxy.startActivityInVirtualSpace()`:
   - Builds an Intent targeting `com.atlas.../StubActivity_P0_Std00`
   - Stuffs the original target component + guest package into extras
   - Calls `context.startActivity(intent)`
4. **Android** forks `:p0` if not running, loads Atlas's APK, calls
   `AtlasStubApp.onCreate()` — **NOT** the guest's Application yet
5. **Stub Application bootstraps** via `VSpaceCore.bootStub(this)`:
   - Parse slot ID from process name
   - Install `BinderHijacker` → replace `ServiceManager.sCache["activity"]`,
     `["package"]`, `["window"]`, `["user"]` with our proxy binders
   - Install `InstrumentationShim` → wraps `ActivityThread.mInstrumentation`
   - Install `HCallback` → added to `ActivityThread.mH.mCallback`
6. **ActivityThread** processes `LAUNCH_ACTIVITY` message:
   - `HCallback.handleMessage` fires first (because we added the callback)
   - Sees `msg.obj.intent.component = .StubActivity_P0_Std00`
   - Extracts original guest component from the intent extras
   - Rewrites `msg.obj.intent.component` → `com.some.game/.MainActivity`
   - Rewrites `msg.obj.activityInfo` → parsed guest ActivityInfo
   - Returns false (lets original handling proceed)
7. **InstrumentationShim.newActivity()** is called by Android's ActivityThread:
   - `className` = `com.some.game.MainActivity`
   - We load it via `GuestApkLoader.classLoaderFor(guestPackage)`
   - Return the real game Activity instance
8. **Android framework** does `activity.attach(...)` with a real Token, real
   Window, etc. The game's `onCreate` runs. GL surfaces render. Touch works.
9. **GameGuardian compatibility**:
   - The game runs inside `:p0`. Its .so regions are loaded into `:p0`'s
     address space via normal Android loading.
   - `/proc/{p0_pid}/maps` naturally shows the game's libraries.
   - GG scans `:p0` — sees everything.

## Why not "just use DexClassLoader"?

The initial broken implementation used `DexClassLoader.loadClass("X").newInstance()`
and reflection to call `onCreate`. This cannot work because:

- `Activity.attach()` requires a `Token` Binder from `system_server`
- Without `attach`, `Activity.mWindow` is null → NPE on `setContentView`
- `LocalContentResolver`, `getSystemService`, `getLayoutInflater` — all null
- `Activity.getApplication()` returns null → app crashes on first framework call

The "stub activity + intent swap" approach lets **the real Android framework**
create and attach the Activity correctly. We only redirect Android's classloader
to our guest's `.dex`, and our Instrumentation decides which class to instantiate.

## File system isolation

`Context.getFilesDir()` is overridden via a `GuestContextWrapper` that returns
paths under `{virtualRoot}/apps/{guestPackage}/{...}`. This covers:

- `getFilesDir()`, `getCacheDir()`, `getCodeCacheDir()`, `getNoBackupFilesDir()`
- `getDataDir()`, `getExternalFilesDir()`, `getObbDir()`
- `getDatabasePath()` → guest's virtual databases dir
- `getSharedPreferences()` → scoped to guest's virtual prefs dir

For low-level path access (`java.io.File("/data/data/com.some.game/..."`), we
would need native hooks via `libxhook` — out of scope for v2.0. Most games don't
hit this path because they use the framework APIs.

## What still must be done

- **Content providers** — stubs declared in manifest, proxy routes `resolve()`
- **Services** — same pattern as content providers
- **Broadcast receivers** — harder; intercept `IPackageManager.queryIntentReceivers`
- **Permissions** — proxy responds "GRANTED" for guest-requested runtime perms
- **Notifications** — let them pass through but rewrite the source package
- **WindowManager token** — may need special handling for SurfaceView-heavy games

These are additive. The architecture above gives a correct foundation for
each of them without rewrites.
