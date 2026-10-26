/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

/*
 * Linux cookie-only sign-in browser on WebKitGTK 4.1 (GTK 3, libsoup 3).
 *
 * One GTK main loop runs on a thread this library owns. Every GTK, WebKit and cookie call happens on that loop, and
 * the JNI entry points only post work to it, so no GTK object is touched from a Java thread. Each attempt gets its
 * own ephemeral network session, so its cookies never reach the disk or another attempt.
 *
 * Origin policy is enforced here, not in Java. A navigation or response outside the origins the caller authorized
 * is ignored, new windows and downloads are refused, and cookies are read only for an authorized origin. The sign-in
 * completes only when the user presses the Finish button; closing the window cancels the attempt. Java never sees a
 * page URL, only the Finish, close and cookie events.
 *
 * Not compiled or run in this lane. It is built only through the explicit AE_BROWSER option, never by the default
 * build, so a missing WebKitGTK cannot break the playback engine build.
 */

#include <jni.h>

#include <gtk/gtk.h>
#include <libsoup/soup.h>
#include <webkit2/webkit2.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <future>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "browser_callbacks.hpp"

namespace {

using archivetune::browser::allCanonical;
using archivetune::browser::BrowserCallbacks;
using archivetune::browser::CookieRow;
using archivetune::browser::contains;
using archivetune::browser::httpsOriginOf;
using archivetune::browser::javaString;
using archivetune::browser::kMaxCookieBytes;
using archivetune::browser::kMaxCookieRows;
using archivetune::browser::OpenTicket;
using archivetune::browser::readOrigins;
using archivetune::browser::utf8FromJava;

constexpr int kWindowWidth = 960;
constexpr int kWindowHeight = 720;
/* Opening waits for the owner loop, which normally answers at once. The bound keeps a wedged display from hanging Java. */
constexpr int kOpenTimeoutSeconds = 10;

/* Only the owner thread touches the registry, so it needs no lock. */
struct Session {
    jlong id = 0;
    GtkWidget *window = nullptr;
    GtkWidget *finish = nullptr;
    WebKitWebView *view = nullptr;
    WebKitNetworkSession *network = nullptr;
    std::vector<std::string> origins;
    std::shared_ptr<BrowserCallbacks> callbacks;
    bool failed = false;

    ~Session() {
        if (network) g_object_unref(network);
    }
};

using Job = std::function<void()>;

std::map<jlong, std::unique_ptr<Session>> &sessions() {
    static std::map<jlong, std::unique_ptr<Session>> registry;
    return registry;
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

gpointer signalKey(jlong id) {
    return reinterpret_cast<gpointer>(static_cast<intptr_t>(id));
}

jlong keyId(gpointer data) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(data));
}

gboolean runJob(gpointer data) {
    std::unique_ptr<Job> job(static_cast<Job *>(data));
    (*job)();
    return G_SOURCE_REMOVE;
}

/* Queues work for the owner loop from any thread. */
void post(Job job) {
    g_main_context_invoke_full(nullptr, G_PRIORITY_DEFAULT, runJob, new Job(std::move(job)), nullptr);
}

/* Starts the GTK loop once. Returns false when GTK has no display, which callers report as unavailable. */
bool ensureOwner() {
    static std::once_flag once;
    static bool ready = false;
    std::call_once(once, [] {
        auto started = std::make_shared<std::promise<bool>>();
        std::future<bool> result = started->get_future();
        std::thread([started] {
            const bool ok = gtk_init_check(nullptr, nullptr) != FALSE;
            started->set_value(ok);
            if (ok) gtk_main();
        }).detach();
        ready = result.get();
    });
    return ready;
}

/* A payload-free page inside the sign-in window is not a new origin, so the browser may show it. */
bool isInertUri(const char *uri) {
    return uri && (g_str_has_prefix(uri, "about:blank") || g_str_has_prefix(uri, "about:srcdoc"));
}

bool allowedUri(const Session &session, const char *uri) {
    if (isInertUri(uri)) return true;
    if (!uri) return false;
    const std::string origin = httpsOriginOf(uri);
    return !origin.empty() && contains(session.origins, origin);
}

