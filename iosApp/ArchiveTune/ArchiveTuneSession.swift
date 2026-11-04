import UIKit
import ArchiveTuneShared

/// One application-owned graph. Controller detach, recreation and backgrounding never release its services.
final class ArchiveTuneSession {
    static let shared = ArchiveTuneSession()

    private let host = IosHost()
    private var observers: [NSObjectProtocol] = []
    private var presenter: AnyObject?
    private var closed = false
    private lazy var rootController: UIViewController = {
        if #available(iOS 26.0, *) {
            return NativeChromeController(compose: host.viewController, chrome: host.nativeChrome)
        }
        return host.viewController
    }()

    private init() {
        NSSetUncaughtExceptionHandler { exception in
            IosCrashHooksKt.iosCaptureNSException(
                name: exception.name.rawValue,
                reason: exception.reason,
                callStack: exception.callStackSymbols
            )
        }
        // Launch-time registration precedes the first activation and the pack's foreground consideration.
        observers.append(NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main
        ) { [host] _ in
            host.foregrounded()
        })
        observers.append(NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main
        ) { [host] _ in
            host.backgrounded()
        })
        observers.append(NotificationCenter.default.addObserver(
            forName: UIApplication.willTerminateNotification, object: nil, queue: .main
        ) { [weak self] _ in
            self?.shutdown()
        })
        if #available(iOS 26.0, *) {
            host.setNativeLyricsFactory { lyrics in SystemLyrics.makeController(lyrics) }
        }
    }

    /// Re-presents the same UIKit container and Compose child, never a second host or per-tab controller.
    func present(for token: AnyObject) -> UIViewController {
        let controller = rootController
        if let stale = presenter, stale !== token {
            controller.willMove(toParent: nil)
            controller.view.removeFromSuperview()
            controller.removeFromParent()
        }
        presenter = token
        return controller
    }

    /// A stale dismantle never clears a newer representable's claim.
    func dismiss(for token: AnyObject) {
        if presenter === token { presenter = nil }
    }

    /// Both scheme opens and browsing activities use Kotlin's existing bounded link intake and classifier.
    /// HTTPS activation still needs associated domains; forwarding an activity grants no entitlement.
    func accept(_ url: URL) {
        host.openLink(link: url.absoluteString)
    }

    func shutdown() {
        guard !closed else { return }
        closed = true
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        observers.removeAll()
        presenter = nil
        if #available(iOS 26.0, *), let chrome = rootController as? NativeChromeController { chrome.close() }
        host.close()
    }
}
