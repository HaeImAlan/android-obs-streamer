# CLAUDE.md

Notes for Claude (and future-me) when working on this repo.

## What this is
Two-part project:
1. Android Kotlin app under `app/` — captures camera frames and serves them
   as MJPEG over HTTP (port 4747) and as a raw length-prefixed TCP stream
   (port 4748).
2. Native OBS Studio plugin under `obs-plugin/native/` — written in C, decodes
   either transport and pushes frames into OBS as an async video source.

There is also a legacy Python OBS script at `obs-plugin/android-usbcam.py`. It
just creates a Media Source pointing at the HTTP URL. The native C plugin is
the supported path.

## Hard constraints — read these before changing things

- **Target devices are old phones.** `compileSdk` / `targetSdk` are pinned to
  29 (Android 10). Do not bump them. Google Play complains via lint, so
  `ExpiredTargetSdkVersion` is in the lint disable list — leave it there.
- **No Google Play.** Don't add anything that requires Play Services.
- **Latency matters.** The whole point of the raw TCP path is sub-50 ms
  latency. Don't add buffering, don't add reencoding, don't switch to a
  framework that would.
- **Do not require ADB.** Wi-Fi mode must keep working. ADB is one option,
  not the only one.
- **The camera preview must never share the screen with controls.** UI lives
  in the slide-out drawer (right side, 260 dp) and the floating "..." button.
  If you find yourself moving controls onto the preview surface, you're
  doing the wrong thing.

## Repo layout (annotated)

```
app/                                       Android app
├── build.gradle.kts                       compileSdk/targetSdk = 29; lint disables ExpiredTargetSdkVersion
└── src/main/
    ├── AndroidManifest.xml                CAMERA, INTERNET, ACCESS_WIFI_STATE, WAKE_LOCK; HOME category; fullSensor
    ├── java/com/example/usbcam/
    │   ├── MainActivity.kt                UI, drawer, wake lock, OrientationEventListener
    │   ├── CameraStreamer.kt              Camera2; applySettings() for live updates; computeJpegOrientation()
    │   ├── MjpegServer.kt                 HTTP server; multi-client; TCP_NODELAY
    │   ├── StreamSession.kt               HTTP multipart writer (per-client)
    │   └── RawStreamServer.kt             Raw TCP server; "ACAM" magic + u32 BE length-prefixed JPEG
    └── res/
        ├── layout/activity_main.xml       FrameLayout; TextureView fills screen; drawer is a ScrollView
        ├── layout/spinner_item.xml        White text — needed because dark drawer + default = unreadable
        └── drawable/focus_ring.xml        Tap-to-focus indicator

obs-plugin/
├── android-usbcam.py                      LEGACY — Python OBS script that wraps a Media Source. Not the supported path.
└── native/                                The real plugin
    ├── CMakeLists.txt                     Linux build; Windows uses obs-plugintemplate
    ├── src/plugin.c                       Single source file; ALL plugin logic
    ├── src/stb_image.h                    Bundled JPEG decoder (STBI_ONLY_JPEG, STBI_NO_STDIO)
    └── installer/installer.nsi            NSIS installer; auto-detects OBS via registry

.github/workflows/
├── build.yml                              Android APK; uploads to dev-latest pre-release
└── build-obs-plugin.yml                   Linux .so + Windows .dll + NSIS installer
```

## Architecture invariants

### Android side
- `CameraStreamer` is constructed with **both** servers:
  `CameraStreamer(context, server: MjpegServer, rawServer: RawStreamServer? = null)`.
  Every captured JPEG goes to both, regardless of which one OBS is using.
- Live setting changes (resolution excepted) go through `applySettings()` —
  do not tear down and rebuild the capture session for things like AF / EV /
  torch / FPS.
- JPEG orientation is set on the capture request via
  `CaptureRequest.JPEG_ORIENTATION`, computed from sensor orientation +
  device rotation. `MainActivity` keeps `cameraStreamer.deviceRotation` in
  sync via an `OrientationEventListener`.
- Wake lock is `PARTIAL_WAKE_LOCK` named `usbcam:streaming`. Acquire on
  start, release on stop.

