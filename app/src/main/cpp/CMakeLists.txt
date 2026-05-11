# Project Atlas - Android Virtual Space
#
# CMakeLists.txt
# Build configuration for the atlas_native shared library.
# This native library provides JNI hooks for GameGuardian compatibility
# and low-level memory/process operations.

cmake_minimum_required(VERSION 3.22)

project(atlas_native
    VERSION     1.0.0
    LANGUAGES   CXX
    DESCRIPTION "Atlas native bridge for PLT hooking and /proc filesystem operations"
)

# ──────────────────────────────────────────────────────────────
#  Build the shared library
# ──────────────────────────────────────────────────────────────

add_library(atlas_native SHARED
    atlas_native.cpp
)

# ──────────────────────────────────────────────────────────────
#  Include directories
# ──────────────────────────────────────────────────────────────

# Add the cpp directory itself so that future header files are found
target_include_directories(atlas_native PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# ──────────────────────────────────────────────────────────────
#  Link libraries
# ──────────────────────────────────────────────────────────────

# Android log library for __android_log_print
target_link_libraries(atlas_native
    log
)

# ──────────────────────────────────────────────────────────────
#  C++ standard
# ──────────────────────────────────────────────────────────────

target_compile_features(atlas_native PRIVATE
    cxx_std_17
)

# ──────────────────────────────────────────────────────────────
#  Compile definitions
# ──────────────────────────────────────────────────────────────

# Ensure we use the C++ shared STL (set via ANDROID_STL in build.gradle.kts)
# and expose the version info
target_compile_definitions(atlas_native PRIVATE
    ATLAS_VERSION_MAJOR=1
    ATLAS_VERSION_MINOR=0
    ATLAS_VERSION_PATCH=0
)

# ──────────────────────────────────────────────────────────────
#  Compiler flags — shared between Debug and Release
# ──────────────────────────────────────────────────────────────

# Warning flags for all builds
target_compile_options(atlas_native PRIVATE
    -Wall
    -Wextra
    -Werror=return-type
    -Werror=uninitialized
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
)

# ──────────────────────────────────────────────────────────────
#  Release-specific flags
# ──────────────────────────────────────────────────────────────

target_compile_options(atlas_native PRIVATE
    $<$<CONFIG:Release>:
        -O2
        -flto
        -DNDEBUG
        -fomit-frame-pointer
    >
)

# Link-time optimization for release
target_link_options(atlas_native PRIVATE
    $<$<CONFIG:Release>:
        -flto
        -Wl,--gc-sections
        -Wl,--strip-all
    >
)

# ──────────────────────────────────────────────────────────────
#  Debug-specific flags
# ──────────────────────────────────────────────────────────────

target_compile_options(atlas_native PRIVATE
    $<$<CONFIG:Debug>:
        -g
        -DDEBUG
        -O0
        -fno-omit-frame-pointer
    >
)

# ──────────────────────────────────────────────────────────────
#  ABI-specific notes
# ──────────────────────────────────────────────────────────────
#
# The build system supports both armeabi-v7a and arm64-v8a as
# declared in build.gradle.kts:
#
#   ndk {
#       abiFilters += listOf("armeabi-v7a", "arm64-v8a")
#   }
#
# No ABI-specific compile flags are needed here since the NDK
# toolchain handles architecture differences automatically.
#
# ──────────────────────────────────────────────────────────────
#  bhook integration (future)
# ──────────────────────────────────────────────────────────────
#
# To integrate bhook for PLT hooking, add the following:
#
#   # Assuming bhook is built as a static library in a subdirectory:
#   add_subdirectory(bhook)
#   target_link_libraries(atlas_native bhook)
#   target_include_directories(atlas_native PRIVATE bhook/include)
#
# Or as a prebuilt:
#
#   add_library(bhook STATIC IMPORTED)
#   set_target_properties(bhook PROPERTIES
#       IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/libs/${ANDROID_ABI}/libbhook.a
#   )
#   target_link_libraries(atlas_native bhook)
# ──────────────────────────────────────────────────────────────
