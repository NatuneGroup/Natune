/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

/*
 * macOS cookie-only sign-in browser on WKWebView.
 *
 * AppKit and WebKit calls run on the main thread. The JNI entry points dispatch work to the main dispatch queue and
 * wait a bounded time for the answer. That queue drains only while the process main thread runs an AppKit loop, so
 * the desktop host must keep one; this is a requirement of the design and is not verified in this lane. Each attempt
 * uses a non-persistent data store, so its cookies stay in memory and are released when its window closes.
 *
 * The navigation delegate cancels every navigation and response outside the authorized origins, the UI delegate
 * refuses popups, and cookies are read only for an authorized origin. The sign-in completes only when the user
 * presses the Finish button; closing the window cancels the attempt.
 *
 * Not compiled or run in this lane. It is built only through the explicit AE_BROWSER option.
 */

#include <jni.h>

#import <AppKit/AppKit.h>
#import <Foundation/Foundation.h>
#import <WebKit/WebKit.h>

#include <dispatch/dispatch.h>

#include <atomic>
#include <chrono>
#include <cstddef>
#include <future>
#include <memory>
#include <string>
#include <vector>

#include "browser_callbacks.hpp"

using archivetune::browser::allCanonical;
using archivetune::browser::BrowserCallbacks;
using archivetune::browser::CookieRow;
using archivetune::browser::contains;
using archivetune::browser::cookieDomainApplies;
using archivetune::browser::hostOfOrigin;
using archivetune::browser::httpsOriginOf;
using archivetune::browser::kMaxCookieBytes;
using archivetune::browser::kMaxCookieRows;
using archivetune::browser::OpenTicket;
using archivetune::browser::readOrigins;
using archivetune::browser::utf8FromJava;

namespace {

constexpr CGFloat kWindowWidth = 960;
constexpr CGFloat kWindowHeight = 720;
constexpr CGFloat kTopBarHeight = 48;
constexpr int kOpenTimeoutSeconds = 10;
/* A challenge document is a small page; the bound keeps a caller from holding an unbounded string in the window. */
constexpr std::size_t kMaxHtmlBytes = 256 * 1024;

/* Main-thread state for one attempt. The callbacks are shared with the cookie completion blocks. */
struct Record {
    std::shared_ptr<BrowserCallbacks> callbacks;
    std::vector<std::string> origins;
    /* When set, a navigation to it is reported through onNavigate and cancelled, like shouldOverrideUrlLoading. */
    std::string capturePrefix;
    bool failed = false;
};

jlong nextSessionId() {
    static jlong next = 1;
    return next++;
}

std::string stdString(NSString *value) {
    const char *raw = value.UTF8String;
    return raw ? std::string(raw) : std::string();
}

bool isInertUri(const std::string &uri) {
    return uri.rfind("about:blank", 0) == 0 || uri.rfind("about:srcdoc", 0) == 0;
}

CookieRow cookieRow(NSHTTPCookie *cookie) {
    std::string expiry = "-1";
    if (cookie.expiresDate) {
        expiry = std::to_string(static_cast<long long>([cookie.expiresDate timeIntervalSince1970] * 1000.0));
    }
    std::string sameSite;
    NSString *policy = cookie.properties[NSHTTPCookieSameSitePolicy];
    if ([policy isEqualToString:NSHTTPCookieSameSiteLax]) sameSite = "Lax";
    if ([policy isEqualToString:NSHTTPCookieSameSiteStrict]) sameSite = "Strict";
    return CookieRow{
        stdString(cookie.name),
        stdString(cookie.value),
        stdString(cookie.domain),
        stdString(cookie.path),
        expiry,
        [cookie isSecure] ? "true" : "false",
        [cookie isHTTPOnly] ? "true" : "false",
        sameSite,
    };
}

}  // namespace

/* Owns the window, web view and delegate roles for one attempt. It is only used on the main thread. */
@interface ArchiveTuneBrowserSession : NSObject <NSWindowDelegate, WKNavigationDelegate, WKUIDelegate> {
    std::shared_ptr<Record> _record;
}
@property (nonatomic, assign) jlong sessionId;
@property (nonatomic, strong) NSWindow *window;
@property (nonatomic, strong) WKWebView *webView;
@property (nonatomic, strong) NSButton *finishButton;
- (instancetype)initWithId:(jlong)sessionId record:(std::shared_ptr<Record>)record;
- (Record *)recordPointer;
- (void)finishClicked:(id)sender;
@end

