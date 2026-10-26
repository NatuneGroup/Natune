# Standalone build for the cookie-only sign-in window and the Linux file chooser. It is configured on its own and is
# deliberately not added to the playback engine build, so a missing WebView2 SDK or WebKitGTK cannot break the engine.
# desktopApp's buildNativeBrowser and installNativeBrowser tasks build it and place archivetune_browser in the host
# native resource directory that the package copies. A full desktop package requires it: there is no Chrome fallback,
# and cookie-only sign-in is not silently omitted.
#
# Package prerequisites (recipes only: nothing here is downloaded or installed by the build):
#   Windows x64/arm64: Microsoft.Web.WebView2 SDK 1.0.4258.31 extracted from its NuGet package and passed as
#     -PdesktopWebView2Sdk=<dir>, which becomes CMake -DAE_WEBVIEW2_SDK. The static WebView2LoaderStatic.lib is linked,
#     so no WebView2Loader.dll ships. The target machine needs the Microsoft Edge WebView2 Evergreen Runtime; the release
#     recipe pins the runtime build that matches SDK 1.0.4258.31.
#   Linux x64 build: libwebkit2gtk-4.1-dev, libgtk-3-dev, libsoup-3.0-dev, pkg-config, cmake and a JDK 21 with JNI
#     headers. Linux x64 runtime: libwebkit2gtk-4.1-0, libgtk-3-0, libsoup-3.0-0 (RPM: webkit2gtk4.1, gtk3, libsoup3).
#     The Linux package must declare these as system dependencies. desktopApp/build.gradle.kts sets no dependency field
#     for the Linux package, so the generated deb control and rpm spec must be checked for them in the final phase.
#     stage-linux-package.py stages only the media closure and excludes GTK and glib, so the browser engine comes from
#     the system.
#   macOS: Xcode command-line tools for Objective-C++. WKWebView is the system WebKit, so nothing is packaged for it.
# This revision has not been compiled or run in this lane; verification is deferred.
cmake_minimum_required(VERSION 3.20)
project(archivetune_browser LANGUAGES CXX)
find_package(JNI REQUIRED)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(WIN32)
    # Static CRT: the package must not depend on vcruntime/msvcp DLLs an end user may not have.
    set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
    set(AE_WEBVIEW2_SDK "" CACHE PATH "Extracted official Microsoft.Web.WebView2 1.0.4258.31 NuGet SDK")
    if(NOT EXISTS "${AE_WEBVIEW2_SDK}/build/native/include/WebView2.h")
        message(FATAL_ERROR "Provide the official WebView2 1.0.4258.31 SDK with -DAE_WEBVIEW2_SDK; no external Chrome fallback.")
    endif()
    # The static loader finds the installed Evergreen runtime itself, so no WebView2Loader.dll ships beside the library.
    if(CMAKE_SYSTEM_PROCESSOR MATCHES "ARM64|aarch64|AARCH64")
        set(AE_WEBVIEW2_ARCH arm64)
    else()
        set(AE_WEBVIEW2_ARCH x64)
    endif()
    set(AE_WEBVIEW2_STATIC_LOADER "${AE_WEBVIEW2_SDK}/build/native/${AE_WEBVIEW2_ARCH}/WebView2LoaderStatic.lib")
    if(NOT EXISTS "${AE_WEBVIEW2_STATIC_LOADER}")
        message(FATAL_ERROR "The WebView2 static loader is missing from the SDK: ${AE_WEBVIEW2_STATIC_LOADER}")
    endif()
    add_library(archivetune_browser SHARED browser_windows.cpp)
    target_include_directories(archivetune_browser PRIVATE "${AE_WEBVIEW2_SDK}/build/native/include")
    target_compile_definitions(archivetune_browser PRIVATE UNICODE _UNICODE WIN32_LEAN_AND_MEAN NOMINMAX)
    target_link_libraries(archivetune_browser PRIVATE
        "${AE_WEBVIEW2_STATIC_LOADER}" ole32 oleaut32 user32 shell32 shlwapi advapi32 version)
elseif(APPLE)
    enable_language(OBJCXX)
    add_library(archivetune_browser SHARED browser_macos.mm)
    target_compile_options(archivetune_browser PRIVATE -fobjc-arc)
    target_link_libraries(archivetune_browser PRIVATE "-framework AppKit" "-framework Foundation" "-framework WebKit")
else()
    # GTK 3 and libsoup 3 are named explicitly because the sources include their headers directly, instead of relying on
    # the transitive flags of webkit2gtk-4.1.
    find_package(PkgConfig REQUIRED)
    pkg_check_modules(WEBKIT REQUIRED IMPORTED_TARGET webkit2gtk-4.1)
    pkg_check_modules(GTK REQUIRED IMPORTED_TARGET gtk+-3.0)
    pkg_check_modules(SOUP REQUIRED IMPORTED_TARGET libsoup-3.0)
    add_library(archivetune_browser SHARED browser_linux.cpp)
    target_link_libraries(archivetune_browser PRIVATE PkgConfig::WEBKIT PkgConfig::GTK PkgConfig::SOUP)
    set_target_properties(archivetune_browser PROPERTIES INSTALL_RPATH "$ORIGIN")
endif()
target_include_directories(archivetune_browser PRIVATE ${JNI_INCLUDE_DIRS})
install(TARGETS archivetune_browser LIBRARY DESTINATION . RUNTIME DESTINATION .)
