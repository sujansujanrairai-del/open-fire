# ---------------------------------------------------------------------------
# Open Fire native audio engine
# ---------------------------------------------------------------------------
# The engine is a C++17 library built around Google Oboe. Oboe itself is
# vendored under third_party/oboe (Apache-2.0, release 1.11.0) and compiled
# from source, so the build is hermetic: no network access, no Gradle
# dependency on a Prefab package, and no version drift between the headers the
# engine compiles against and the library it links.
#
# 16 KB page sizes are supported from NDK r28 on by default; the explicit
# max-page-size link option below keeps the library aligned for r27 toolchains
# as well, which is what Android 15+ devices require.
# ---------------------------------------------------------------------------
cmake_minimum_required(VERSION 3.22.1)

project(openfire_audio LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Oboe: a static library built from the vendored sources.
add_subdirectory(third_party/oboe EXCLUDE_FROM_ALL)

add_library(openfire_audio SHARED
    engine/AudioEngine.cpp
    engine/of_audio.cpp
    jni/JniBridge.cpp
)

target_include_directories(openfire_audio PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/engine
)

target_compile_options(openfire_audio PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
    -fexceptions
    -frtti
    -O3
    -fno-math-errno
    -fno-trapping-math
)

target_link_options(openfire_audio PRIVATE
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
    -Wl,--gc-sections
)

# The shared C++ runtime carries its exceptions and RTTI; every Oboe symbol
# stays private to this library so it cannot clash with another .so.
set_target_properties(oboe PROPERTIES CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN ON)

# Only JNI_OnLoad is exported: every other symbol stays inside the library.
target_link_options(openfire_audio PRIVATE
    -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/openfire.map
)

target_link_libraries(openfire_audio
    PRIVATE
    oboe
    android
    log
)
