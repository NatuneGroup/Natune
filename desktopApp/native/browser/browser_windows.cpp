/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

/*
 * Windows cookie-only sign-in browser on WebView2 (Evergreen runtime, SDK 1.0.4258.31).
 *
 * One single-threaded apartment thread owns every window and WebView2 object. It runs a message loop, and the JNI
 * entry points only post work to a message-only window, so no COM object is touched from a Java thread. The official
 * static loader is linked in, so the library ships no separate WebView2Loader.dll. There is no external Chrome path.
 *
 * Each attempt gets its own user-data folder under the user's temp directory. The folder is deleted after the window
 * closes. WebView2 holds its files until its processes exit, so the deletion is retried on a timer and gives up after
 * a bounded number of attempts. The origin policy is enforced in NavigationStarting, popups are refused in
 * NewWindowRequested, and downloads are cancelled in DownloadStarting.
 *
 * Not compiled or run in this lane. It is built only through the explicit AE_BROWSER option.
 */

#include <jni.h>

#include <windows.h>
#include <objbase.h>
#include <wrl.h>
#include <wrl/event.h>
#include <WebView2.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cwchar>
#include <filesystem>
#include <functional>
#include <future>
#include <map>
#include <memory>
#include <string>
#include <system_error>
#include <thread>
#include <vector>

#include "browser_callbacks.hpp"

using archivetune::browser::allCanonical;
using archivetune::browser::BrowserCallbacks;
using archivetune::browser::CookieRow;
using archivetune::browser::contains;
using archivetune::browser::httpsOriginOf;
using archivetune::browser::kMaxCookieBytes;
using archivetune::browser::kMaxCookieRows;
using archivetune::browser::OpenTicket;
using archivetune::browser::readOrigins;
using archivetune::browser::utf8FromJava;
using Microsoft::WRL::Callback;
using Microsoft::WRL::ComPtr;

