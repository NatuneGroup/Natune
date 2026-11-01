import SwiftUI
import UIKit
import ArchiveTuneShared

/// Builds the system lyric view the Kotlin player hosts for the Enhanced look.
@available(iOS 26.0, *)
enum SystemLyrics {
    static func makeController(_ lyrics: IosNativeLyrics) -> UIViewController {
        let controller = UIHostingController(rootView: SystemLyricsView(lyrics: lyrics))
        controller.view.backgroundColor = .clear
        // The player places this view; its own safe area would push the rows away from where Compose put them.
        controller.safeAreaRegions = []
        return controller
    }
}

/// Mirrors one Kotlin lyric bridge for SwiftUI. Kotlin delivers every update on the main thread.
final class SystemLyricsModel: ObservableObject {
    @Published private(set) var document: NativeLyricsDocument?
    @Published private(set) var visible = false
    let lyrics: IosNativeLyrics
    private var subscription: IosSubscription?

    init(lyrics: IosNativeLyrics) {
        self.lyrics = lyrics
        subscription = lyrics.observe { [weak self] update in
            guard let self else { return }
            if self.document !== update.document { self.document = update.document }
            if self.visible != update.visible { self.visible = update.visible }
        }
    }

    deinit {
        subscription?.cancel()
    }
}

/// The Apple Music style lyric list: the sung words light up as they are sung, the list springs to the sung line,
/// and rows away from it soften. The position comes from Kotlin on every frame; this view keeps no clock.
@available(iOS 18.0, *)
struct SystemLyricsView: View {
    @StateObject private var model: SystemLyricsModel
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    @State private var advancing = false

    init(lyrics: IosNativeLyrics) {
        _model = StateObject(wrappedValue: SystemLyricsModel(lyrics: lyrics))
    }

    var body: some View {
        if let document = model.document {
            let reduceMotion = document.reduceMotion || systemReduceMotion
            // A held position only needs a slow poll, so a pause, a buffer or a seek still shows within a moment.
            let interval: Double? = (reduceMotion || !advancing) ? SystemLyricsMetrics.heldPollSeconds : nil
            TimelineView(.animation(minimumInterval: interval, paused: !model.visible)) { _ in
                let frame = model.lyrics.frame()
                SystemLyricsList(document: document, frame: frame, lyrics: model.lyrics, reduceMotion: reduceMotion)
                    .onChange(of: frame.advancing, initial: true) { _, value in
                        if advancing != value { advancing = value }
                    }
            }
        }
    }
}

private enum SystemLyricsMetrics {
    static let heldPollSeconds = 0.25
    static let anchor = UnitPoint(x: 0.5, y: 0.35)
    static let rowSpacing: CGFloat = 22
    static let horizontalPadding: CGFloat = 28
    static let verticalMargin: CGFloat = 160
    static let backgroundRatio: CGFloat = 0.7
    static let readingRatio: CGFloat = 0.55
    static let lift: CGFloat = 2
}

@available(iOS 18.0, *)
private struct SystemLyricsList: View {
    let document: NativeLyricsDocument
    let frame: NativeLyricsFrame
    let lyrics: IosNativeLyrics
    let reduceMotion: Bool

    @ScaledMetric(relativeTo: .largeTitle) private var mainSize: CGFloat = 30
    @State private var interacting = false
    @State private var following = true
    @State private var latestPrimary: Int32 = -1
    @State private var resumeToken = 0