### Raw TCP protocol (`RawStreamServer.kt` ↔ `plugin.c::run_raw_session`)
```
"ACAM"                       (4 bytes, sent once when client connects)
repeating:
  uint32 BE size              (frame length in bytes)
  size bytes of JPEG          (FFD8...FFD9)
```
Single client at a time. New connection bumps the previous one.
TCP_NODELAY on both sides. Sanity-cap on size is 10 MiB.

### HTTP MJPEG (`MjpegServer.kt` ↔ `plugin.c::run_http_session`)
Standard `multipart/x-mixed-replace`. The plugin **does not** parse the
multipart boundary — it scans for JPEG SOI (`FF D8`) / EOI (`FF D9`) in the
byte stream. This is intentional and robust; do not "improve" it by adding
boundary parsing.

### OBS plugin modes
The `mode` source property has three string values:
- `"usb"` — uses bundled adb to `adb forward tcp:PORT tcp:PORT`, then talks
  raw TCP to `127.0.0.1`. Default. Host field is hidden in this mode.
- `"raw"` — raw TCP, user provides host (phone IP).
- `"http"` — HTTP MJPEG, user provides host.

Switching mode flips the default port between 4748 (raw/usb) and 4747 (http)
**only** if the current port matches the other mode's default.

### ADB lifecycle (USB mode)
- `obs_module_load`: `adb_locate()` resolves `data/obs-plugins/obs-android-usbcam/adb/adb[.exe]`
  via `obs_module_file()`. Falls back to `"adb"` on PATH if missing.
- Source create / each reconnect attempt: `adb forward tcp:PORT tcp:PORT`.
- Source destroy / update: `adb forward --remove tcp:PORT`.
- `obs_module_unload`: `adb kill-server`. **This is required** — without it
  adb.exe lingers after OBS exit.
- Process spawn is silent: `CREATE_NO_WINDOW` on Windows, fork+execl with
  stdout/stderr to `/dev/null` on POSIX. Do not use `system()`.

## Build commands

```bash
# Android
./gradlew assembleDebug

# OBS plugin (Linux)
cd obs-plugin/native && cmake -B build -DLIBOBS_INCLUDE_DIR=/usr/include/obs && cmake --build build

# OBS plugin (Windows) — only via CI; uses obs-plugintemplate
```

## CI gotchas

- **Linux OBS plugin**: do **not** add `ppa:obsproject/obs-studio`. The PPA's
  `obs-studio` package conflicts with Ubuntu's pre-installed `libobs0`. Just
  use `libobs-dev` from Ubuntu universe — we only need headers.
- **Windows OBS plugin**: builds via `obs-plugintemplate` clone. Our source
  files (`plugin.c`, `stb_image.h`) get copied into `tpl/src/` as
  `plugin-main.c` + `stb_image.h` so they match the template's expected
  layout. Don't try to build the Windows plugin standalone — fetching
  obs-deps + qt6 + libobs by hand is a rabbit hole.
- **Gradle wrapper**: `gradlew` has `DEFAULT_JVM_OPTS='-Xmx64m -Xms64m'` —
  the inner quotes were broken in earlier checkins, do not re-add them.
- **APK / installer release**: every push to a non-main branch updates a
  `dev-latest` pre-release with the new artifacts via
  `softprops/action-gh-release@v2`.

## Things that have already gone wrong, do not do them again

- **Don't** use `system()` to call adb — it pops a console window on
  Windows. Use `CreateProcessA` + `CREATE_NO_WINDOW`.
- **Don't** add `MUI_PAGE_LICENSE_NOWARN` to the NSIS installer — it
  doesn't exist. The real macro is `MUI_PAGE_LICENSE` and it needs a
  license file. We just skip it.
- **Don't** try to download `OBS-Studio-VERSION-Windows.zip` from the
  obs-studio releases. That filename isn't published. Use
  `obs-plugintemplate` instead.
- **Don't** put controls on top of the camera preview. Drawer or nothing.
- **Don't** rebuild the camera capture session for AE/AF/torch/EV/FPS
  changes — `applySettings()` updates the existing repeating request.

## Working branch
All development goes on `claude/android-mjpeg-streamer-AIvQ8`. Push there.
Do not push to `main` without explicit permission.
