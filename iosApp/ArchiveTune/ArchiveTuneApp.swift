/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
import SwiftUI
import UIKit
import ArchiveTuneShared

@main
struct ArchiveTuneApp: App {
    init() {
        // Launch-time creation registers the foreground observer before the first activation can happen.
        _ = ArchiveTuneSession.shared
    }

    var body: some Scene {
        WindowGroup {
            AppRoot()
                .onOpenURL { url in
                    // Custom scheme and invite links; the host classifies, routes and reports what it cannot open.
                    ArchiveTuneSession.shared.accept(url)
                }
        }
    }
}

/// The application-owned graph: one IosHost per process (settings, vault, Japanese pack, challenges, player,
/// credentials, files, browsers). SwiftUI may detach and recreate the controller freely; only the final
/// process shutdown closes the graph, so a view rebuild never releases the settings owner.
final class ArchiveTuneSession {
    static let shared = ArchiveTuneSession()

    private let host = IosHost()
    private var observers: [NSObjectProtocol] = []
    private var presenter: AnyObject?
    private var closed = false

    private init() {
        // After the host exists, so the Kotlin capture already knows where the report goes. The handler is a C
        // function pointer and captures nothing.
        NSSetUncaughtExceptionHandler { exception in
            IosCrashHooksKt.iosCaptureNSException(
                name: exception.name.rawValue,
                reason: exception.reason,
                callStack: exception.callStackSymbols
            )
        }
        observers.append(NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main
        ) { [host] _ in
            // Real activation only; the Kotlin pack spends its one per-process consideration on it.
            host.foregrounded()
        })
        observers.append(NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main
        ) { [host] _ in
            // Stops frames and blur while hidden; playback keeps running.
            host.backgrounded()
        })
        observers.append(NotificationCenter.default.addObserver(
            forName: UIApplication.willTerminateNotification, object: nil, queue: .main
        ) { [weak self] _ in
            self?.shutdown()
        })
        if #available(iOS 26.0, *) {
            // The Kotlin player still picks the Compose renderer for every other look and when the setting is off.
            host.setNativeLyricsFactory { lyrics in SystemLyrics.makeController(lyrics) }
        }
    }

    /// Re-presents the single controller for a new representable. A previous representable that has not been
    /// dismantled yet is detached from it first, so UIKit never sees the controller with two parents.
    func present(for token: AnyObject) -> UIViewController {
        let controller = host.viewController
        if let stale = presenter, stale !== token {
            controller.removeFromParent()
            controller.view.removeFromSuperview()
        }
        presenter = token
        return controller
    }

    /// Releases only this representable's claim; a stale dismantle never clears a newer presenter.
    func dismiss(for token: AnyObject) {
        if presenter === token { presenter = nil }
    }

    /// The system glass tab bar and mini player bridge. The Kotlin root stays the owner of tabs and playback.
    var nativeChrome: IosNativeChrome { host.nativeChrome }

    /// The onOpenURL entry: a deep link is handed to the host, which queues it for the session graph to resolve.
    func accept(_ url: URL) {
        host.openLink(link: url.absoluteString)
    }

    /// Final process shutdown. Idempotent, and never reached by controller detach or reattach.
    func shutdown() {
        guard !closed else { return }
        closed = true
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        observers.removeAll()
        presenter = nil
        host.close()
    }
}

/// On iOS 26 and later the system Liquid Glass chrome is drawn above the Compose root; earlier releases keep the
/// Compose root alone, unchanged. The Kotlin "Native iOS interface" setting decides whether the chrome shows.
private struct AppRoot: View {
    var body: some View {
        if #available(iOS 26.0, *) {
            NativeChromeHost(chrome: ArchiveTuneSession.shared.nativeChrome) {
                ComposeRoot().ignoresSafeArea()
            }
        } else {
            ComposeRoot().ignoresSafeArea()
        }
    }
}

private struct ComposeRoot: UIViewControllerRepresentable {
    /// Identity only: this representable's claim on the shared controller. It owns no services.
    final class Coordinator {}

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIViewController(context: Context) -> UIViewController {
        ArchiveTuneSession.shared.present(for: context.coordinator)
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {}

    static func dismantleUIViewController(_ controller: UIViewController, coordinator: Coordinator) {
        ArchiveTuneSession.shared.dismiss(for: coordinator)
    }
}