gboolean onDecidePolicy(WebKitWebView *, WebKitPolicyDecision *decision, WebKitPolicyDecisionType type,
                        gpointer data) {
    Session *session = findSession(keyId(data));
    bool allow = false;
    if (session) {
        switch (type) {
            case WEBKIT_POLICY_DECISION_TYPE_NAVIGATION_ACTION: {
                WebKitNavigationAction *action =
                    webkit_navigation_policy_decision_get_navigation_action(WEBKIT_NAVIGATION_POLICY_DECISION(decision));
                const char *uri = webkit_uri_request_get_uri(webkit_navigation_action_get_request(action));
                allow = allowedUri(*session, uri);
                break;
            }
            case WEBKIT_POLICY_DECISION_TYPE_RESPONSE: {
                WebKitResponsePolicyDecision *response = WEBKIT_RESPONSE_POLICY_DECISION(decision);
                const char *uri = webkit_uri_response_get_uri(webkit_response_policy_decision_get_response(response));
                allow = webkit_response_policy_decision_is_mime_type_supported(response) && allowedUri(*session, uri);
                break;
            }
            default:
                /* New windows and downloads are refused, so a sign-in cannot leave the attempt. */
                allow = false;
                break;
        }
    }
    if (allow) {
        webkit_policy_decision_use(decision);
    } else {
        webkit_policy_decision_ignore(decision);
    }
    return TRUE;
}

void onProcessTerminated(WebKitWebView *, WebKitWebProcessTerminationReason, gpointer data) {
    if (Session *session = findSession(keyId(data))) session->failed = true;
}

/* The user's confirmation. The button is disabled at once so a second press cannot start a second read. */
void onFinishClicked(GtkButton *button, gpointer data) {
    Session *session = findSession(keyId(data));
    if (!session) return;
    gtk_widget_set_sensitive(GTK_WIDGET(button), FALSE);
    session->callbacks->notifyDone();
}

/* The window's destroy signal is the single place a session ends, whether the user or Java closed it. */
void onWindowDestroyed(GtkWidget *, gpointer data) {
    auto it = sessions().find(keyId(data));
    if (it == sessions().end()) return;
    std::shared_ptr<BrowserCallbacks> callbacks = it->second->callbacks;
    const bool failed = it->second->failed;
    sessions().erase(it);
    if (callbacks) callbacks->notifyClosed(failed);
}

CookieRow cookieRow(SoupCookie *cookie) {
    auto text = [](const char *value) { return std::string(value ? value : ""); };
    std::string expiry = "-1";
    if (GDateTime *expires = soup_cookie_get_expires(cookie)) {
        expiry = std::to_string(static_cast<long long>(g_date_time_to_unix(expires)) * 1000LL);
    }
    std::string sameSite;
    switch (soup_cookie_get_same_site_policy(cookie)) {
        case SOUP_SAME_SITE_POLICY_LAX:
            sameSite = "Lax";
            break;
        case SOUP_SAME_SITE_POLICY_STRICT:
            sameSite = "Strict";
            break;
        default:
            break;
    }
    return CookieRow{
        text(soup_cookie_get_name(cookie)),
        text(soup_cookie_get_value(cookie)),
        text(soup_cookie_get_domain(cookie)),
        text(soup_cookie_get_path(cookie)),
        expiry,
        soup_cookie_get_secure(cookie) ? "true" : "false",
        soup_cookie_get_http_only(cookie) ? "true" : "false",
        sameSite,
    };
}

struct CookieRequest {
    std::shared_ptr<BrowserCallbacks> callbacks;
    jlong requestId = 0;
    WebKitCookieManager *manager = nullptr;
};

void onCookiesReady(GObject *source, GAsyncResult *result, gpointer data) {
    std::unique_ptr<CookieRequest> request(static_cast<CookieRequest *>(data));
    GError *error = nullptr;
    GList *cookies = webkit_cookie_manager_get_cookies_finish(WEBKIT_COOKIE_MANAGER(source), result, &error);
    bool ok = error == nullptr;
    g_clear_error(&error);

    std::vector<CookieRow> rows;
    std::size_t bytes = 0;
    for (GList *node = cookies; ok && node; node = node->next) {
        CookieRow row = cookieRow(static_cast<SoupCookie *>(node->data));
        for (const std::string &field : row) bytes += field.size();
        if (rows.size() >= kMaxCookieRows || bytes > kMaxCookieBytes) {
            ok = false;
            break;
        }
        rows.push_back(std::move(row));
    }
    g_list_free_full(cookies, reinterpret_cast<GDestroyNotify>(soup_cookie_free));
    g_object_unref(request->manager);
    request->callbacks->notifyCookies(request->requestId, ok ? &rows : nullptr);
}