namespace {

NSMutableDictionary<NSNumber *, ArchiveTuneBrowserSession *> *registry() {
    static NSMutableDictionary<NSNumber *, ArchiveTuneBrowserSession *> *sessions = [NSMutableDictionary dictionary];
    return sessions;
}

}  // namespace

@implementation ArchiveTuneBrowserSession

- (instancetype)initWithId:(jlong)sessionId record:(std::shared_ptr<Record>)record {
    self = [super init];
    if (self) {
        _sessionId = sessionId;
        _record = std::move(record);
    }
    return self;
}

- (Record *)recordPointer {
    return _record.get();
}

/* The user's confirmation. The button is disabled at once so a second press cannot start a second read. */
- (void)finishClicked:(id)sender {
    self.finishButton.enabled = NO;
    if (Record *record = _record.get()) {
        if (record->callbacks) record->callbacks->notifyDone();
    }
}

- (BOOL)allowsURL:(NSURL *)url {
    const char *raw = url.absoluteString.UTF8String;
    if (!raw) return NO;
    const std::string target(raw);
    Record *record = _record.get();
    if (!record) return NO;
    if (isInertUri(target)) return YES;
    const std::string origin = httpsOriginOf(target);
    return !origin.empty() && contains(record->origins, origin) ? YES : NO;
}

- (void)webView:(WKWebView *)webView
    decidePolicyForNavigationAction:(WKNavigationAction *)navigationAction
                    decisionHandler:(void (^)(WKNavigationActionPolicy))decisionHandler {
    const char *raw = navigationAction.request.URL.absoluteString.UTF8String;
    const std::string target = raw ? raw : "";
    Record *record = _record.get();
    if (record && !record->capturePrefix.empty() &&
        target.compare(0, record->capturePrefix.size(), record->capturePrefix) == 0) {
        if (record->callbacks) record->callbacks->notifyNavigation(target);
        decisionHandler(WKNavigationActionPolicyCancel);
        return;
    }
    decisionHandler([self allowsURL:navigationAction.request.URL] ? WKNavigationActionPolicyAllow
                                                                  : WKNavigationActionPolicyCancel);
}

- (void)webView:(WKWebView *)webView
    decidePolicyForNavigationResponse:(WKNavigationResponse *)navigationResponse
                      decisionHandler:(void (^)(WKNavigationResponsePolicy))decisionHandler {
    const BOOL allow = navigationResponse.canShowMIMEType && [self allowsURL:navigationResponse.response.URL];
    decisionHandler(allow ? WKNavigationResponsePolicyAllow : WKNavigationResponsePolicyCancel);
}

- (void)webViewWebContentProcessDidTerminate:(WKWebView *)webView {
    if (Record *record = _record.get()) record->failed = true;
}

/* Popups are refused: returning nil creates no window, so a sign-in cannot leave the attempt. */
- (WKWebView *)webView:(WKWebView *)webView
    createWebViewWithConfiguration:(WKWebViewConfiguration *)configuration
               forNavigationAction:(WKNavigationAction *)navigationAction
                    windowFeatures:(WKWindowFeatures *)windowFeatures {
    return nil;
}

- (void)windowWillClose:(NSNotification *)notification {
    /* Keep this object alive until the callback has run, even if the registry is its last owner. */
    ArchiveTuneBrowserSession *keepAlive = self;
    [registry() removeObjectForKey:@(self.sessionId)];
    [self.webView stopLoading];
    self.webView.navigationDelegate = nil;
    if (Record *record = _record.get()) {
        if (record->callbacks) record->callbacks->notifyClosed(record->failed);
    }
    (void)keepAlive;
}

@end

