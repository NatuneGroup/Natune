/*
 * ArchiveTune (2026), GPL-3.0
 * © Rukamori — github.com/rukamori; Portions © vossgraves — github.com/vossgraves
 * Contributors: see git history. Do not remove or alter this notice.
 */
#include "jni_callbacks.hpp"
#include <windows.h>
#include <shellapi.h>
#include <shobjidl.h>
#include <dwmapi.h>
#include <systemmediatransportcontrolsinterop.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Media.h>
#include <winrt/Windows.Media.Playback.h>
#include <winrt/Windows.Storage.Streams.h>
#include <thread>
#include <mutex>
#include <future>
#include <vector>
#include <atomic>
#include <stdexcept>
#include <memory>

using namespace winrt;
using namespace Windows::Media;
using namespace Windows::Foundation;
constexpr UINT WM_UPDATE_MEDIA = WM_APP + 20;
constexpr UINT WM_TRAY_EVENT = WM_APP + 21;
constexpr UINT WM_UPDATE_ARTWORK = WM_APP + 22;
struct MediaUpdate {
    std::string track, title, artist, album, status;
    int64_t position = 0, duration = 0;
    double speed = 1;
    bool seek = false, next = false, previous = false, advancing = false;
};
struct CallbackState {
    JavaCallbacks callbacks;
    std::mutex mutex;
    MediaUpdate update;
    std::string artworkTrack;
    std::vector<uint8_t> artwork;
    std::atomic<bool> closing{false};
    CallbackState(JNIEnv* env, jobject target) : callbacks(env, target) {}
};
struct Session {
    std::shared_ptr<CallbackState> state;
    HWND owner = nullptr, messages = nullptr;
    std::thread worker;
    SystemMediaTransportControls controls{nullptr};
    event_token buttons{}, seek{}, rate{};
    NOTIFYICONDATAW icon{};
    bool ownsIcon = false;
    UINT taskbarCreated = RegisterWindowMessageW(L"TaskbarCreated");
    std::atomic<bool> tray{false};
    std::vector<std::wstring> labels;
    std::string displayedArtworkTrack;
    Session(JNIEnv* env, jobject target) : state(std::make_shared<CallbackState>(env, target)) {}
};
static std::wstring wide(const std::string& value) {
    if (value.empty()) return {};
    int count = MultiByteToWideChar(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), nullptr, 0);
    std::wstring result(count, 0);
    MultiByteToWideChar(CP_UTF8, 0, value.data(), static_cast<int>(value.size()), result.data(), count);
    return result;
}
static bool obscured(HWND window) {
    if (!IsWindowVisible(window) || IsIconic(window)) return true;
    DWORD cloaked = 0;
    DwmGetWindowAttribute(window, DWMWA_CLOAKED, &cloaked, sizeof(cloaked));
    if (cloaked) return true;
    RECT own{};
    if (!GetWindowRect(window, &own)) return false;
    HRGN remaining = CreateRectRgnIndirect(&own);
    bool hidden = false;
    for (HWND above = GetWindow(window, GW_HWNDPREV); above; above = GetWindow(above, GW_HWNDPREV)) {
        if (!IsWindowVisible(above) || IsIconic(above)) continue;
        LONG_PTR style = GetWindowLongPtrW(above, GWL_EXSTYLE);
        if (style & (WS_EX_TRANSPARENT | WS_EX_LAYERED)) continue;
        RECT rect{};
        if (!GetWindowRect(above, &rect)) continue;
        HRGN cover = CreateRectRgnIndirect(&rect);
        int result = CombineRgn(remaining, remaining, cover, RGN_DIFF);
        DeleteObject(cover);
        if (result == NULLREGION) { hidden = true; break; }
    }
    DeleteObject(remaining);
    return hidden;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_moe_rukamori_archivetune_desktop_integration_NativeDesktopBridge_registerUriScheme(
    JNIEnv* env, jobject, jstring executable) {
    auto path = wide(utf8(env, executable));
    if (path.empty() || path.find(L'"') != std::wstring::npos || GetFileAttributesW(path.c_str()) == INVALID_FILE_ATTRIBUTES) return JNI_FALSE;
    HKEY key = nullptr;
    if (RegCreateKeyExW(HKEY_CURRENT_USER, L"Software\\Classes\\archivetune", 0, nullptr, 0, KEY_SET_VALUE, nullptr, &key, nullptr) != ERROR_SUCCESS)
        return JNI_FALSE;
    const wchar_t* description = L"URL:ArchiveTune";
    LONG result = RegSetValueExW(key, nullptr, 0, REG_SZ, reinterpret_cast<const BYTE*>(description), (wcslen(description) + 1) * sizeof(wchar_t));
    if (result == ERROR_SUCCESS) result = RegSetValueExW(key, L"URL Protocol", 0, REG_SZ, reinterpret_cast<const BYTE*>(L""), sizeof(wchar_t));
    RegCloseKey(key);
    if (result != ERROR_SUCCESS) return JNI_FALSE;
    if (RegCreateKeyExW(HKEY_CURRENT_USER, L"Software\\Classes\\archivetune\\shell\\open\\command", 0, nullptr, 0, KEY_SET_VALUE, nullptr, &key, nullptr) != ERROR_SUCCESS)
        return JNI_FALSE;
    std::wstring command = L"\"" + path + L"\" \"%1\"";
    result = RegSetValueExW(key, nullptr, 0, REG_SZ, reinterpret_cast<const BYTE*>(command.c_str()), (command.size() + 1) * sizeof(wchar_t));
    RegCloseKey(key);
    return result == ERROR_SUCCESS ? JNI_TRUE : JNI_FALSE;
}
static void updateMedia(Session* s) {
    MediaUpdate value;
    { std::lock_guard<std::mutex> guard(s->state->mutex); value = s->state->update; }
    auto display = s->controls.DisplayUpdater();
    display.Type(MediaPlaybackType::Music); // Must precede MusicProperties.
    auto music = display.MusicProperties();
    music.Title(wide(value.title)); music.Artist(wide(value.artist)); music.AlbumTitle(wide(value.album));
    if (s->displayedArtworkTrack != value.track) {
        display.Thumbnail(nullptr);
        s->displayedArtworkTrack.clear();
    }
    display.Update();
    s->controls.PlaybackStatus(value.status == "Playing" ? MediaPlaybackStatus::Playing :
        value.status == "Paused" ? MediaPlaybackStatus::Paused : MediaPlaybackStatus::Stopped);
    s->controls.IsNextEnabled(value.next); s->controls.IsPreviousEnabled(value.previous);
    s->controls.PlaybackRate(value.advancing ? value.speed : 0.0);
    SystemMediaTransportControlsTimelineProperties timeline;
    timeline.StartTime(TimeSpan{0}); timeline.MinSeekTime(TimeSpan{0});
    timeline.EndTime(TimeSpan{value.duration * 10}); timeline.MaxSeekTime(TimeSpan{value.duration * 10});
    timeline.Position(TimeSpan{value.position * 10});
    s->controls.UpdateTimelineProperties(timeline);
}
static void updateArtwork(Session* s) {
    std::vector<uint8_t> bytes;
    std::string track;
    {
        std::lock_guard<std::mutex> guard(s->state->mutex);
        if (s->state->artworkTrack != s->state->update.track) return;
        track = s->state->artworkTrack;
        bytes = s->state->artwork;
    }
    auto display = s->controls.DisplayUpdater();
    display.Thumbnail(nullptr);
    if (!bytes.empty()) {
        using namespace Windows::Storage::Streams;
        InMemoryRandomAccessStream stream;
        DataWriter writer(stream);
        writer.WriteBytes(bytes);
        writer.StoreAsync().get();
        writer.DetachStream();
        stream.Seek(0);
        display.Thumbnail(RandomAccessStreamReference::CreateFromStream(stream));
    }
    s->displayedArtworkTrack = track;
    display.Update();
}
static LRESULT CALLBACK messages(HWND hwnd, UINT message, WPARAM wparam, LPARAM lparam) {
    auto s = reinterpret_cast<Session*>(GetWindowLongPtrW(hwnd, GWLP_USERDATA));
    if (message == WM_NCCREATE) {
        s = static_cast<Session*>(reinterpret_cast<CREATESTRUCTW*>(lparam)->lpCreateParams);
        SetWindowLongPtrW(hwnd, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(s));
    }
    if (!s) return DefWindowProcW(hwnd, message, wparam, lparam);
    try {
        if (message == s->taskbarCreated) {
            s->tray = Shell_NotifyIconW(NIM_ADD, &s->icon) != FALSE;
            if (!s->tray) s->state->callbacks.emit("raise");
            return 0;
        }
        if (message == WM_UPDATE_MEDIA) { updateMedia(s); return 0; }
        if (message == WM_UPDATE_ARTWORK) { updateArtwork(s); return 0; }
        if (message == WM_TIMER) { s->state->callbacks.visibility(obscured(s->owner), true); return 0; }
        if (message == WM_TRAY_EVENT) {
            if (lparam == WM_LBUTTONUP) s->state->callbacks.emit("raise");
            if (lparam == WM_RBUTTONUP) {
                HMENU menu = CreatePopupMenu();
                for (UINT i = 0; i < s->labels.size(); ++i) AppendMenuW(menu, MF_STRING, i + 1, s->labels[i].c_str());
                POINT point; GetCursorPos(&point); SetForegroundWindow(hwnd);
                int item = TrackPopupMenu(menu, TPM_RETURNCMD | TPM_NONOTIFY, point.x, point.y, 0, hwnd, nullptr);
                DestroyMenu(menu);
                const char* names[] = {"raise", "toggle", "next", "previous", "quit"};
                if (item > 0 && item <= 5) s->state->callbacks.emit(names[item - 1]);
            }
            return 0;
        }
        if (message == WM_CLOSE) { DestroyWindow(hwnd); return 0; }
        if (message == WM_DESTROY) { PostQuitMessage(0); return 0; }
    } catch (...) { /* Native integration failure cannot unwind into Win32 or audio. */ }
    return DefWindowProcW(hwnd, message, wparam, lparam);
}
extern "C" JNIEXPORT jlong JNICALL
Java_moe_rukamori_archivetune_desktop_integration_NativeDesktopBridge_create(
    JNIEnv* env, jobject, jlong owner, jobject callbacks, jobjectArray labels) {
    auto s = new Session(env, callbacks);
    s->owner = reinterpret_cast<HWND>(owner);
    for (int i = 0; i < env->GetArrayLength(labels); ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(labels, i));
        s->labels.push_back(wide(utf8(env, value))); env->DeleteLocalRef(value);
    }
    std::promise<bool> started;
    auto result = started.get_future();
    s->worker = std::thread([s, ready = std::move(started)]() mutable {
        bool apartmentInitialized = false;
        try {
            init_apartment(apartment_type::multi_threaded);
            apartmentInitialized = true;
            WNDCLASSW cls{}; cls.lpfnWndProc = messages; cls.hInstance = GetModuleHandleW(nullptr);
            cls.lpszClassName = L"ArchiveTuneSystemControls";
            RegisterClassW(&cls);
            s->messages = CreateWindowW(cls.lpszClassName, L"", WS_OVERLAPPED, 0, 0, 0, 0, nullptr, nullptr, cls.hInstance, s);
            if (!s->messages) throw std::runtime_error("Cannot create media callback window");
            auto interop = get_activation_factory<SystemMediaTransportControls, ISystemMediaTransportControlsInterop>();
            check_hresult(interop->GetForWindow(s->owner, guid_of<SystemMediaTransportControls>(), put_abi(s->controls)));
            s->controls.IsEnabled(true); s->controls.IsPlayEnabled(true); s->controls.IsPauseEnabled(true);
            s->controls.IsStopEnabled(true);
            auto state = s->state;
            s->buttons = s->controls.ButtonPressed([state](auto const&, auto const& event) {
                if (state->closing) return;
                switch (event.Button()) {
                    case SystemMediaTransportControlsButton::Play: state->callbacks.emit("play"); break;
                    case SystemMediaTransportControlsButton::Pause: state->callbacks.emit("pause"); break;
                    case SystemMediaTransportControlsButton::Stop: state->callbacks.emit("stop"); break;
                    case SystemMediaTransportControlsButton::Next: state->callbacks.emit("next"); break;
                    case SystemMediaTransportControlsButton::Previous: state->callbacks.emit("previous"); break;
                    default: break;
                }
            });
            s->seek = s->controls.PlaybackPositionChangeRequested([state](auto const&, auto const& event) {
                if (state->closing) return;
                MediaUpdate value; { std::lock_guard<std::mutex> guard(state->mutex); value = state->update; }
                if (value.seek) state->callbacks.emit("seek", value.track, event.RequestedPlaybackPosition().count() / 10);
            });
            s->rate = s->controls.PlaybackRateChangeRequested([state](auto const&, auto const& event) {
                if (!state->closing) state->callbacks.emit("speed", "", 0, event.RequestedPlaybackRate());
            });
            s->icon.cbSize = sizeof(s->icon); s->icon.hWnd = s->messages; s->icon.uID = 1;
            s->icon.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP; s->icon.uCallbackMessage = WM_TRAY_EVENT;
            wchar_t launcher[MAX_PATH]{};
            if (GetModuleFileNameW(nullptr, launcher, MAX_PATH))
                s->ownsIcon = ExtractIconExW(launcher, 0, nullptr, &s->icon.hIcon, 1) > 0;
            if (!s->ownsIcon) s->icon.hIcon = LoadIconW(nullptr, IDI_APPLICATION);
            wcscpy_s(s->icon.szTip, L"Natune");
            s->tray = Shell_NotifyIconW(NIM_ADD, &s->icon) != FALSE;
            SetTimer(s->messages, 1, 500, nullptr);
            ready.set_value(true);
            MSG message;
            while (GetMessageW(&message, nullptr, 0, 0) > 0) { TranslateMessage(&message); DispatchMessageW(&message); }
        } catch (...) { try { ready.set_value(false); } catch (...) {} }
        s->state->closing = true;
        try { if (s->controls) {
            s->controls.ButtonPressed(s->buttons);
            s->controls.PlaybackPositionChangeRequested(s->seek);
            s->controls.PlaybackRateChangeRequested(s->rate);
            s->controls.IsEnabled(false); s->controls.DisplayUpdater().ClearAll(); s->controls.DisplayUpdater().Update();
        } } catch (...) { /* Shutdown must not terminate the process when WinRT has gone away. */ }
        if (s->tray) Shell_NotifyIconW(NIM_DELETE, &s->icon);
        if (s->ownsIcon) DestroyIcon(s->icon.hIcon);
        if (IsWindow(s->messages)) DestroyWindow(s->messages);
        s->tray = false;
        s->controls = nullptr;
        if (apartmentInitialized) uninit_apartment();
    });
    if (!result.get()) { s->worker.join(); delete s; return 0; }
    return reinterpret_cast<jlong>(s);
}
extern "C" JNIEXPORT void JNICALL
Java_moe_rukamori_archivetune_desktop_integration_NativeDesktopBridge_update(
    JNIEnv* env, jobject, jlong handle, jstring track, jstring title, jstring artist, jstring album, jstring status,
    jlong position, jlong duration, jdouble speed, jboolean seek, jboolean next, jboolean previous, jboolean advancing) {
    auto s = reinterpret_cast<Session*>(handle); if (!s) return;
    { std::lock_guard<std::mutex> guard(s->state->mutex);
      s->state->update = {utf8(env, track), utf8(env, title), utf8(env, artist), utf8(env, album), utf8(env, status),
          position, duration, speed, seek == JNI_TRUE, next == JNI_TRUE, previous == JNI_TRUE, advancing == JNI_TRUE}; }
    PostMessageW(s->messages, WM_UPDATE_MEDIA, 0, 0);
}
extern "C" JNIEXPORT void JNICALL
Java_moe_rukamori_archivetune_desktop_integration_NativeDesktopBridge_setArtwork(
    JNIEnv* env, jobject, jlong handle, jstring track, jbyteArray image) {
    auto s = reinterpret_cast<Session*>(handle); if (!s) return;
    const jsize size = image ? env->GetArrayLength(image) : 0;
    if (size > 4 * 1024 * 1024) return;
    std::vector<uint8_t> bytes(size);
    if (size) env->GetByteArrayRegion(image, 0, size, reinterpret_cast<jbyte*>(bytes.data()));
    if (env->ExceptionCheck()) return;
    {
        std::lock_guard<std::mutex> guard(s->state->mutex);
        s->state->artworkTrack = utf8(env, track);
        s->state->artwork = std::move(bytes);
    }
    PostMessageW(s->messages, WM_UPDATE_ARTWORK, 0, 0);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_moe_rukamori_archivetune_desktop_integration_NativeDesktopBridge_trayAvailable(JNIEnv*, jobject, jlong handle) {
    return reinterpret_cast<Session*>(handle)->tray ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT void JNICALL
Java_moe_rukamori_archivetune_desktop_integration_NativeDesktopBridge_release(JNIEnv*, jobject, jlong handle) {
    auto s = reinterpret_cast<Session*>(handle); if (!s) return;
    PostMessageW(s->messages, WM_CLOSE, 0, 0); s->worker.join(); delete s;
}
extern "C" JNIEXPORT jobjectArray JNICALL
Java_moe_rukamori_archivetune_desktop_integration_NativeDesktopBridge_pickFiles(
    JNIEnv* env, jobject, jlong owner, jboolean folder, jboolean multiple, jstring title) {
    jobjectArray result = nullptr;
    HRESULT initialized = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    try {
        com_ptr<IFileOpenDialog> dialog;
        check_hresult(CoCreateInstance(CLSID_FileOpenDialog, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(dialog.put())));
        DWORD flags; dialog->GetOptions(&flags);
        flags |= FOS_FORCEFILESYSTEM | FOS_PATHMUSTEXIST | FOS_FILEMUSTEXIST;
        if (folder) flags |= FOS_PICKFOLDERS;
        if (multiple) flags |= FOS_ALLOWMULTISELECT;
        check_hresult(dialog->SetOptions(flags)); dialog->SetTitle(wide(utf8(env, title)).c_str());
        HRESULT shown = dialog->Show(reinterpret_cast<HWND>(owner));
        if (shown != HRESULT_FROM_WIN32(ERROR_CANCELLED)) {
            check_hresult(shown);
            com_ptr<IShellItemArray> items; check_hresult(dialog->GetResults(items.put()));
            DWORD count; items->GetCount(&count);
            result = env->NewObjectArray(count, env->FindClass("java/lang/String"), nullptr);
            for (DWORD i = 0; i < count; ++i) {
                com_ptr<IShellItem> item; items->GetItemAt(i, item.put()); PWSTR path = nullptr;
                check_hresult(item->GetDisplayName(SIGDN_FILESYSPATH, &path));
                auto value = env->NewString(reinterpret_cast<const jchar*>(path), static_cast<jsize>(wcslen(path)));
                env->SetObjectArrayElement(result, i, value); env->DeleteLocalRef(value); CoTaskMemFree(path);
            }
        }
    } catch (...) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Windows native file picker failed");
    }
    if (SUCCEEDED(initialized)) CoUninitialize();
    return result;
}