/* Owner thread only. Builds the window, its Finish control and the policy-enforcing web view. */
jlong createSession(const std::string &startUrl, const std::string &title, const std::string &finishText,
                    const std::vector<std::string> &origins, const std::shared_ptr<BrowserCallbacks> &callbacks) {
    try {
        auto session = std::make_unique<Session>();
        session->id = nextSessionId();
        session->origins = origins;
        session->callbacks = callbacks;
        /* An ephemeral network session keeps cookies in memory only and never shares them with another attempt. */
        session->network = webkit_network_session_new_ephemeral();
        session->window = gtk_window_new(GTK_WINDOW_TOPLEVEL);
        gtk_window_set_title(GTK_WINDOW(session->window), title.c_str());
        gtk_window_set_default_size(GTK_WINDOW(session->window), kWindowWidth, kWindowHeight);

        GtkWidget *layout = gtk_box_new(GTK_ORIENTATION_VERTICAL, 0);
        session->finish = gtk_button_new_with_label(finishText.c_str());
        gtk_box_pack_start(GTK_BOX(layout), session->finish, FALSE, FALSE, 0);
        session->view = WEBKIT_WEB_VIEW(
            g_object_new(WEBKIT_TYPE_WEB_VIEW, "network-session", session->network, nullptr));
        gtk_box_pack_start(GTK_BOX(layout), GTK_WIDGET(session->view), TRUE, TRUE, 0);
        gtk_container_add(GTK_CONTAINER(session->window), layout);

        const jlong id = session->id;
        gpointer key = signalKey(id);
        g_signal_connect(session->view, "decide-policy", G_CALLBACK(onDecidePolicy), key);
        g_signal_connect(session->view, "web-process-terminated", G_CALLBACK(onProcessTerminated), key);
        g_signal_connect(session->finish, "clicked", G_CALLBACK(onFinishClicked), key);
        g_signal_connect(session->window, "destroy", G_CALLBACK(onWindowDestroyed), key);

        Session *registered = session.get();
        sessions().emplace(id, std::move(session));
        webkit_web_view_load_uri(registered->view, startUrl.c_str());
        gtk_widget_show_all(registered->window);
        return id;
    } catch (...) {
        return 0;
    }
}

/*
 * The Linux file chooser shares this owner loop with the sign-in windows, so the process never has a second GTK owner.
 * Java never creates a GTK object: it starts a ticket, waits on it in bounded slices and may cancel it. The owner
 * builds, shows and answers the dialog, and a cancel never blocks its caller.
 */
constexpr std::size_t kMaxPickedFiles = 4096;
constexpr jlong kMaxPickPollMillis = 60000;

struct PickSpec {
    bool folder = false;
    bool multiple = false;
    std::string title;
    std::string acceptLabel;
    std::string cancelLabel;
};

/* The owner thread builds and answers the dialog. A Java thread only waits on the answer or sets the cancel flag. */
struct PickTicket {
    std::mutex mutex;
    std::condition_variable answered;
    bool done = false;               /* guarded by mutex */
    std::string failure;             /* guarded by mutex */
    std::vector<std::string> files;  /* guarded by mutex */
    std::atomic<bool> cancelled{false};
    GtkWidget *dialog = nullptr;     /* owner thread only */
};

std::mutex &pickerMutex() {
    static std::mutex mutex;
    return mutex;
}

/* Guarded by pickerMutex(). A ticket leaves the registry once its answer is read or it is cancelled. */
std::map<jlong, std::shared_ptr<PickTicket>> &pickers() {
    static std::map<jlong, std::shared_ptr<PickTicket>> registry;
    return registry;
}

jlong &nextPickerId() {
    static jlong next = 1;
    return next;
}

std::shared_ptr<PickTicket> findPicker(jlong id) {
    std::lock_guard<std::mutex> lock(pickerMutex());
    auto it = pickers().find(id);
    return it == pickers().end() ? nullptr : it->second;
}

void forgetPicker(jlong id) {
    std::lock_guard<std::mutex> lock(pickerMutex());
    pickers().erase(id);
}