namespace {

constexpr UINT kTaskMessage = WM_APP + 1;
constexpr UINT_PTR kCleanupTimer = 1;
constexpr UINT kCleanupIntervalMs = 500;
constexpr int kCleanupAttempts = 60;
constexpr int kFinishButtonId = 1001;
constexpr int kTopBarHeight = 48;
constexpr int kWindowWidth = 960;
constexpr int kWindowHeight = 720;
/* Opening waits for the owner thread, which normally answers at once. The bound keeps a wedged runtime from hanging Java. */
constexpr int kOpenTimeoutSeconds = 10;
constexpr wchar_t kOwnerClass[] = L"ArchiveTuneBrowserOwner";
constexpr wchar_t kSessionClass[] = L"ArchiveTuneBrowserSession";

using Job = std::function<void()>;

/* Owner thread only. The COM members are released there, when the session is erased. */
struct Session {
    jlong id = 0;
    HWND window = nullptr;
    HWND finish = nullptr;
    std::string startUrl;
    std::vector<std::string> origins;
    std::shared_ptr<BrowserCallbacks> callbacks;
    std::wstring userDataFolder;
    /* When set, a navigation to it is reported through onNavigate and cancelled, like shouldOverrideUrlLoading. */
    std::string capturePrefix;
    bool failed = false;
    ComPtr<ICoreWebView2Environment> environment;
    ComPtr<ICoreWebView2Controller> controller;
    ComPtr<ICoreWebView2> webview;
    ComPtr<ICoreWebView2CookieManager> cookies;
};

struct PendingCleanup {
    std::wstring path;
    int attemptsLeft;
};

HWND gOwner = nullptr;
HMODULE gModule = nullptr;

std::map<jlong, std::unique_ptr<Session>> &sessions() {
    static std::map<jlong, std::unique_ptr<Session>> registry;
    return registry;
}

std::vector<PendingCleanup> &pendingCleanup() {
    static std::vector<PendingCleanup> queue;
    return queue;
}

/* Only the owner thread assigns ids, so a plain counter is safe. */
jlong nextSessionId() {
    static jlong next = 1;
    return next++;
}

Session *findSession(jlong id) {
    auto it = sessions().find(id);
    return it == sessions().end() ? nullptr : it->second.get();
}

std::wstring wideFromUtf8(const std::string &text) {
    if (text.empty()) return std::wstring();
    const int length = MultiByteToWideChar(CP_UTF8, 0, text.data(), static_cast<int>(text.size()), nullptr, 0);
    if (length <= 0) return std::wstring();
    std::wstring out(static_cast<std::size_t>(length), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, text.data(), static_cast<int>(text.size()), out.data(), length);
    return out;
}

std::string utf8FromWide(const wchar_t *text) {
    if (!text) return std::string();
    const int count = static_cast<int>(std::wcslen(text));
    if (count == 0) return std::string();
    const int length = WideCharToMultiByte(CP_UTF8, 0, text, count, nullptr, 0, nullptr, nullptr);
    if (length <= 0) return std::string();
    std::string out(static_cast<std::size_t>(length), '\0');
    WideCharToMultiByte(CP_UTF8, 0, text, count, out.data(), length, nullptr, nullptr);
    return out;
}

bool isInertUri(const std::string &uri) {
    return uri.rfind("about:blank", 0) == 0 || uri.rfind("about:srcdoc", 0) == 0;
}

/* Runs on any thread. Work executes later on the owner thread, in message order. */
void post(Job job) {
    auto *boxed = new Job(std::move(job));
    if (!PostMessageW(gOwner, kTaskMessage, 0, reinterpret_cast<LPARAM>(boxed))) delete boxed;
}

void scheduleCleanup(const std::wstring &path) {
    pendingCleanup().push_back(PendingCleanup{path, kCleanupAttempts});
    SetTimer(gOwner, kCleanupTimer, kCleanupIntervalMs, nullptr);
}

void retryCleanup() {
    auto &queue = pendingCleanup();
    for (auto it = queue.begin(); it != queue.end();) {
        std::error_code error;
        std::filesystem::remove_all(it->path, error);
        const bool gone = !std::filesystem::exists(it->path, error);
        if (gone || --it->attemptsLeft <= 0) {
            it = queue.erase(it);
        } else {
            ++it;
        }
    }
    if (queue.empty()) KillTimer(gOwner, kCleanupTimer);
}

/* Closing goes through WM_CLOSE so the controller closes before the window is destroyed. */
void closeSession(const Session &session) {
    if (session.window) PostMessageW(session.window, WM_CLOSE, 0, 0);
}

void failSession(Session &session) {
    session.failed = true;
    closeSession(session);
}

void failById(jlong id) {
    if (Session *session = findSession(id)) failSession(*session);
}

/* The single place a session ends, whether the user, Java or a failure ended it. */
void onSessionDestroyed(jlong id) {
    auto it = sessions().find(id);
    if (it == sessions().end()) return;
    std::shared_ptr<BrowserCallbacks> callbacks = it->second->callbacks;
    const bool failed = it->second->failed;
    const std::wstring folder = it->second->userDataFolder;
    sessions().erase(it);
    if (!folder.empty()) scheduleCleanup(folder);
    if (callbacks) callbacks->notifyClosed(failed);
}

void layout(const Session &session) {
    if (!session.window) return;
    RECT client{};
    GetClientRect(session.window, &client);
    const int width = client.right - client.left;
    const int height = client.bottom - client.top;
    if (session.finish) MoveWindow(session.finish, 8, 8, 160, 32, TRUE);
    if (session.controller) {
        RECT bounds{0, kTopBarHeight, width, std::max(kTopBarHeight, height)};
        session.controller->put_Bounds(bounds);
    }
}

std::string textOf(const std::function<HRESULT(LPWSTR *)> &getter) {
    LPWSTR value = nullptr;
    std::string result;
    if (SUCCEEDED(getter(&value)) && value) result = utf8FromWide(value);
    CoTaskMemFree(value);
    return result;
}

CookieRow cookieRow(ICoreWebView2Cookie *cookie) {
    BOOL isSession = TRUE;
    double expires = -1.0;
    BOOL secure = FALSE;
    BOOL httpOnly = FALSE;
    COREWEBVIEW2_COOKIE_SAME_SITE_KIND sameSiteKind = COREWEBVIEW2_COOKIE_SAME_SITE_KIND_NONE;
    cookie->get_IsSession(&isSession);
    cookie->get_Expires(&expires);
    cookie->get_IsSecure(&secure);
    cookie->get_IsHttpOnly(&httpOnly);
    cookie->get_SameSite(&sameSiteKind);

    std::string expiry = "-1";
    if (!isSession && expires >= 0) expiry = std::to_string(static_cast<long long>(expires * 1000.0));
    std::string sameSite;
    if (sameSiteKind == COREWEBVIEW2_COOKIE_SAME_SITE_KIND_LAX) sameSite = "Lax";
    if (sameSiteKind == COREWEBVIEW2_COOKIE_SAME_SITE_KIND_STRICT) sameSite = "Strict";

    return CookieRow{
        textOf([&](LPWSTR *out) { return cookie->get_Name(out); }),
        textOf([&](LPWSTR *out) { return cookie->get_Value(out); }),
        textOf([&](LPWSTR *out) { return cookie->get_Domain(out); }),
        textOf([&](LPWSTR *out) { return cookie->get_Path(out); }),
        expiry,
        secure ? "true" : "false",
        httpOnly ? "true" : "false",
        sameSite,
    };
}

void deliverCookies(const std::shared_ptr<BrowserCallbacks> &callbacks, jlong requestId, HRESULT result,
                    ICoreWebView2CookieList *list) {
    std::vector<CookieRow> rows;
    bool ok = SUCCEEDED(result) && list != nullptr;
    UINT count = 0;
    if (ok) ok = SUCCEEDED(list->get_Count(&count)) && count <= kMaxCookieRows;
    std::size_t bytes = 0;
    for (UINT i = 0; ok && i < count; ++i) {
        ComPtr<ICoreWebView2Cookie> cookie;
        ok = SUCCEEDED(list->GetValueAtIndex(i, &cookie)) && cookie.Get() != nullptr;
        if (!ok) break;
        CookieRow row = cookieRow(cookie.Get());
        for (const std::string &field : row) bytes += field.size();
        if (bytes > kMaxCookieBytes) {
            ok = false;
            break;
        }
        rows.push_back(std::move(row));
    }
    callbacks->notifyCookies(requestId, ok ? &rows : nullptr);
}

/* Owner thread only. A request for an origin the attempt was not authorized for is answered as unavailable. */
void requestCookiesOnOwner(jlong id, jlong requestId, const std::string &origin) {
    Session *session = findSession(id);
    if (!session) return;
    std::shared_ptr<BrowserCallbacks> callbacks = session->callbacks;
    if (!contains(session->origins, origin) || !session->cookies) {
        callbacks->notifyCookies(requestId, nullptr);
        return;
    }
    const std::wstring uri = wideFromUtf8(origin + "/");
    session->cookies->GetCookies(
        uri.c_str(),
        Callback<ICoreWebView2GetCookiesCompletedHandler>(
            [callbacks, requestId](HRESULT result, ICoreWebView2CookieList *list) -> HRESULT {
                deliverCookies(callbacks, requestId, result, list);
                return S_OK;
            }).Get());
}

/* Owner thread only. Installs the policy handlers before the first navigation can run. */
void attachController(Session &session, ICoreWebView2Controller *controller) {
    session.controller = controller;
    if (FAILED(controller->get_CoreWebView2(session.webview.GetAddressOf())) || session.webview.Get() == nullptr) {
        failSession(session);
        return;
    }
    layout(session);
    const jlong id = session.id;
    EventRegistrationToken token{};

    session.webview->add_NavigationStarting(
        Callback<ICoreWebView2NavigationStartingEventHandler>(
            [id](ICoreWebView2 *, ICoreWebView2NavigationStartingEventArgs *args) -> HRESULT {
                Session *current = findSession(id);
                if (!current || !args) return S_OK;
                LPWSTR uri = nullptr;
                bool allowed = false;
                if (SUCCEEDED(args->get_Uri(&uri))) {
                    const std::string target = utf8FromWide(uri);
                    if (!current->capturePrefix.empty() &&
                        target.compare(0, current->capturePrefix.size(), current->capturePrefix) == 0) {
                        if (current->callbacks) current->callbacks->notifyNavigation(target);
                        CoTaskMemFree(uri);
                        return args->put_Cancel(TRUE);
                    }
                    const std::string origin = httpsOriginOf(target);
                    allowed = isInertUri(target) || (!origin.empty() && contains(current->origins, origin));
                }
                CoTaskMemFree(uri);
                return args->put_Cancel(allowed ? FALSE : TRUE);
            }).Get(),
        &token);

    session.webview->add_NewWindowRequested(
        Callback<ICoreWebView2NewWindowRequestedEventHandler>(
            [](ICoreWebView2 *, ICoreWebView2NewWindowRequestedEventArgs *args) -> HRESULT {
                /* Handled without creating a window, so a sign-in cannot open a popup outside the origin policy. */
                return args ? args->put_Handled(TRUE) : S_OK;
            }).Get(),
        &token);

    session.webview->add_ProcessFailed(
        Callback<ICoreWebView2ProcessFailedEventHandler>(
            [id](ICoreWebView2 *, ICoreWebView2ProcessFailedEventArgs *) -> HRESULT {
                if (Session *current = findSession(id)) current->failed = true;
                return S_OK;
            }).Get(),
        &token);

    ComPtr<ICoreWebView2_4> webview4;
    if (SUCCEEDED(session.webview.As(&webview4))) {
        webview4->add_DownloadStarting(
            Callback<ICoreWebView2DownloadStartingEventHandler>(
                [](ICoreWebView2 *, ICoreWebView2DownloadStartingEventArgs *args) -> HRESULT {
                    return args ? args->put_Cancel(TRUE) : S_OK;
                }).Get(),
            &token);
    }

    ComPtr<ICoreWebView2_2> webview2;
    if (SUCCEEDED(session.webview.As(&webview2))) webview2->get_CookieManager(session.cookies.GetAddressOf());
    if (session.cookies.Get() == nullptr) {
        failSession(session);
        return;
    }
    session.webview->Navigate(wideFromUtf8(session.startUrl).c_str());
}

/* Owner thread only. Each attempt starts its own environment on a folder that nothing else uses. */
void startEnvironment(jlong id, const std::wstring &folder) {
    const HRESULT started = CreateCoreWebView2EnvironmentWithOptions(
        nullptr, folder.c_str(), nullptr,
        Callback<ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler>(
            [id](HRESULT result, ICoreWebView2Environment *environment) -> HRESULT {
                Session *session = findSession(id);
                if (!session) return S_OK;
                if (FAILED(result) || environment == nullptr) {
                    failSession(*session);
                    return S_OK;
                }
                session->environment = environment;
                const HRESULT requested = environment->CreateCoreWebView2Controller(
                    session->window,
                    Callback<ICoreWebView2CreateCoreWebView2ControllerCompletedHandler>(
                        [id](HRESULT controllerResult, ICoreWebView2Controller *controller) -> HRESULT {
                            Session *attached = findSession(id);
                            if (!attached) {
                                if (controller) controller->Close();
                                return S_OK;
                            }
                            if (FAILED(controllerResult) || controller == nullptr) {
                                failSession(*attached);
                                return S_OK;
                            }
                            attachController(*attached, controller);
                            return S_OK;
                        }).Get());
                if (FAILED(requested)) failSession(*session);
                return S_OK;
            }).Get());
    if (FAILED(started)) failById(id);
}

std::wstring makeUserDataFolder() {
    GUID guid{};
    if (FAILED(CoCreateGuid(&guid))) return std::wstring();
    wchar_t text[64]{};
    if (StringFromGUID2(guid, text, 64) == 0) return std::wstring();
    wchar_t temp[MAX_PATH]{};
    if (GetTempPathW(MAX_PATH, temp) == 0) return std::wstring();
    const std::filesystem::path folder = std::filesystem::path(temp) / (std::wstring(L"ArchiveTune-browser-") + text);
    std::error_code error;
    std::filesystem::create_directories(folder, error);
    return error ? std::wstring() : folder.wstring();
}

/* Owner thread only. Returns the session id, or 0 when the window or its folder cannot be created. */
jlong createSession(const std::string &startUrl, const std::string &title, const std::string &finishText,
                    const std::vector<std::string> &origins, const std::string &capturePrefix,
                    const std::shared_ptr<BrowserCallbacks> &callbacks) {
    const std::wstring folder = makeUserDataFolder();
    if (folder.empty()) return 0;
    const std::wstring titleWide = wideFromUtf8(title);
    const std::wstring finishWide = wideFromUtf8(finishText);

    auto session = std::make_unique<Session>();
    session->id = nextSessionId();
    session->startUrl = startUrl;
    session->origins = origins;
    session->capturePrefix = capturePrefix;
    session->callbacks = callbacks;
    session->userDataFolder = folder;

    HWND window = CreateWindowExW(0, kSessionClass, titleWide.c_str(), WS_OVERLAPPEDWINDOW | WS_VISIBLE,
                                  CW_USEDEFAULT, CW_USEDEFAULT, kWindowWidth, kWindowHeight, nullptr, nullptr,
                                  gModule, nullptr);
    if (!window) {
        std::error_code error;
        std::filesystem::remove_all(folder, error);
        return 0;
    }
    session->window = window;
    const jlong id = session->id;
    SetWindowLongPtrW(window, GWLP_USERDATA, static_cast<LONG_PTR>(id));
    session->finish = CreateWindowExW(
        0, L"BUTTON", finishWide.c_str(), WS_CHILD | WS_VISIBLE | WS_TABSTOP | BS_PUSHBUTTON, 8, 8, 160, 32, window,
        reinterpret_cast<HMENU>(static_cast<INT_PTR>(kFinishButtonId)), gModule, nullptr);
    if (!session->finish) {
        DestroyWindow(window);
        std::error_code error;
        std::filesystem::remove_all(folder, error);
        return 0;
    }

    sessions().emplace(id, std::move(session));
    startEnvironment(id, folder);
    return id;
}

/* The Finish control disables itself at once, so a second press cannot start a second read. */
LRESULT CALLBACK SessionProc(HWND window, UINT message, WPARAM wParam, LPARAM lParam) {
    const jlong id = static_cast<jlong>(GetWindowLongPtrW(window, GWLP_USERDATA));
    switch (message) {
        case WM_SIZE:
            if (Session *session = findSession(id)) layout(*session);
            return 0;
        case WM_COMMAND:
            if (LOWORD(wParam) == kFinishButtonId && HIWORD(wParam) == BN_CLICKED) {
                if (Session *session = findSession(id)) {
                    EnableWindow(session->finish, FALSE);
                    session->callbacks->notifyDone();
                }
                return 0;
            }
            break;
        case WM_CLOSE:
            if (Session *session = findSession(id)) {
                if (session->controller) session->controller->Close();
            }
            DestroyWindow(window);
            return 0;
        case WM_DESTROY:
            onSessionDestroyed(id);
            return 0;
        default:
            break;
    }
    return DefWindowProcW(window, message, wParam, lParam);
}

LRESULT CALLBACK OwnerProc(HWND window, UINT message, WPARAM wParam, LPARAM lParam) {
    if (message == kTaskMessage) {
        std::unique_ptr<Job> job(reinterpret_cast<Job *>(lParam));
        (*job)();
        return 0;
    }
    if (message == WM_TIMER && wParam == kCleanupTimer) {
        retryCleanup();
        return 0;
    }
    return DefWindowProcW(window, message, wParam, lParam);
}

bool registerClasses() {
    WNDCLASSEXW ownerClass{};
    ownerClass.cbSize = sizeof(ownerClass);
    ownerClass.lpfnWndProc = OwnerProc;
    ownerClass.hInstance = gModule;
    ownerClass.lpszClassName = kOwnerClass;

    WNDCLASSEXW sessionClass{};
    sessionClass.cbSize = sizeof(sessionClass);
    sessionClass.lpfnWndProc = SessionProc;
    sessionClass.hInstance = gModule;
    sessionClass.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    sessionClass.hbrBackground = reinterpret_cast<HBRUSH>(static_cast<INT_PTR>(COLOR_WINDOW + 1));
    sessionClass.lpszClassName = kSessionClass;

    return RegisterClassExW(&ownerClass) != 0 && RegisterClassExW(&sessionClass) != 0;
}

/* Anchors the module handle to this library, not to the host JVM executable. */
void moduleAnchor() {}

}  // namespace

