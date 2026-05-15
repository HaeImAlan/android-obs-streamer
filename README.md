# android-obs-streamer

Turn your old Android phone into a low-latency USB webcam for OBS Studio.

Two parts:

1. **Android app** (`app/`) — Camera2-based capture that publishes JPEG frames
   over the network on two ports:
   - **4748** — raw TCP, length-prefixed frames (low latency)
   - **4747** — MJPEG over HTTP (works with OBS Media Source, browsers, VLC, etc.)
2. **Native OBS plugin** (`obs-plugin/native/`) — A C plugin that decodes the
   stream directly into OBS as an async video source. Ships with bundled `adb`
   binaries so USB mode "just works" without manual port-forwarding.

## Features

### Android app
- Camera2 capture with selectable resolution and 10/15/20/24/30/60 FPS
- JPEG quality slider (30–100) for bandwidth/quality tradeoff
- White balance presets (Auto, Daylight, Cloudy, Fluorescent, Tungsten, Shade)
- Front and back cameras, torch, ring-light overlay (front camera, with brightness slider)
- Tap-to-focus, autofocus on/off, AE lock, exposure compensation
- Auto-rotation that re-orients the JPEG stream to match the device
- Live status bar: FPS counter, raw TCP connection status, HTTP client count
- Wake lock so the phone never sleeps mid-stream
- Slide-out drawer UI so the camera preview fills the screen
- Can be launched as a HOME app
- Two transports running simultaneously: HTTP MJPEG (4747) and raw TCP (4748)
- Black-screen mode (privacy)
- ADB and Wi-Fi both supported

### OBS plugin
- Native C plugin (no Python script, no Media Source hacks)
- Three connection modes selectable per source:
  - **USB (auto-forward via ADB)** — bundled adb runs `adb forward` for you
  - **Wi-Fi — Raw TCP (low latency)**
  - **Wi-Fi — HTTP MJPEG (legacy)**
- Sub-50 ms latency in raw TCP mode (TCP_NODELAY, no MJPEG re-encoding overhead)
- Auto-reconnect
- Cleans up `adb` on OBS exit (`adb kill-server` in `obs_module_unload`)
- Source appears in OBS's **+** picker as "Android USB Cam" with the camera icon

## Quick start

### 1. Install the Android app
Grab `app-debug.apk` from the latest [`dev-latest`](../../releases/tag/dev-latest)
release and sideload it. Grant camera permission, tap **Start**.

### 2. Install the OBS plugin
Grab `obs-android-usbcam-installer.exe` from the same release and run it. It
auto-detects your OBS install directory.

### 3. Add the source in OBS
- **+** in the Sources dock → **Android USB Cam**
- Default mode is **USB**. Plug the phone in, enable USB debugging, tap **Start**
  in the app, click **OK** in OBS. You should see the feed within a second.

For Wi-Fi mode: switch the **Connection** dropdown to one of the Wi-Fi options
and type the phone's IP into the **Host** field. The Android app's status bar
shows both URLs.

## Build from source

### Android app
```bash
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

CI builds and uploads the APK to the `dev-latest` pre-release on every push to
`claude/**`.

### OBS plugin (Linux)
```bash
sudo apt install libobs-dev cmake build-essential
cd obs-plugin/native
cmake -B build -DLIBOBS_INCLUDE_DIR=/usr/include/obs
cmake --build build -j$(nproc)
sudo cp build/obs-android-usbcam.so /usr/lib/x86_64-linux-gnu/obs-plugins/
```

### OBS plugin (Windows)
The Windows build is heavyweight because it goes through the official
[`obs-plugintemplate`](https://github.com/obsproject/obs-plugintemplate) CI
flow to fetch prebuilt `obs-deps` and `qt6`. Easiest is to let GitHub Actions do
it — see `.github/workflows/build-obs-plugin.yml`.

## Protocol

### Raw TCP (port 4748)
```
"ACAM"                       (4 bytes magic, sent once on connect)
[uint32 BE size][JPEG data]  (repeating, big-endian frame size)
```
Single client at a time. The Android side closes the previous session when a
new client connects.

### HTTP MJPEG (port 4747)
Standard `multipart/x-mixed-replace; boundary=frame`. The plugin parses it by
scanning for JPEG SOI (`FF D8`) / EOI (`FF D9`) markers, so any boundary string
works.

## Repo layout
```
android-obs-streamer/
├── app/                              Android Kotlin app (Camera2)
│   └── src/main/java/com/example/usbcam/
│       ├── MainActivity.kt           UI, drawer, wake lock, orientation
│       ├── CameraStreamer.kt         Camera2 wrapper, JPEG rotation, AE/AF
│       ├── MjpegServer.kt            HTTP server on 4747
│       ├── StreamSession.kt          HTTP multipart writer
│       └── RawStreamServer.kt        Raw TCP server on 4748
├── obs-plugin/
│   ├── native/                       C plugin (the real one)
│   │   ├── src/plugin.c              Main plugin source
│   │   ├── src/stb_image.h           Bundled JPEG decoder
│   │   ├── installer/installer.nsi   NSIS installer (Windows)
│   │   └── CMakeLists.txt
│   └── android-usbcam.py             (legacy Python script, kept for reference)
└── .github/workflows/
    ├── build.yml                     Android APK build
    └── build-obs-plugin.yml          Linux .so + Windows .dll + installer
```

## License
MIT.
