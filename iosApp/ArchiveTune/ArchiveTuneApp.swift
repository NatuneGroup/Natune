/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
import SwiftUI
import ArchiveTuneShared

@main
struct ArchiveTuneApp: App {
    init() {
        _ = ArchiveTuneSession.shared
    }

    var body: some Scene {
        WindowGroup {
            ComposeRoot()
                .ignoresSafeArea()
                .onOpenURL { ArchiveTuneSession.shared.accept($0) }
                .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
                    if let url = activity.webpageURL { ArchiveTuneSession.shared.accept(url) }
                }
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