extern "C" {

/* Starts the owner thread once. Returns false when COM or the window classes cannot be set up. */
static bool ensureOwner() {
    static std::once_flag once;
    static bool ready = false;
    std::call_once(once, [] {
        auto started = std::make_shared<std::promise<bool>>();
        std::future<bool> result = started->get_future();
        std::thread([started] {
            const HRESULT com = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
            bool ok = SUCCEEDED(com);
            if (ok) {
                ok = GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                                        reinterpret_cast<LPCWSTR>(&moduleAnchor), &gModule) != 0;
            }
            if (ok) ok = registerClasses();
            if (ok) {
                gOwner = CreateWindowExW(0, kOwnerClass, L"", 0, 0, 0, 0, 0, HWND_MESSAGE, nullptr, gModule, nullptr);
                ok = gOwner != nullptr;
            }
            started->set_value(ok);
            if (!ok) {
                if (SUCCEEDED(com)) CoUninitialize();
                return;
            }
            MSG message{};
            while (GetMessageW(&message, nullptr, 0, 0) > 0) {
                TranslateMessage(&message);
                DispatchMessageW(&message);
            }
            CoUninitialize();
        }).detach();
        ready = result.get();
    });
    return ready;
}

JNIEXPORT jlong JNICALL Java_moe_rukamori_archivetune_desktop_integration_auth_NativeCookieBrowser_open(
    JNIEnv *env, jclass, jstring startUrl, jobjectArray origins, jstring title, jstring doneLabel,
    jstring capturePrefix, jobject listener) {
    if (!ensureOwner()) return 0;

    const std::string start = utf8FromJava(env, startUrl);
    const std::string windowTitle = utf8FromJava(env, title);
    const std::string finishText = utf8FromJava(env, doneLabel);
    const std::string capture = utf8FromJava(env, capturePrefix);
    const std::vector<std::string> allowed = readOrigins(env, origins);
    const std::string startOrigin = httpsOriginOf(start);
    if (!allCanonical(allowed) || startOrigin.empty() || !contains(allowed, startOrigin)) return 0;

    auto callbacks = std::make_shared<BrowserCallbacks>(env, listener);
    if (!callbacks->valid()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return 0;
    }

    auto reply = std::make_shared<std::promise<jlong>>();
    std::future<jlong> result = reply->get_future();
    auto ticket = std::make_shared<OpenTicket>();

    post([=]() {
        if (!ticket->begin()) {
            reply->set_value(0);
            return;
        }
        reply->set_value(createSession(start, windowTitle, finishText, allowed, capture, callbacks));
    });

    if (result.wait_for(std::chrono::seconds(kOpenTimeoutSeconds)) == std::future_status::ready) return result.get();
    /* A creation that has not started never will; one that has started answers promptly, so its handle is returned. */
    if (ticket->abandon()) return 0;
    return result.get();
}

JNIEXPORT void JNICALL Java_moe_rukamori_archivetune_desktop_integration_auth_NativeCookieBrowser_requestCookies(
    JNIEnv *env, jclass, jlong handle, jlong requestId, jstring origin) {
    const std::string target = utf8FromJava(env, origin);
    post([=]() { requestCookiesOnOwner(handle, requestId, target); });
}

JNIEXPORT void JNICALL Java_moe_rukamori_archivetune_desktop_integration_auth_NativeCookieBrowser_close(
    JNIEnv *, jclass, jlong handle) {
    post([=]() {
        if (Session *session = findSession(handle)) closeSession(*session);
    });
}

}  // extern "C"