    var body: some View {
        let primary = frame.primaryLine
        let color = Color(argb: document.colorArgb)
        let idleAlpha = document.synced ? Double(document.idleAlpha) : 1
        ScrollViewReader { proxy in
            ScrollView(.vertical) {
                LazyVStack(spacing: SystemLyricsMetrics.rowSpacing) {
                    ForEach(document.lines, id: \.index) { line in
                        let active = frame.isLineActive(index: line.index)
                        let depth = (document.depthEffects && !interacting)
                            ? frame.depth(index: line.index, reduceMotion: reduceMotion) : nil
                        SystemLyricsRow(
                            line: line,
                            active: active,
                            positionMs: active ? frame.positionMs : -1,
                            scale: CGFloat(depth?.scale ?? 1),
                            blur: CGFloat(depth?.blurPoints ?? 0),
                            alpha: Double(depth?.alpha ?? 1),
                            color: color,
                            idleAlpha: idleAlpha,
                            mainSize: mainSize,
                            reduceMotion: reduceMotion
                        )
                        .equatable()
                        .id(line.index)
                        .onTapGesture { lyrics.seekTo(index: line.index) }
                        .contextMenu {
                            if let shareText = lyrics.shareText(index: line.index) {
                                ShareLink(item: shareText)
                            }
                        }
                    }
                }
                .padding(.horizontal, SystemLyricsMetrics.horizontalPadding)
            }
            .scrollIndicators(.hidden)
            .contentMargins(.vertical, SystemLyricsMetrics.verticalMargin, for: .scrollContent)
            .mask {
                LinearGradient(
                    stops: [
                        .init(color: .clear, location: 0),
                        .init(color: .black, location: 0.12),
                        .init(color: .black, location: 0.82),
                        .init(color: .clear, location: 1),
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )
            }
            .onScrollPhaseChange { _, phase in
                switch phase {
                case .tracking, .interacting, .decelerating:
                    interacting = true
                    following = false
                case .idle, .animating:
                    if interacting {
                        interacting = false
                        resumeToken += 1
                    }
                @unknown default:
                    break
                }
            }
            .onAppear {
                latestPrimary = primary
                scroll(proxy, to: primary, animated: false)
            }
            .onChange(of: primary) { _, next in
                latestPrimary = next
                if following { scroll(proxy, to: next, animated: !reduceMotion) }
            }
            .onChange(of: frame.discontinuity) { _, _ in
                following = true
                scroll(proxy, to: primary, animated: false)
            }
            .onChange(of: frame.reanchor) { _, _ in
                // A feed jump on the same timeline snaps rather than animates, but it is not a
                // seek — it never ends a listener's hold.
                if following { scroll(proxy, to: primary, animated: false) }
            }
            .task(id: resumeToken) {
                // The list follows the song again once the listener has left it alone for the shared hold.
                guard resumeToken > 0 else { return }
                try? await Task.sleep(for: .milliseconds(Int(document.manualScrollHoldMs)))
                guard !Task.isCancelled, !interacting else { return }
                following = true
                scroll(proxy, to: latestPrimary, animated: !reduceMotion)
            }
        }
    }

    private func scroll(_ proxy: ScrollViewProxy, to index: Int32, animated: Bool) {
        guard index >= 0 else { return }
        if animated {
            withAnimation(.spring(response: 0.6, dampingFraction: 0.86)) {
                proxy.scrollTo(index, anchor: SystemLyricsMetrics.anchor)
            }
        } else {
            proxy.scrollTo(index, anchor: SystemLyricsMetrics.anchor)
        }
    }
}

@available(iOS 18.0, *)
private struct SystemLyricsRow: View, Equatable {
    let line: NativeLyricsLine
    let active: Bool
    let positionMs: Int64
    let scale: CGFloat
    let blur: CGFloat
    let alpha: Double
    let color: Color
    let idleAlpha: Double
    let mainSize: CGFloat
    let reduceMotion: Bool

    static func == (lhs: SystemLyricsRow, rhs: SystemLyricsRow) -> Bool {
        lhs.line === rhs.line && lhs.active == rhs.active && lhs.positionMs == rhs.positionMs &&
            lhs.scale == rhs.scale && lhs.blur == rhs.blur && lhs.alpha == rhs.alpha && lhs.color == rhs.color &&
            lhs.idleAlpha == rhs.idleAlpha && lhs.mainSize == rhs.mainSize && lhs.reduceMotion == rhs.reduceMotion
    }

    /// A second voice sits on the far side, and right-to-left text reads from the right.
    private var trailing: Bool { line.secondVoice != line.rightToLeft }

    var body: some View {
        VStack(alignment: trailing ? .trailing : .leading, spacing: 6) {
            LyricText(
                text: line.text,
                spans: line.spans,
                active: active,
                positionMs: positionMs,
                font: .system(size: mainSize, weight: .bold),
                color: color,
                idleAlpha: idleAlpha,
                rightToLeft: line.rightToLeft,
                lift: reduceMotion ? 0 : SystemLyricsMetrics.lift
            )
            if let background = line.background {
                LyricText(
                    text: background,
                    spans: line.backgroundSpans,
                    active: active,
                    positionMs: positionMs,
                    font: .system(size: mainSize * SystemLyricsMetrics.backgroundRatio, weight: .semibold),
                    color: color.opacity(0.85),
                    idleAlpha: idleAlpha,
                    rightToLeft: line.rightToLeft,
                    lift: 0
                )
            }
            if let reading = line.phonetic {
                Text(reading)
                    .font(.system(size: mainSize * SystemLyricsMetrics.readingRatio, weight: .medium))
                    .foregroundStyle(color.opacity(active ? 0.8 : idleAlpha * 0.8))
            }
            if let translation = line.translation {
                Text(translation)
                    .font(.system(size: mainSize * SystemLyricsMetrics.readingRatio, weight: .medium))
                    .foregroundStyle(color.opacity(active ? 0.75 : idleAlpha * 0.75))
            }
        }
        .multilineTextAlignment(trailing ? .trailing : .leading)
        .frame(maxWidth: .infinity, alignment: trailing ? .trailing : .leading)
        .scaleEffect(scale, anchor: trailing ? .trailing : .leading)
        .blur(radius: blur)
        .opacity(alpha)
        .animation(reduceMotion ? nil : .smooth(duration: 0.45), value: scale)
        .animation(reduceMotion ? nil : .smooth(duration: 0.45), value: blur)
        .animation(reduceMotion ? nil : .easeOut(duration: 0.3), value: active)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(line.seekable ? .isButton : [])
    }
}

/// One line of lyric text. While its row is sung and it has word timings, each word lights from the leading edge as
/// it is sung; otherwise the whole line is drawn lit or dim.
@available(iOS 18.0, *)
private struct LyricText: View {
    let text: String
    let spans: [NativeLyricsSpan]
    let active: Bool
    let positionMs: Int64
    let font: Font
    let color: Color
    let idleAlpha: Double
    let rightToLeft: Bool
    let lift: CGFloat