/* The first answer wins, so a cancel that races a selection cannot report both. */
void answerPicker(PickTicket &ticket, std::vector<std::string> files, std::string failure) {
    {
        std::lock_guard<std::mutex> lock(ticket.mutex);
        if (ticket.done) return;
        ticket.done = true;
        ticket.files = std::move(files);
        ticket.failure = std::move(failure);
    }
    ticket.answered.notify_all();
}

/* The response signal owns a copy of the ticket pointer; GLib releases that copy when the dialog's closure goes. */
void releasePickerHolder(gpointer data, GClosure *) {
    delete static_cast<std::shared_ptr<PickTicket> *>(data);
}

/* Owner thread. Only an accepted response carries files; every other response is a cancellation. */
void onPickerResponse(GtkDialog *dialog, gint response, gpointer data) {
    std::shared_ptr<PickTicket> ticket = *static_cast<std::shared_ptr<PickTicket> *>(data);
    std::vector<std::string> files;
    std::string failure;
    if (response == GTK_RESPONSE_ACCEPT) {
        GSList *selected = gtk_file_chooser_get_filenames(GTK_FILE_CHOOSER(dialog));
        for (GSList *node = selected; node && failure.empty(); node = node->next) {
            if (!node->data) continue;
            if (files.size() >= kMaxPickedFiles) {
                failure = "Too many files were selected";
                break;
            }
            files.emplace_back(static_cast<const char *>(node->data));
        }
        g_slist_free_full(selected, g_free);
    }
    ticket->dialog = nullptr;
    answerPicker(*ticket, std::move(files), std::move(failure));
    gtk_widget_destroy(GTK_WIDGET(dialog));
}

/* Owner thread. A cancel that arrived first creates nothing, so no chooser appears without a caller to answer it. */
void openPicker(const std::shared_ptr<PickTicket> &ticket, const PickSpec &spec) {
    try {
        if (ticket->cancelled.load()) {
            answerPicker(*ticket, {}, {});
            return;
        }
        GtkWidget *dialog = gtk_file_chooser_dialog_new(
            spec.title.c_str(), nullptr,
            spec.folder ? GTK_FILE_CHOOSER_ACTION_SELECT_FOLDER : GTK_FILE_CHOOSER_ACTION_OPEN, nullptr);
        if (!dialog) {
            answerPicker(*ticket, {}, "The file chooser could not be created");
            return;
        }
        gtk_dialog_add_button(GTK_DIALOG(dialog), spec.cancelLabel.c_str(), GTK_RESPONSE_CANCEL);
        gtk_dialog_add_button(GTK_DIALOG(dialog), spec.acceptLabel.c_str(), GTK_RESPONSE_ACCEPT);
        gtk_file_chooser_set_select_multiple(GTK_FILE_CHOOSER(dialog), spec.multiple ? TRUE : FALSE);
        ticket->dialog = dialog;
        auto *holder = new std::shared_ptr<PickTicket>(ticket);
        g_signal_connect_data(dialog, "response", G_CALLBACK(onPickerResponse), holder, releasePickerHolder,
                              static_cast<GConnectFlags>(0));
        gtk_widget_show_all(dialog);
    } catch (...) {
        answerPicker(*ticket, {}, "The file chooser could not be opened");
    }
}

void throwIllegalState(JNIEnv *env, const char *message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (!type) return;
    env->ThrowNew(type, message);
    env->DeleteLocalRef(type);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_moe_rukamori_archivetune_desktop_integration_auth_NativeCookieBrowser_open(
    JNIEnv *env, jclass, jstring startUrl, jobjectArray origins, jstring title, jstring doneLabel, jobject listener) {
    if (!ensureOwner()) return 0;

    const std::string start = utf8FromJava(env, startUrl);
    const std::string windowTitle = utf8FromJava(env, title);
    const std::string finishText = utf8FromJava(env, doneLabel);
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
        reply->set_value(createSession(start, windowTitle, finishText, allowed, callbacks));
    });

    if (result.wait_for(std::chrono::seconds(kOpenTimeoutSeconds)) == std::future_status::ready) return result.get();
    /* A creation that has not started never will; one that has started answers promptly, so its handle is returned. */
    if (ticket->abandon()) return 0;
    return result.get();
}

