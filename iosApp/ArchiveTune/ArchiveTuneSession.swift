import UIKit
import ArchiveTuneShared

/// One application-owned graph. Controller detach, recreation and backgrounding never release its services.
final class ArchiveTuneSession {
    static let shared = ArchiveTuneSession()

    private let host = IosHost()
    private var observers: [NSObjectProtocol] = []
    private var presenter: AnyObject?
    private var closed = false
    private lazy var rootController: UIViewController = IosRootController(
        compose: host.viewController,
        safeArea: host.safeArea,
    )

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
        host.close()
    }
}

/// The one OS bridge the shared app needs here: the window's safe-area insets for a full-bleed Compose controller.
/// It draws no content of its own, so the shared Compose tree stays the only UI owner on iOS.
final class IosRootController: UIViewController {
    private let compose: UIViewController
    private let safeArea: IosSafeArea

    init(compose: UIViewController, safeArea: IosSafeArea) {
        self.compose = compose
        self.safeArea = safeArea
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override var childForStatusBarStyle: UIViewController? { compose }
    override var childForStatusBarHidden: UIViewController? { compose }

    override func viewDidLoad() {
        super.viewDidLoad()
        // The shared theme paints the whole window, strips included; a system colour here would show through as a
        // white or light strip around a dark or tinted palette.
        view.backgroundColor = .clear
        addChild(compose)
        compose.view.frame = view.bounds
        compose.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(compose.view)
        compose.didMove(toParent: self)
    }

    override func viewSafeAreaInsetsDidChange() {
        super.viewSafeAreaInsetsDidChange()
        reportSafeArea()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        reportSafeArea()
    }

    private func reportSafeArea() {
        let insets = view.safeAreaInsets
        // Kotlin exports the parameters in declaration order, and Swift passes labelled arguments in that order.
        safeArea.update(left: Double(insets.left), top: Double(insets.top),
                        right: Double(insets.right), bottom: Double(insets.bottom))
    }
}