    var body: some View {
        if active && !spans.isEmpty {
            sweptText
                .font(font)
                .foregroundStyle(color)
                .textRenderer(renderer)
        } else {
            Text(verbatim: text)
                .font(font)
                .foregroundStyle(color.opacity(active ? 1 : idleAlpha))
        }
    }

    /// Each word carries its index, so the renderer can find its timing among the laid-out runs.
    private var sweptText: Text {
        var result = Text(verbatim: "")
        for (index, span) in spans.enumerated() {
            result = result + Text(verbatim: span.text).customAttribute(LyricSpanAttribute(index: index))
        }
        return result
    }

    private var renderer: LyricSweepRenderer {
        LyricSweepRenderer(
            starts: spans.map { $0.startMs },
            ends: spans.map { $0.endMs },
            positionMs: positionMs,
            idleAlpha: idleAlpha,
            rightToLeft: rightToLeft,
            lift: lift
        )
    }
}

@available(iOS 18.0, *)
private struct LyricSpanAttribute: TextAttribute {
    let index: Int
}

/// Draws each word dim, then the sung part of it lit, with a soft edge that follows the voice. A word rises a little
/// as it is sung, which Reduce Motion turns off.
@available(iOS 18.0, *)
private struct LyricSweepRenderer: TextRenderer {
    let starts: [Int64]
    let ends: [Int64]
    let positionMs: Int64
    let idleAlpha: Double
    let rightToLeft: Bool
    let lift: CGFloat

    private static let featherPoints: CGFloat = 14

    func draw(layout: Text.Layout, in context: inout GraphicsContext) {
        for line in layout {
            for run in line {
                guard let span = run[LyricSpanAttribute.self] else {
                    context.draw(run)
                    continue
                }
                let progress = fraction(span.index)
                var base = context
                base.translateBy(x: 0, y: -lift * progress)
                var dim = base
                dim.opacity = idleAlpha
                dim.draw(run)
                guard progress > 0 else { continue }
                let bounds = run.typographicBounds.rect.insetBy(dx: 0, dy: -lift - 2)
                let feather = min(Self.featherPoints, bounds.width * 0.4)
                let reach = (bounds.width + feather) * progress
                var lit = base
                lit.clipToLayer { mask in
                    let solid = max(0, reach - feather)
                    let solidRect = rightToLeft
                        ? CGRect(x: bounds.maxX - solid, y: bounds.minY, width: solid, height: bounds.height)
                        : CGRect(x: bounds.minX, y: bounds.minY, width: solid, height: bounds.height)
                    mask.fill(Path(solidRect), with: .color(.black))
                    let edgeStart = rightToLeft ? bounds.maxX - solid : bounds.minX + solid
                    let edgeEnd = rightToLeft ? edgeStart - feather : edgeStart + feather
                    let edgeRect = CGRect(
                        x: min(edgeStart, edgeEnd), y: bounds.minY, width: feather, height: bounds.height
                    )
                    mask.fill(
                        Path(edgeRect),
                        with: .linearGradient(
                            Gradient(colors: [.black, .clear]),
                            startPoint: CGPoint(x: edgeStart, y: bounds.midY),
                            endPoint: CGPoint(x: edgeEnd, y: bounds.midY)
                        )
                    )
                }
                lit.draw(run)
            }
        }
    }

    private func fraction(_ index: Int) -> CGFloat {
        guard index < starts.count, index < ends.count else { return 0 }
        let start = starts[index]
        let end = ends[index]
        if positionMs <= start { return 0 }
        if positionMs >= end { return 1 }
        return CGFloat(Double(positionMs - start) / Double(max(1, end - start)))
    }
}

private extension Color {
    /// A colour from 0xAARRGGBB, the form the shared theme hands over.
    init(argb: Int32) {
        let value = UInt32(bitPattern: argb)
        self.init(
            .sRGB,
            red: Double((value >> 16) & 0xFF) / 255,
            green: Double((value >> 8) & 0xFF) / 255,
            blue: Double(value & 0xFF) / 255,
            opacity: Double((value >> 24) & 0xFF) / 255
        )
    }
}
