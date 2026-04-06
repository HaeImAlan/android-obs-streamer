cmake_minimum_required(VERSION 3.16)
project(obs-android-usbcam VERSION 1.0.0)

# ── Find OBS ──────────────────────────────────────────────────────────
# Set OBS_SOURCE_DIR or LIBOBS_INCLUDE_DIR + LIBOBS_LIB to your OBS SDK.
# Example:
#   cmake -DOBS_SOURCE_DIR=/path/to/obs-studio ..
#
# Or if OBS is installed system-wide, find_package may work:
find_package(LibObs QUIET)

if(NOT LIBOBS_FOUND AND OBS_SOURCE_DIR)
    set(LIBOBS_INCLUDE_DIR "${OBS_SOURCE_DIR}/libobs")
    if(WIN32)
        set(LIBOBS_LIB "${OBS_SOURCE_DIR}/build/libobs/Release/obs.lib")
    else()
        set(LIBOBS_LIB "${OBS_SOURCE_DIR}/build/libobs/libobs.so")
    endif()
endif()

if(NOT LIBOBS_INCLUDE_DIR)
    # Try pkg-config as fallback (Linux)
    find_package(PkgConfig QUIET)
    if(PKG_CONFIG_FOUND)
        pkg_check_modules(LIBOBS QUIET libobs)
    endif()
endif()

if(NOT LIBOBS_INCLUDE_DIR AND NOT LIBOBS_INCLUDE_DIRS)
    message(FATAL_ERROR
        "Could not find OBS SDK. Set OBS_SOURCE_DIR or LIBOBS_INCLUDE_DIR.\n"
        "  cmake -DOBS_SOURCE_DIR=/path/to/obs-studio ..\n"
        "  cmake -DLIBOBS_INCLUDE_DIR=/usr/include/obs -DLIBOBS_LIB=/usr/lib/libobs.so .."
    )
endif()

# ── Plugin library ────────────────────────────────────────────────────
add_library(obs-android-usbcam MODULE
    src/plugin.c
)

target_include_directories(obs-android-usbcam PRIVATE
    ${LIBOBS_INCLUDE_DIR}
    ${LIBOBS_INCLUDE_DIRS}
    src
)

target_link_libraries(obs-android-usbcam PRIVATE
    ${LIBOBS_LIB}
    ${LIBOBS_LIBRARIES}
)

if(WIN32)
    target_link_libraries(obs-android-usbcam PRIVATE ws2_32)
endif()

# No lib prefix on the output
set_target_properties(obs-android-usbcam PROPERTIES
    PREFIX ""
    C_STANDARD 11
)

# ── Install ───────────────────────────────────────────────────────────
# Copies the plugin .so/.dll to the OBS plugin directory.
if(CMAKE_INSTALL_PREFIX_INITIALIZED_TO_DEFAULT AND UNIX AND NOT APPLE)
    set(CMAKE_INSTALL_PREFIX "$ENV{HOME}/.config/obs-studio/plugins" CACHE PATH "" FORCE)
endif()

install(TARGETS obs-android-usbcam
    LIBRARY DESTINATION obs-android-usbcam/bin/${CMAKE_SYSTEM_PROCESSOR}
    RUNTIME DESTINATION obs-android-usbcam/bin/${CMAKE_SYSTEM_PROCESSOR}
)
