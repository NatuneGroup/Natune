import UIKit
import CarPlay

/// Apple system templates only; the shared Kotlin host remains the sole queue and player.
final class NatuneCarPlayScene: UIResponder, CPTemplateApplicationSceneDelegate {
    private var controller: CPInterfaceController?
    private var observer: NSObjectProtocol?

    func templateApplicationScene(_ scene: CPTemplateApplicationScene, didConnect interfaceController: CPInterfaceController) {
        controller = interfaceController
        refresh()
        observer = NotificationCenter.default.addObserver(forName: Notification.Name("NatuneSystemSnapshotChanged"), object: nil, queue: .main) { [weak self] _ in
            self?.refreshQueue()
        }
    }

    func templateApplicationScene(_ scene: CPTemplateApplicationScene, didDisconnect interfaceController: CPInterfaceController) {
        if let observer { NotificationCenter.default.removeObserver(observer) }
        observer = nil
        controller = nil
    }

    private var queueTemplate = CPListTemplate(title: NSLocalizedString("carplay_queue", comment: ""), sections: [])

    private func refresh() {
        refreshQueue()
        ArchiveTuneSession.shared.systemHost.systemLibrary { [weak self] text in
            guard let self, let controller = self.controller else { return }
            let library = CPListTemplate(title: NSLocalizedString("carplay_library", comment: ""), sections: [CPListSection(items: self.items(text))])
            library.tabTitle = NSLocalizedString("carplay_library", comment: "")
            library.tabImage = UIImage(systemName: "music.note.list")
            let refresh = CPBarButton(type: .text) { [weak self] _ in self?.refresh() }
            refresh.title = NSLocalizedString("carplay_refresh", comment: "")
            library.trailingNavigationBarButtons = [refresh]
            self.queueTemplate.tabTitle = NSLocalizedString("carplay_queue", comment: "")
            self.queueTemplate.tabImage = UIImage(systemName: "list.bullet")
            controller.setRootTemplate(CPTabBarTemplate(templates: [library, self.queueTemplate]), animated: true, completion: nil)
        }
    }

    private func refreshQueue() {
        let raw = ArchiveTuneSession.shared.systemHost.systemSnapshotJson()
        guard let data = raw.data(using: .utf8), let state = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rows = state["queue"] as? [[String: Any]], let encoded = try? JSONSerialization.data(withJSONObject: rows),
              let text = String(data: encoded, encoding: .utf8) else { return }
        queueTemplate.updateSections([CPListSection(items: items(text))])
    }

    private func items(_ text: String) -> [CPListItem] {
        if text == "failed" { return [CPListItem(text: NSLocalizedString("carplay_unavailable", comment: ""), detailText: nil)] }
        guard let data = text.data(using: .utf8), let rows = try? JSONSerialization.jsonObject(with: data) as? [[String: String]] else { return [] }
        return rows.prefix(200).compactMap { row in
            guard let id = row["id"], let provider = row["provider"], let title = row["title"] else { return nil }
            let item = CPListItem(text: title, detailText: row["artist"])
            item.handler = { [weak self] _, done in
                ArchiveTuneSession.shared.systemHost.systemPlay(provider: provider, id: id, occurrence: row["occurrence"]) { result in
                    done()
                    if result == "ready" { self?.controller?.pushTemplate(CPNowPlayingTemplate.shared, animated: true, completion: nil) }
                }
            }
            return item
        }
    }
}
