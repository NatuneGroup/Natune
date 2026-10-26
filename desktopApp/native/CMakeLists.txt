# ArchiveTune (2026)
# © Rukamori — github.com/rukamori
# GPL-3.0 License | Contributors: see git history
# Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
# Portions © vossgraves — github.com/vossgraves

# Builds libarchivetune_engine, the in-process desktop playback engine, the two-deck output mixer and the
# video artwork reader with their JNI bindings.
# The FFmpeg, OpenSSL, libxml2 and zlib shared libraries come from the prefix that
# scripts/build-native-deps.sh produces; the build never downloads anything.
cmake_minimum_required(VERSION 3.20)
project(archivetune_engine C)

set(AE_PREFIX "" CACHE PATH "Native dependency prefix from scripts/build-native-deps.sh")
set(AE_MINIAUDIO_DIR "" CACHE PATH "Directory that contains miniaudio.h")

if(NOT AE_PREFIX OR NOT EXISTS "${AE_PREFIX}/lib")
    message(FATAL_ERROR "AE_PREFIX must point at the native dependency prefix (missing lib directory)")
endif()
if(NOT AE_MINIAUDIO_DIR OR NOT EXISTS "${AE_MINIAUDIO_DIR}/miniaudio.h")
    message(FATAL_ERROR "AE_MINIAUDIO_DIR must contain miniaudio.h")
endif()

find_package(JNI REQUIRED)
find_package(Threads REQUIRED)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
# gnu11 keeps POSIX declarations such as strdup and clock_gettime visible.
set(CMAKE_C_EXTENSIONS ON)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

foreach(component avformat avcodec avfilter avutil swscale)
    find_library(AE_${component}_LIBRARY NAMES ${component} PATHS "${AE_PREFIX}/lib" NO_DEFAULT_PATH REQUIRED)
endforeach()

add_library(archivetune_engine SHARED
    src/archivetune_engine.c
    src/archivetune_device.c
    src/archivetune_engine_jni.c
    src/archivetune_mixer.c
    src/archivetune_mixer_jni.c
    src/archivetune_probe.c
    src/archivetune_probe_jni.c
    src/archivetune_video.c
    src/archivetune_video_jni.c
)

target_include_directories(archivetune_engine
    PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/include"
        "${CMAKE_CURRENT_SOURCE_DIR}/src"
        "${AE_MINIAUDIO_DIR}"
        "${AE_PREFIX}/include"
        ${JNI_INCLUDE_DIRS}
)

target_compile_options(archivetune_engine PRIVATE -Wall -Wextra)

target_link_libraries(archivetune_engine
    PRIVATE
        ${AE_avformat_LIBRARY}
        ${AE_avcodec_LIBRARY}
        ${AE_avfilter_LIBRARY}
        ${AE_avutil_LIBRARY}
        ${AE_swscale_LIBRARY}
        Threads::Threads
        ${CMAKE_DL_LIBS}
        m
)
if(APPLE)
    # miniaudio's CoreAudio backend and the mixer need these; nothing else on Apple resolves them.
    target_link_libraries(archivetune_engine PRIVATE
        "-framework AudioToolbox" "-framework AudioUnit" "-framework CoreAudio" "-framework CoreFoundation")
endif()

if(UNIX AND NOT APPLE)
    # The transitive FFmpeg dependencies (TLS, XML, zlib) live in the same prefix, so the linker needs
    # the same search path at link time as the loader uses at run time. ld64 has no -rpath-link.
    target_link_options(archivetune_engine PRIVATE "LINKER:-rpath-link,${AE_PREFIX}/lib")
    # DT_RPATH is inherited by the FFmpeg/TLS/XML closure; DT_RUNPATH is not.
    target_link_options(archivetune_engine PRIVATE "LINKER:--disable-new-dtags")
    set_property(TARGET archivetune_engine PROPERTY INSTALL_RPATH "$ORIGIN")
endif()
if(APPLE)
    # Stage script gives every dylib an @rpath identity; @loader_path resolves them beside the library.
    set_property(TARGET archivetune_engine PROPERTY INSTALL_RPATH "@loader_path")
endif()
if(MINGW)
    # Static link libgcc/winpthreads so the package's only DLL dependencies are the staged ones and
    # Windows system libraries. Windows DLL resolution already covers the install directory.
    target_link_options(archivetune_engine PRIVATE -static)
    # System.mapLibraryName gives archivetune_engine.dll; MinGW's lib prefix would break that lookup.
    set_property(TARGET archivetune_engine PROPERTY PREFIX "")
endif()
set_target_properties(archivetune_engine PROPERTIES
    BUILD_RPATH "${AE_PREFIX}/lib"
    BUILD_WITH_INSTALL_RPATH OFF
)
install(TARGETS archivetune_engine LIBRARY DESTINATION . RUNTIME DESTINATION .)

include(CTest)
if(BUILD_TESTING)
    add_executable(archivetune_realtime_test EXCLUDE_FROM_ALL tests/realtime_render_test.c src/archivetune_device.c)
    target_include_directories(archivetune_realtime_test PRIVATE include src "${AE_MINIAUDIO_DIR}" "${AE_PREFIX}/include")
    target_compile_options(archivetune_realtime_test PRIVATE -Wall -Wextra)
    target_link_libraries(archivetune_realtime_test PRIVATE
        ${AE_avformat_LIBRARY} ${AE_avcodec_LIBRARY} ${AE_avfilter_LIBRARY} ${AE_avutil_LIBRARY}
        Threads::Threads ${CMAKE_DL_LIBS} m)
    target_link_options(archivetune_realtime_test PRIVATE "LINKER:-rpath-link,${AE_PREFIX}/lib")
    if(UNIX AND NOT APPLE)
        target_link_options(archivetune_realtime_test PRIVATE "LINKER:--disable-new-dtags")
    endif()
    set_target_properties(archivetune_realtime_test PROPERTIES BUILD_RPATH "${AE_PREFIX}/lib")
    add_test(NAME realtime-render COMMAND archivetune_realtime_test
        "${CMAKE_CURRENT_SOURCE_DIR}/../src/jvmTest/resources/playback/ramp-48k.wav")
    set_tests_properties(realtime-render PROPERTIES TIMEOUT 15)
endif()