namespace {

/* Main thread only. Builds the window and its web view, then starts the first navigation. */
jlong openOnMain(const std::shared_ptr<Record> &record, const std::string &start, const std::string &title,
                 const std::string &finishText, const std::string &html = std::string()) {
    NSURL *startURL = [NSURL URLWithString:[NSString stringWithUTF8String:start.c_str()]];
    if (!startURL) return 0;

    const jlong sessionId = nextSessionId();
    ArchiveTuneBrowserSession *session = [[ArchiveTuneBrowserSession alloc] initWithId:sessionId record:record];

    const NSWindowStyleMask style = NSWindowStyleMaskTitled | NSWindowStyleMaskClosable |
                                    NSWindowStyleMaskResizable | NSWindowStyleMaskMiniaturizable;
    NSWindow *window = [[NSWindow alloc] initWithContentRect:NSMakeRect(0, 0, kWindowWidth, kWindowHeight)
                                                   styleMask:style
                                                     backing:NSBackingStoreBuffered
                                                       defer:NO];
    window.releasedWhenClosed = NO;
    window.title = [NSString stringWithUTF8String:title.c_str()];
    window.delegate = session;

    WKWebViewConfiguration *configuration = [[WKWebViewConfiguration alloc] init];
    /* A non-persistent store keeps this attempt's cookies in memory and shares nothing with another attempt. */
    configuration.websiteDataStore = [WKWebsiteDataStore nonPersistentDataStore];

    NSView *content = window.contentView;
    const NSRect bounds = content.bounds;
    WKWebView *webView = [[WKWebView alloc]
        initWithFrame:NSMakeRect(0, 0, NSWidth(bounds), NSHeight(bounds) - kTopBarHeight)
        configuration:configuration];
    webView.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    webView.navigationDelegate = session;
    webView.UIDelegate = session;

    NSButton *finishButton = [NSButton buttonWithTitle:[NSString stringWithUTF8String:finishText.c_str()]
                                                target:session
                                                action:@selector(finishClicked:)];
    finishButton.frame = NSMakeRect(8, NSHeight(bounds) - 40, 160, 32);
    finishButton.autoresizingMask = NSViewMinYMargin;

    [content addSubview:webView];
    [content addSubview:finishButton];
    session.window = window;
    session.webView = webView;
    session.finishButton = finishButton;
    [registry() setObject:session forKey:@(sessionId)];

    [window makeKeyAndOrderFront:nil];
    [NSApp activateIgnoringOtherApps:YES];
    if (html.empty()) {
        [webView loadRequest:[NSURLRequest requestWithURL:startURL]];
    } else {
        /* The base URL is the document's origin, so a page whose script is domain-locked to it runs on that origin. */
        [webView loadHTMLString:[NSString stringWithUTF8String:html.c_str()] baseURL:startURL];
    }
    return sessionId;
}

/* Main thread only. Closing the window runs windowWillClose, which ends the session and reports closed. */
void closeOnMain(jlong sessionId) {
    ArchiveTuneBrowserSession *session = registry()[@(sessionId)];
    [session.window close];
}

/* Main thread only. A request for an origin the attempt was not authorized for is answered as unavailable. */
void requestCookiesOnMain(jlong sessionId, jlong requestId, const std::string &origin) {
    ArchiveTuneBrowserSession *session = registry()[@(sessionId)];
    if (!session) return;
    Record *record = [session recordPointer];
    if (!record || !record->callbacks) return;
    std::shared_ptr<BrowserCallbacks> callbacks = record->callbacks;
    if (!contains(record->origins, origin)) {
        callbacks->notifyCookies(requestId, nullptr);
        return;
    }
    const std::string host = hostOfOrigin(origin);
    WKHTTPCookieStore *store = session.webView.configuration.websiteDataStore.httpCookieStore;
    [store getAllCookies:^(NSArray<NSHTTPCookie *> *cookies) {
        /* The store belongs to this attempt alone, so filtering by the origin host yields its cookies only. */
        std::vector<CookieRow> rows;
        bool ok = true;
        std::size_t bytes = 0;
        for (NSHTTPCookie *cookie in cookies) {
            if (!cookieDomainApplies(host, stdString(cookie.domain))) continue;
            if (rows.size() >= kMaxCookieRows) {
                ok = false;
                break;
            }
            CookieRow row = cookieRow(cookie);
            for (const std::string &field : row) bytes += field.size();
            if (bytes > kMaxCookieBytes) {
                ok = false;
                break;
            }
            rows.push_back(std::move(row));
        }
        callbacks->notifyCookies(requestId, ok ? &rows : nullptr);
    }];
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_moe_rukamori_archivetune_desktop_integration_auth_NativeCookieBrowser_open(
    JNIEnv *env, jclass, jstring startUrl, jobjectArray origins, jstring title, jstring doneLabel,
    jstring capturePrefix, jobject listener) {
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

    auto record = std::make_shared<Record>();
    record->callbacks = callbacks;
    record->origins = allowed;
    record->capturePrefix = capture;

    auto reply = std::make_shared<std::promise<jlong>>();
    std::future<jlong> result = reply->get_future();
    auto ticket = std::make_shared<OpenTicket>();

    dispatch_async(dispatch_get_main_queue(), ^{
        if (!ticket->begin()) {
            reply->set_value(0);
            return;
        }
        reply->set_value(openOnMain(record, start, windowTitle, finishText));
    });

    if (result.wait_for(std::chrono::seconds(kOpenTimeoutSeconds)) == std::future_status::ready) return result.get();
    /* A creation that has not started never will; one that has started answers promptly, so its handle is returned. */
    if (ticket->abandon()) return 0;
    return result.get();
}

/*
 * The challenge window: the document is [documentUrl]'s origin but its bytes are [html], so a script that is
 * domain-locked to the sign-in origin runs there. The policy, the capture prefix and the cookie reads are the same
 * ones `open` enforces.
 */
JNIEXPORT jlong JNICALL Java_moe_rukamori_archivetune_desktop_integration_auth_NativeCookieBrowser_openHtml(
    JNIEnv *env, jclass, jstring documentUrl, jstring html, jobjectArray origins, jstring title, jstring doneLabel,
    jstring capturePrefix, jobject listener) {
    const std::string document = utf8FromJava(env, documentUrl);
    const std::string content = utf8FromJava(env, html);
    const std::string windowTitle = utf8FromJava(env, title);
    const std::string finishText = utf8FromJava(env, doneLabel);
    const std::string capture = utf8FromJava(env, capturePrefix);
    const std::vector<std::string> allowed = readOrigins(env, origins);
    const std::string documentOrigin = httpsOriginOf(document);
    if (content.empty() || content.size() > kMaxHtmlBytes) return 0;
    if (!allCanonical(allowed) || documentOrigin.empty() || !contains(allowed, documentOrigin)) return 0;

    auto callbacks = std::make_shared<BrowserCallbacks>(env, listener);
    if (!callbacks->valid()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return 0;
    }

    auto record = std::make_shared<Record>();
    record->callbacks = callbacks;
    record->origins = allowed;
    record->capturePrefix = capture;

    auto reply = std::make_shared<std::promise<jlong>>();
    std::future<jlong> result = reply->get_future();
    auto ticket = std::make_shared<OpenTicket>();

    dispatch_async(dispatch_get_main_queue(), ^{
        if (!ticket->begin()) {
            reply->set_value(0);
            return;
        }
        reply->set_value(openOnMain(record, document, windowTitle, finishText, content));
    });

    if (result.wait_for(std::chrono::seconds(kOpenTimeoutSeconds)) == std::future_status::ready) return result.get();
    if (ticket->abandon()) return 0;
    return result.get();
}

JNIEXPORT void JNICALL Java_moe_rukamori_archivetune_desktop_integration_auth_NativeCookieBrowser_requestCookies(
    JNIEnv *env, jclass, jlong handle, jlong requestId, jstring origin) {
    const std::string target = utf8FromJava(env, origin);
    dispatch_async(dispatch_get_main_queue(), ^{
        requestCookiesOnMain(handle, requestId, target);
    });
}

JNIEXPORT void JNICALL Java_moe_rukamori_archivetune_desktop_integration_auth_NativeCookieBrowser_close(
    JNIEnv *, jclass, jlong handle) {
    dispatch_async(dispatch_get_main_queue(), ^{
        closeOnMain(handle);
    });
}

}  // extern "C"
