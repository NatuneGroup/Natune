import SwiftUI
import WidgetKit

private struct ControlsEntry: TimelineEntry {
    let date: Date
}

private struct ControlsProvider: TimelineProvider {
    func placeholder(in context: Context) -> ControlsEntry { ControlsEntry(date: Date()) }
    func getSnapshot(in context: Context, completion: @escaping (ControlsEntry) -> Void) {
        completion(ControlsEntry(date: Date()))
    }
    func getTimeline(in context: Context, completion: @escaping (Timeline<ControlsEntry>) -> Void) {
        completion(Timeline(entries: [ControlsEntry(date: Date())], policy: .never))
    }
}

private struct ControlsView: View {
    @Environment(\.widgetFamily) private var family
    let entry: ControlsEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Natune").font(.headline)
            Text("widget_open_hint").font(.caption).foregroundColor(.secondary)
            HStack(spacing: 16) {
                control("play", "play.fill", "widget_play")
                control("pause", "pause.fill", "widget_pause")
                if family == .systemMedium { control("previous", "backward.end.fill", "widget_previous") }
                control("next", "forward.end.fill", "widget_next")
            }
        }
        .padding()
        .widgetURL(URL(string: "natune://widget/open"))
        .natuneWidgetBackground()
    }

    private func control(_ action: String, _ symbol: String, _ label: LocalizedStringKey) -> some View {
        Link(destination: URL(string: "natune://widget/" + action)!) {
            Label(label, systemImage: symbol).labelStyle(.iconOnly)
                .font(.title3).frame(maxWidth: .infinity, minHeight: 36)
        }
        .accessibilityLabel(Text(label))
    }
}

private extension View {
    @ViewBuilder func natuneWidgetBackground() -> some View {
        if #available(iOSApplicationExtension 17.0, *) {
            containerBackground(for: .widget) { Color(.secondarySystemBackground) }
        } else {
            background(Color(.secondarySystemBackground))
        }
    }
}

@main
struct NatuneControls: Widget {
    let kind = "NatuneControls"
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: ControlsProvider()) { ControlsView(entry: $0) }
            .configurationDisplayName("widget_name")
            .description("widget_description")
            .supportedFamilies([.systemSmall, .systemMedium])
    }
}
