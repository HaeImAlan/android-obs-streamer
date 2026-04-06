"""
Android USB Cam — OBS Studio Script

Adds a "Android USB Cam" source type that connects to the MJPEG
streamer app over WiFi or ADB (localhost).

Install:
  1. Copy this file to your OBS scripts folder
  2. OBS → Tools → Scripts → + → select this file
  3. Add source: Sources → + → Android USB Cam

Or just configure manually:
  Sources → + → Media Source → uncheck Local File →
  Input: http://<phone-ip>:4747/video
"""

import obspython as obs
import subprocess
import socket
import threading
import time

# ---------------------------------------------------------------------------
# Globals
# ---------------------------------------------------------------------------
PHONE_IP = "192.168.1.100"
PORT = 4747
USE_ADB = False
ADB_PATH = "adb"
AUTO_ADB_FORWARD = True
SOURCE_NAME = "Android USB Cam"
RECONNECT_SEC = 3

_status = "Disconnected"
_monitor_thread = None
_running = False


# ---------------------------------------------------------------------------
# OBS Script Interface
# ---------------------------------------------------------------------------

def script_description():
    return (
        "<h2>Android USB Cam</h2>"
        "<p>Connects to the Android MJPEG camera streamer app.</p>"
        "<p>Works over <b>WiFi</b> (enter phone IP) or <b>USB/ADB</b> "
        "(auto-forwards port).</p>"
    )


def script_properties():
    props = obs.obs_properties_create()

    obs.obs_properties_add_bool(props, "use_adb", "Use USB/ADB (localhost)")
    obs.obs_properties_add_text(props, "phone_ip", "Phone IP (WiFi mode)", obs.OBS_TEXT_DEFAULT)
    obs.obs_properties_add_int(props, "port", "Port", 1024, 65535, 1)
    obs.obs_properties_add_text(props, "adb_path", "ADB path", obs.OBS_TEXT_DEFAULT)
    obs.obs_properties_add_bool(props, "auto_adb_forward", "Auto ADB port forward")
    obs.obs_properties_add_text(props, "source_name", "Source name", obs.OBS_TEXT_DEFAULT)
    obs.obs_properties_add_button(props, "create_source_btn", "Create/Update Source", create_source_clicked)
    obs.obs_properties_add_button(props, "adb_forward_btn", "Run ADB Forward Now", adb_forward_clicked)

    return props


def script_defaults(settings):
    obs.obs_data_set_default_string(settings, "phone_ip", PHONE_IP)
    obs.obs_data_set_default_int(settings, "port", PORT)
    obs.obs_data_set_default_bool(settings, "use_adb", False)
    obs.obs_data_set_default_string(settings, "adb_path", "adb")
    obs.obs_data_set_default_bool(settings, "auto_adb_forward", True)
    obs.obs_data_set_default_string(settings, "source_name", SOURCE_NAME)


def script_update(settings):
    global PHONE_IP, PORT, USE_ADB, ADB_PATH, AUTO_ADB_FORWARD, SOURCE_NAME

    PHONE_IP = obs.obs_data_get_string(settings, "phone_ip")
    PORT = int(obs.obs_data_get_int(settings, "port"))
    USE_ADB = obs.obs_data_get_bool(settings, "use_adb")
    ADB_PATH = obs.obs_data_get_string(settings, "adb_path")
    AUTO_ADB_FORWARD = obs.obs_data_get_bool(settings, "auto_adb_forward")
    SOURCE_NAME = obs.obs_data_get_string(settings, "source_name")


def script_load(settings):
    global _running, _monitor_thread
    _running = True
    _monitor_thread = threading.Thread(target=_connection_monitor, daemon=True)
    _monitor_thread.start()


def script_unload():
    global _running
    _running = False


# ---------------------------------------------------------------------------
# Source creation
# ---------------------------------------------------------------------------

def _get_stream_url():
    host = "127.0.0.1" if USE_ADB else PHONE_IP
    return "http://{}:{}/video".format(host, PORT)