JNIEXPORT void JNICALL Java_moe_rukamori_archivetune_desktop_integration_auth_NativeCookieBrowser_requestCookies(
    JNIEnv *env, jclass, jlong handle, jlong requestId, jstring origin) {
    const std::string target = utf8FromJava(env, origin);
    post([=]() {
        Session *session = findSession(handle);
        if (!session) return;
        if (!contains(session->origins, target)) {
            session->callbacks->notifyCookies(requestId, nullptr);
            return;
        }
        WebKitCookieManager *manager = webkit_network_session_get_cookie_manager(session->network);
        auto *request = new CookieRequest{session->callbacks, requestId, WEBKIT_COOKIE_MANAGER(g_object_ref(manager))};
        const std::string uri = target + "/";
        webkit_cookie_manager_get_cookies(manager, uri.c_str(), nullptr, onCookiesReady, request);
    });
}

JNIEXPORT void JNICALL Java_moe_rukamori_archivetune_desktop_integration_auth_NativeCookieBrowser_close(
    JNIEnv *, jclass, jlong handle) {
    post([=]() {
        Session *session = findSession(handle);
        /* Destroying the window emits the destroy signal, which ends the session and reports closed. */
        if (session) gtk_widget_destroy(session->window);
    });
}

/* Starts a chooser on the owner loop and returns its ticket, or 0 when GTK has no display. Never blocks on the dialog. */
JNIEXPORT jlong JNICALL Java_moe_rukamori_archivetune_desktop_integration_NativeGtkFilePicker_start(
    JNIEnv *env, jclass, jboolean folder, jboolean multiple, jstring title, jstring acceptLabel, jstring cancelLabel) {
    if (!ensureOwner()) return 0;

    PickSpec spec;
    spec.folder = folder == JNI_TRUE;
    spec.multiple = multiple == JNI_TRUE;
    spec.title = utf8FromJava(env, title);
    spec.acceptLabel = utf8FromJava(env, acceptLabel);
    spec.cancelLabel = utf8FromJava(env, cancelLabel);

    auto ticket = std::make_shared<PickTicket>();
    jlong id = 0;
    {
        std::lock_guard<std::mutex> lock(pickerMutex());
        id = nextPickerId()++;
        pickers().emplace(id, ticket);
    }
    post([ticket, spec]() { openPicker(ticket, spec); });
    return id;
}

/*
 * Null while the chooser is still open after timeoutMillis, so the caller can check for its own cancellation. An empty
 * array is a cancelled or empty selection. A failed chooser throws, so a caller never mistakes it for a cancel.
 */
JNIEXPORT jobjectArray JNICALL Java_moe_rukamori_archivetune_desktop_integration_NativeGtkFilePicker_await(
    JNIEnv *env, jclass, jlong id, jlong timeoutMillis) {
    std::shared_ptr<PickTicket> ticket = findPicker(id);
    if (!ticket) {
        throwIllegalState(env, "Unknown file chooser ticket");
        return nullptr;
    }
    const auto wait = std::chrono::milliseconds(std::clamp<jlong>(timeoutMillis, 0, kMaxPickPollMillis));
    std::unique_lock<std::mutex> lock(ticket->mutex);
    if (!ticket->answered.wait_for(lock, wait, [&ticket] { return ticket->done; })) return nullptr;
    const std::string failure = ticket->failure;
    const std::vector<std::string> files = ticket->files;
    lock.unlock();
    forgetPicker(id);

    if (!failure.empty()) {
        throwIllegalState(env, failure.c_str());
        return nullptr;
    }
    jclass stringType = env->FindClass("java/lang/String");
    if (!stringType) return nullptr;
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(files.size()), stringType, nullptr);
    for (std::size_t i = 0; result && i < files.size(); ++i) {
        jstring path = javaString(env, files[i]);
        env->SetObjectArrayElement(result, static_cast<jsize>(i), path);
        env->DeleteLocalRef(path);
    }
    env->DeleteLocalRef(stringType);
    return result;
}

/* Non-blocking. The caller stops waiting at once; the owner closes the dialog without answering anyone else. */
JNIEXPORT void JNICALL Java_moe_rukamori_archivetune_desktop_integration_NativeGtkFilePicker_cancel(
    JNIEnv *, jclass, jlong id) {
    std::shared_ptr<PickTicket> ticket = findPicker(id);
    if (!ticket) return;
    forgetPicker(id);
    ticket->cancelled.store(true);
    post([ticket]() {
        if (ticket->dialog) {
            gtk_dialog_response(GTK_DIALOG(ticket->dialog), GTK_RESPONSE_CANCEL);
        } else {
            answerPicker(*ticket, {}, {});
        }
    });
}

}  // extern "C"
