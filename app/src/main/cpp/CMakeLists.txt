# Native analysis support for the ported audio code paths.
#
# Phase 1: whole-track DSP analysis (tempo/key/energy/structure via
# autocorrelation, no ML model). Phase 2: the log-mel front end the Beat
# This! ONNX beat/downbeat model reads (mel_spectrogram.cpp / mel_jni.cpp).
# Phase 3: the linear-frequency STFT the open-unmix vocal-separation model
# reads (vocal_spectrogram.cpp / vocal_jni.cpp). Both models themselves run
# through ONNX Runtime on the Kotlin side, not here. The shared analyzer
# sources live under the repo's root `native/` tree.
#
# Two audio libraries build here as well: the AAudio exclusive output stream
# (archivetune_dsp) and the direct USB audio output (archivetune_usb). The retired Tryptify `monochrome_dsp` and
# LastWave `lastwave_audio` engines, with their vendored soxr resampler, are
# gone: the shared DSP chain replaced them all in Kotlin.

cmake_minimum_required(VERSION 3.22.1)
project(archivetune_analysis LANGUAGES C CXX)

get_filename_component(ARCHIVETUNE_NATIVE_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../native"
    ABSOLUTE)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Per-function sections let the linker drop code nothing reaches, which shrinks every
# library in each per-ABI APK. Exported JNI entry points stay, because a shared
# library's dynamic exports are always GC roots.
add_compile_options(-ffunction-sections -fdata-sections)
add_link_options("-Wl,--gc-sections")

# Each JNI library owns its STL objects; only JNI primitives/arrays cross its boundary.
# Hide archive symbols when statically linking the STL so dlopen cannot interpose another
# library's allocator/exception runtime. Optional prebuilt SDK variants use c++_shared.
if(ANDROID_STL STREQUAL "c++_static")
    add_link_options("-Wl,--exclude-libs,ALL")
endif()

add_library(archivetune_analysis SHARED
    ${ARCHIVETUNE_NATIVE_DIR}/analyzer/audio_analysis.cpp
    ${ARCHIVETUNE_NATIVE_DIR}/analyzer/tempo_analysis.cpp
    ${ARCHIVETUNE_NATIVE_DIR}/analyzer/resampler.cpp
    ${ARCHIVETUNE_NATIVE_DIR}/analyzer/mel_spectrogram.cpp
    ${ARCHIVETUNE_NATIVE_DIR}/analyzer/vocal_spectrogram.cpp
    jni/analysis_jni.cpp
    jni/mel_jni.cpp
    jni/vocal_jni.cpp
)

target_include_directories(archivetune_analysis PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${ARCHIVETUNE_NATIVE_DIR}
)

# -ffast-math is deliberately NOT enabled: the analyzer's log/mel-adjacent
# arithmetic (see audio_analysis.cpp) is tuned against exact IEEE behaviour.
# -fexceptions is explicit because the JNI bridges catch std::bad_alloc so an
# allocation failure degrades to "no analysis" instead of aborting the process
# (an exception unwinding out of a JNI frame is a hard SIGABRT with no log).
target_compile_options(archivetune_analysis PRIVATE -O3 -fvisibility=hidden -fexceptions)

find_library(log-lib log)
target_link_libraries(archivetune_analysis ${log-lib})

# 16KB page-size support (ported from BitChord's own fix, 77cd233): Android
# 15+ 16KB-page kernels refuse to map LOAD segments aligned only to 4KB — on
# those devices an unaligned libarchivetune_analysis.so either fails to load
# (analysis silently unavailable) or takes the process down at dlopen time.
# -z max-page-size=16384 is a superset of 4KB alignment, so 4KB devices are
# unaffected; APK-side alignment of the uncompressed .so is handled by AGP.
target_link_options(archivetune_analysis PRIVATE "-Wl,-z,max-page-size=16384")

# ---------------------------------------------------------------------------
# archivetune_dsp: the AAudio exclusive output stream. Loaded by FloatDsp.kt,
# whose `available` flag decides whether the USB-exclusive route can be
# offered; a device without the library keeps the stock output.
#
# The 32-bit float DSP chain that used to live beside it (float_dsp.cpp and its
# JNI half) went with the float-DSP processor: the shared chain does that work
# now, and this library only opens, writes and reports the exclusive stream.
# ---------------------------------------------------------------------------
add_library(archivetune_dsp SHARED
    ${ARCHIVETUNE_NATIVE_DIR}/dsp/aaudio_output.cpp
    jni/dsp_jni.cpp
)
target_include_directories(archivetune_dsp PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${ARCHIVETUNE_NATIVE_DIR}
)
target_compile_options(archivetune_dsp PRIVATE -O3 -fvisibility=hidden -fexceptions)
find_library(log-lib-dsp log)
# AAudio is loaded with dlopen (native/dsp/aaudio_output.cpp) rather than linked:
# libaaudio.so does not exist below Android 8.0, so a link-time dependency would
# stop the whole library from loading on minSdk 24-25 devices.
find_library(dl-lib-dsp dl)
target_link_libraries(archivetune_dsp ${log-lib-dsp} ${dl-lib-dsp})
target_link_options(archivetune_dsp PRIVATE "-Wl,-z,max-page-size=16384")

# ---------------------------------------------------------------------------
# archivetune_usb: the direct USB audio output (native/usbdirect), loaded by
# UsbDirectNative.kt. Without it the direct driver reports itself unavailable
# and bit-perfect output stays on the Android path.
#
# Android grants the USB file descriptor; USBFS drives isochronous URBs directly.
# No vendored driver or device-discovery library is needed.
# ---------------------------------------------------------------------------
add_library(archivetune_usb SHARED
    ${ARCHIVETUNE_NATIVE_DIR}/usbdirect/usb_direct_output.cpp
    jni/usb_direct_jni.cpp
)
target_include_directories(archivetune_usb PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${ARCHIVETUNE_NATIVE_DIR}
)
target_compile_options(archivetune_usb PRIVATE -Os -fvisibility=hidden -fexceptions)
find_library(log-lib-usb log)
target_link_libraries(archivetune_usb PRIVATE ${log-lib-usb})
target_link_options(archivetune_usb PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,--strip-all"
    "-Wl,--version-script,${CMAKE_CURRENT_SOURCE_DIR}/usb_direct_exports.map"
)
set_property(TARGET archivetune_usb APPEND PROPERTY LINK_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/usb_direct_exports.map"
)

# The optional Discord target/package lookup is disabled without the licensed AAR.
# Native audio keeps its existing CMake/prefab configuration independently.
option(NATUNE_DISCORD_SOCIAL_SDK "Build the OAuth-only Discord Social SDK bridge" OFF)
if(NATUNE_DISCORD_SOCIAL_SDK)
    find_package(discord_partner_sdk REQUIRED CONFIG)
    add_library(natune_discord_social SHARED jni/discord_social_jni.cpp)
    target_link_libraries(natune_discord_social PRIVATE discord_partner_sdk::discord_partner_sdk)
    target_compile_options(natune_discord_social PRIVATE -fexceptions -fvisibility=hidden)
    target_link_options(natune_discord_social PRIVATE "-Wl,-z,max-page-size=16384")
endif()