def _create_or_update_source():
    """Create or update a Media Source with the MJPEG stream URL."""
    url = _get_stream_url()

    # Try to find existing source
    source = obs.obs_get_source_by_name(SOURCE_NAME)
    if source is not None:
        # Update existing source settings
        settings = obs.obs_source_get_settings(source)
        obs.obs_data_set_bool(settings, "is_local_file", False)
        obs.obs_data_set_string(settings, "input", url)
        obs.obs_data_set_bool(settings, "hw_decode", False)
        obs.obs_data_set_bool(settings, "restart_on_activate", True)
        obs.obs_data_set_int(settings, "reconnect_delay_sec", RECONNECT_SEC)
        obs.obs_source_update(source, settings)
        obs.obs_data_release(settings)
        obs.obs_source_release(source)
        obs.script_log(obs.LOG_INFO, "Updated source '{}' → {}".format(SOURCE_NAME, url))
        return

    # Create new source
    settings = obs.obs_data_create()
    obs.obs_data_set_bool(settings, "is_local_file", False)
    obs.obs_data_set_string(settings, "input", url)
    obs.obs_data_set_bool(settings, "hw_decode", False)
    obs.obs_data_set_bool(settings, "restart_on_activate", True)
    obs.obs_data_set_int(settings, "reconnect_delay_sec", RECONNECT_SEC)

    source = obs.obs_source_create("ffmpeg_source", SOURCE_NAME, settings, None)
    obs.obs_data_release(settings)

    # Add to current scene
    current_scene_source = obs.obs_frontend_get_current_scene()
    if current_scene_source is not None:
        scene = obs.obs_scene_from_source(current_scene_source)
        if scene is not None:
            obs.obs_scene_add(scene, source)
        obs.obs_source_release(current_scene_source)

    obs.obs_source_release(source)
    obs.script_log(obs.LOG_INFO, "Created source '{}' → {}".format(SOURCE_NAME, url))


# ---------------------------------------------------------------------------
# ADB forwarding
# ---------------------------------------------------------------------------

def _run_adb_forward():
    """Run adb forward command. Returns True on success."""
    try:
        result = subprocess.run(
            [ADB_PATH, "forward", "tcp:{}".format(PORT), "tcp:{}".format(PORT)],
            capture_output=True, text=True, timeout=10
        )
        if result.returncode == 0:
            obs.script_log(obs.LOG_INFO, "ADB forward tcp:{0} → tcp:{0} OK".format(PORT))
            return True
        else:
            obs.script_log(obs.LOG_WARNING, "ADB forward failed: " + result.stderr.strip())
            return False
    except FileNotFoundError:
        obs.script_log(obs.LOG_WARNING, "ADB not found at: " + ADB_PATH)
        return False
    except Exception as e:
        obs.script_log(obs.LOG_WARNING, "ADB forward error: " + str(e))
        return False


# ---------------------------------------------------------------------------
# Button callbacks
# ---------------------------------------------------------------------------

def create_source_clicked(props, prop):
    if USE_ADB and AUTO_ADB_FORWARD:
        _run_adb_forward()
    _create_or_update_source()
    return True


def adb_forward_clicked(props, prop):
    _run_adb_forward()
    return True


# ---------------------------------------------------------------------------
# Connection monitor (background)
# ---------------------------------------------------------------------------

def _check_connection():
    """Quick TCP connect check to see if the stream server is up."""
    host = "127.0.0.1" if USE_ADB else PHONE_IP
    try:
        s = socket.create_connection((host, PORT), timeout=2)
        s.close()
        return True
    except (socket.timeout, socket.error, OSError):
        return False


def _connection_monitor():
    """Background thread that monitors connection and auto-forwards ADB."""
    global _status
    adb_forwarded = False

    while _running:
        if USE_ADB and AUTO_ADB_FORWARD and not adb_forwarded:
            if _run_adb_forward():
                adb_forwarded = True

        if _check_connection():
            _status = "Connected"
        else:
            _status = "Disconnected"
            if USE_ADB:
                adb_forwarded = False

        time.sleep(5)
