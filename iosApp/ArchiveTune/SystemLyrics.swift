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
    static let backgroundAlpha: Double = 0.72
    static let readingRatio: CGFloat = 0.55
    static let revealSeconds: Double = 0.6
    static let activeScaleSeconds: Double = 0.1
    static let depthSeconds: Double = 0.45
    static let scrollSeconds: Double = 0.35
    static let featherFraction: CGFloat = 0.2
    static let featherMin: CGFloat = 4
    static let featherMax: CGFloat = 28
    static let glowAlpha: Double = 0.35
    static let glowCorner: CGFloat = 8
    static let emphasisLift: CGFloat = 2
    static let emphasisScale: CGFloat = 0.10
    /// The low seat every unsung word sits at and the rise a completing word crosses — the shared
    /// lift (LyricsSweepGeometry.WORD_LIFT_DP), so the three renderers draw the same rise.
    static let wordLift: CGFloat = 2
    static let interludeDotDiameter: CGFloat = 10
    static let interludeDotGap: CGFloat = 6
    static let interludeEnterMs: Int64 = 400
    static let interludeExitMs: Int64 = 400
    static let interludeBreathMs: Int64 = 2400
    static let interludeSwellMs: Int64 = 1500
    static let interludeIdleLight: Double = 0.25
    static let autoCreatedMark = "✦ "

    /// The soft edge the sweep carries across a word: a share of its width, clamped like the shared sweep.
    static func feather(of width: CGFloat) -> CGFloat {
        min(featherMax, max(featherMin, width * featherFraction))
    }
}

@available(iOS 18.0, *)
private struct SystemLyricsList: View {
    let document: NativeLyricsDocument
    let frame: NativeLyricsFrame
    let lyrics: IosNativeLyrics
    let reduceMotion: Bool

    @State private var interacting = false
    @State private var following = true
    @State private var latestPrimary: Int32 = -1
    @State private var resumeToken = 0

    var body: some View {
        let primary = frame.primaryLine
        let color = Color(argb: document.colorArgb)
        let glow = Color(argb: document.glowArgb)
        let idleAlpha = document.synced ? Double(document.idleAlpha) : 1
        // The saved size is a reading size: it rides Dynamic Type like the retired @ScaledMetric did.
        let mainSize = UIFontMetrics(forTextStyle: .largeTitle).scaledValue(for: CGFloat(document.mainSizeSp))
        ScrollViewReader { proxy in
            ScrollView(.vertical) {
                LazyVStack(spacing: SystemLyricsMetrics.rowSpacing) {
                    ForEach(document.lines, id: \.index) { line in
                        let active = frame.isLineActive(index: line.index)
                        // A listener reading ahead sees every row sharp until the list follows the playhead again.
                        let depth = (document.depthEffects && following && !interacting)
                            ? frame.depth(index: line.index, reduceMotion: reduceMotion) : nil
                        SystemLyricsRow(
                            line: line,
                            active: active,
                            positionMs: frame.positionMs,
                            scale: CGFloat(depth?.scale ?? 1),
                            blur: CGFloat(depth?.blurPoints ?? 0),
                            alpha: Double(depth?.alpha ?? 1),
                            stagger: Double(depth?.staggerMs ?? 0) / 1_000,
                            popScale: CGFloat(document.activeScale),
                            color: color,
                            glow: glow,
                            idleAlpha: idleAlpha,
                            mainSize: mainSize,
                            minSweepMs: document.minSweepMs,
                            autoCreatedLabel: document.credits?.autoCreatedLabel,
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
                    if let credits = document.credits,
                       credits.writtenBy != nil || credits.autoCreatedLabel != nil {
                        VStack(spacing: 4) {
                            if let writtenBy = credits.writtenBy {
                                Text(writtenBy)
                            }
                            if let label = credits.autoCreatedLabel {
                                Text(SystemLyricsMetrics.autoCreatedMark + label)
                                    .foregroundStyle(color.opacity(idleAlpha * 0.8))
                            }
                        }
                        .font(.system(size: mainSize * SystemLyricsMetrics.readingRatio, weight: .medium))
                        .foregroundStyle(color.opacity(idleAlpha))
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 16)
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
            // The shared Enhanced follow eases a line change to the anchor on one fixed bezier.
            withAnimation(.timingCurve(0.42, 0, 0.58, 1, duration: SystemLyricsMetrics.scrollSeconds)) {
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
    let stagger: Double
    let popScale: CGFloat
    let color: Color
    let glow: Color
    let idleAlpha: Double
    let mainSize: CGFloat
    let minSweepMs: Int64
    let autoCreatedLabel: String?
    let reduceMotion: Bool

    static func == (lhs: SystemLyricsRow, rhs: SystemLyricsRow) -> Bool {
        lhs.line === rhs.line && lhs.active == rhs.active && lhs.positionMs == rhs.positionMs &&
            lhs.scale == rhs.scale && lhs.blur == rhs.blur && lhs.alpha == rhs.alpha && lhs.stagger == rhs.stagger &&
            lhs.popScale == rhs.popScale && lhs.color == rhs.color && lhs.glow == rhs.glow &&
            lhs.idleAlpha == rhs.idleAlpha && lhs.mainSize == rhs.mainSize && lhs.minSweepMs == rhs.minSweepMs &&
            lhs.autoCreatedLabel == rhs.autoCreatedLabel && lhs.reduceMotion == rhs.reduceMotion
    }

    /// A second voice sits on the far side, and right-to-left text reads from the right.
    private var trailing: Bool { line.secondVoice != line.rightToLeft }
    private var edgeAnchor: UnitPoint { trailing ? .trailing : .leading }
    private var sublineFont: Font { .system(size: mainSize * SystemLyricsMetrics.readingRatio, weight: .medium) }

    var body: some View {
        VStack(alignment: trailing ? .trailing : .leading, spacing: 6) {
            if line.interludeStartMs >= 0 {
                LyricInterludeDots(
                    startMs: line.interludeStartMs,
                    endMs: line.interludeEndMs,
                    positionMs: positionMs,
                    color: color,
                    reduceMotion: reduceMotion
                )
            }
            LyricText(
                text: line.text,
                spans: line.spans,
                active: active,
                positionMs: positionMs,
                font: .system(size: mainSize, weight: .bold),
                color: color,
                glow: glow,
                idleAlpha: idleAlpha,
                rightToLeft: line.rightToLeft,
                reduceMotion: reduceMotion,
                minSweepMs: minSweepMs
            )
            // The shared look shows a backing line lit with the row, not swept word by word.
            if let background = line.background {
                Text(background)
                    .font(.system(size: mainSize * SystemLyricsMetrics.backgroundRatio, weight: .semibold))
                    .foregroundStyle(color.opacity(active ? SystemLyricsMetrics.backgroundAlpha : idleAlpha))
            }
            if let reading = line.phonetic {
                LyricSubline(
                    text: reading,
                    autoCreated: line.phoneticAutoCreated,
                    autoCreatedLabel: autoCreatedLabel,
                    font: sublineFont,
                    color: color.opacity(active ? 0.8 : idleAlpha * 0.8),
                    reduceMotion: reduceMotion
                )
            }
            if let translation = line.translation {
                LyricSubline(
                    text: translation,
                    autoCreated: line.translationAutoCreated,
                    autoCreatedLabel: autoCreatedLabel,
                    font: sublineFont,
                    color: color.opacity(active ? 0.75 : idleAlpha * 0.75),
                    reduceMotion: reduceMotion
                )
            }
        }
        .multilineTextAlignment(trailing ? .trailing : .leading)
        .frame(maxWidth: .infinity, alignment: trailing ? .trailing : .leading)
        .shadow(color: .black.opacity(0.45), radius: 3, y: 1.5)
        .animation(reduceMotion ? nil : .easeOut(duration: 0.3), value: active)
        .scaleEffect(active ? popScale : 1, anchor: edgeAnchor)
        .animation(reduceMotion ? nil : .easeOut(duration: SystemLyricsMetrics.activeScaleSeconds), value: active)
        .scaleEffect(scale, anchor: edgeAnchor)
        .blur(radius: blur)
        .opacity(alpha)
        .animation(reduceMotion ? nil : .smooth(duration: SystemLyricsMetrics.depthSeconds).delay(stagger), value: scale)
        .animation(reduceMotion ? nil : .smooth(duration: SystemLyricsMetrics.depthSeconds).delay(stagger), value: blur)
        .animation(reduceMotion ? nil : .smooth(duration: SystemLyricsMetrics.depthSeconds).delay(stagger), value: alpha)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(line.seekable ? .isButton : [])
    }
}

/// One reading or translation under the sung line, with the machine-made mark when the document badges it.
/// It slides in when its text publishes to the row — the row may not be the sung one, as a listener reading
/// ahead still sees every reading — and a republished text enters again from hidden.
@available(iOS 18.0, *)
private struct LyricSubline: View {
    let text: String
    let autoCreated: Bool
    let autoCreatedLabel: String?
    let font: Font
    let color: Color
    let reduceMotion: Bool

    @State private var shown = false

    var body: some View {
        Group {
            if shown || reduceMotion {
                HStack(spacing: 0) {
                    if autoCreated {
                        Text(SystemLyricsMetrics.autoCreatedMark)
                            .accessibilityLabel(autoCreatedLabel ?? "")
                    }
                    Text(text)
                }
                .font(font)
                .foregroundStyle(color)
                .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .animation(reduceMotion ? nil : .easeInOut(duration: SystemLyricsMetrics.revealSeconds), value: shown)
        .task(id: text) {
            // A fresh text enters from hidden; a republished one leaves in place, like the shared reveal.
            var transaction = Transaction()
            transaction.disablesAnimations = true
            withTransaction(transaction) { shown = false }
            await Task.yield()
            shown = true
        }
    }
}

/// The three dots of a long instrumental break: they light in turn, breathe slowly and swell as the
/// next line arrives — the same clock-driven motion the shared Enhanced look draws, in points.
@available(iOS 18.0, *)
private struct LyricInterludeDots: View {
    let startMs: Int64
    let endMs: Int64
    let positionMs: Int64
    let color: Color
    let reduceMotion: Bool

    private var visibility: Double {
        if positionMs < startMs || positionMs >= endMs { return 0 }
        let enter = Double(positionMs - startMs) / Double(SystemLyricsMetrics.interludeEnterMs)
        let exit = Double(endMs - positionMs) / Double(SystemLyricsMetrics.interludeExitMs)
        return min(enter, exit, 1)
    }

    private var progress: Double {
        min(max(Double(positionMs - startMs) / Double(max(1, endMs - startMs)), 0), 1)
    }

    /// The slow breath and the anticipation swell over the break's last stretch; the bigger one wins.
    private var swell: Double {
        if reduceMotion { return 1 }
        let phase = Double(positionMs % SystemLyricsMetrics.interludeBreathMs) /
            Double(SystemLyricsMetrics.interludeBreathMs)
        let breath = 0.92 + 0.16 * (0.5 - 0.5 * cos(2 * .pi * phase))
        var end = 0.0
        let remaining = endMs - positionMs
        if remaining > 0 && remaining <= SystemLyricsMetrics.interludeSwellMs {
            let through = 1 - Double(remaining) / Double(SystemLyricsMetrics.interludeSwellMs)
            end = 1.1 + 0.3 * through
        }
        return max(breath, end)
    }

    private func light(_ index: Int) -> Double {
        let turn = progress * 3 - Double(index)
        let share = reduceMotion ? (turn > 0 ? 1 : 0) : min(max(turn, 0), 1)
        return SystemLyricsMetrics.interludeIdleLight + (1 - SystemLyricsMetrics.interludeIdleLight) * share
    }

    var body: some View {
        Canvas { context, _ in
            let visibility = self.visibility
            guard visibility > 0 else { return }
            let swell = self.swell
            let radius = SystemLyricsMetrics.interludeDotDiameter / 2 * swell
            for index in 0..<3 {
                let centreX = SystemLyricsMetrics.interludeDotDiameter / 2 +
                    CGFloat(index) * (SystemLyricsMetrics.interludeDotDiameter + SystemLyricsMetrics.interludeDotGap)
                let rect = CGRect(x: centreX - radius, y: 7 - radius, width: radius * 2, height: radius * 2)
                context.fill(Path(ellipseIn: rect), with: .color(color.opacity(visibility * light(index))))
            }
        }
        .frame(width: 42, height: 14)
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
    let glow: Color
    let idleAlpha: Double
    let rightToLeft: Bool
    let reduceMotion: Bool
    let minSweepMs: Int64

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

    /// Each word carries its index so the renderer can find its timing among the laid-out runs. A word that earned
    /// the emphasis ripple arrives already cut into its cluster letters, between an optional prefix and the tail.
    private var sweptText: Text {
        var result = Text(verbatim: "")
        for (index, span) in spans.enumerated() {
            if let letters = span.letters, !letters.isEmpty {
                if !span.lettersPrefix.isEmpty {
                    result = result + Text(verbatim: span.lettersPrefix)
                        .customAttribute(LyricSpanAttribute(index: index, letter: -1))
                }
                for (letter, letterText) in letters.enumerated() {
                    result = result + Text(verbatim: letterText)
                        .customAttribute(LyricSpanAttribute(index: index, letter: letter))
                }
                let covered = span.lettersPrefix.count + letters.joined().count
                if covered < span.text.count {
                    result = result + Text(verbatim: String(span.text.dropFirst(covered)))
                        .customAttribute(LyricSpanAttribute(index: index, letter: -1))
                }
            } else {
                result = result + Text(verbatim: span.text)
                    .customAttribute(LyricSpanAttribute(index: index, letter: -1))
            }
        }
        return result
    }

    private var renderer: LyricSweepRenderer {
        LyricSweepRenderer(
            spans: spans,
            positionMs: positionMs,
            idleAlpha: idleAlpha,
            rightToLeft: rightToLeft,
            reduceMotion: reduceMotion,
            minSweepMs: minSweepMs,
            glow: glow
        )
    }
}

@available(iOS 18.0, *)
private struct LyricSpanAttribute: TextAttribute {
    /// The span the run belongs to, and its letter inside the span's emphasized word, -1 for a plain run.
    let index: Int
    let letter: Int
}

/// Draws each word dim, then the sung part of it lit, with a soft edge that follows the voice. A word that earned
/// the shared emphasis has its letters cut out and drawn one at a time, rippling up as the edge crosses them.
@available(iOS 18.0, *)
private struct LyricSweepRenderer: TextRenderer {
    let spans: [NativeLyricsSpan]
    let positionMs: Int64
    let idleAlpha: Double
    let rightToLeft: Bool
    let reduceMotion: Bool
    let minSweepMs: Int64
    let glow: Color

    /// One letter run of an emphasized word and the visual line it sits on.
    private struct LetterSlice {
        let layoutLine: Int
        let rect: CGRect
    }

    func draw(layout: Text.Layout, in context: inout GraphicsContext) {
        // Group the letter runs by word so the sweep edge can cross each emphasized word as one region.
        // A word whose letters land on two visual lines keeps the plain sweep, as the shared sweep does.
        var slices: [Int: [LetterSlice]] = [:]
        var layoutLine = 0
        for line in layout {
            for run in line {
                if let span = run[LyricSpanAttribute.self], span.letter >= 0 {
                    slices[span.index, default: []].append(
                        LetterSlice(layoutLine: layoutLine, rect: run.typographicBounds.rect)
                    )
                }
            }
            layoutLine += 1
        }
        let wrapped = Set(slices.filter { Set($0.value.map(\.layoutLine)).count > 1 }.keys)
        var unions: [Int: CGRect] = [:]
        for (index, group) in slices {
            unions[index] = group.dropFirst().reduce(group[0].rect) { $0.union($1.rect) }
        }
        let firstStartMs = spans.map(\.startMs).min() ?? 0
        for line in layout {
            for run in line {
                guard let span = run[LyricSpanAttribute.self], span.index < spans.count else {
                    context.draw(run)
                    continue
                }
                let word = spans[span.index]
                // The shared drop: the row sinks one lift low just before its first word and each word
                // eases back up through its own completion lift. Every run of the word, emphasized
                // letters included, moves by the same offset, as the Compose sweep moves them.
                var seat = context
                let offset = SystemLyricsMetrics.wordLift * drop(of: word, index: span.index, firstStartMs: firstStartMs)
                if offset > 0.01 {
                    seat.translateBy(x: 0, y: offset)
                }
                if span.letter >= 0,
                   let group = slices[span.index],
                   let union = unions[span.index],
                   !wrapped.contains(span.index) {
                    drawEmphasizedLetter(
                        run: run, word: word, group: group, union: union, in: &seat
                    )
                } else {
                    let bounds = span.letter >= 0 ? unions[span.index] : nil
                    drawPlainRun(run: run, word: word, bounds: bounds, in: &seat)
                }
            }
        }
    }

    /// The plain sweep for one run: dim base, then bright clipped to the sweep edge with its feathered fade.
    /// [bounds] is the run's own box, or the word's union box for letters that lost their emphasis to wrapping.
    private func drawPlainRun(run: Text.Layout.Run, word: NativeLyricsSpan, bounds: CGRect?, in context: inout GraphicsContext) {
        let progress = fraction(of: word)
        var dim = context
        dim.opacity = idleAlpha
        dim.draw(run)
        guard progress > 0 else { return }
        if LyricsSweepGeometry.shared.sweepComplete(progress: Float(progress)) {
            // A finished word stays lit to its own bounds; the feathered edge would leave its tail dim.
            context.draw(run)
            return
        }
        let box = (bounds ?? run.typographicBounds.rect).insetBy(dx: 0, dy: -2)
        let feather = SystemLyricsMetrics.feather(of: box.width)
        let edge = rightToLeft ? box.maxX - box.width * progress : box.minX + box.width * progress
        var lit = context
        lit.clipToLayer { mask in
            mask.fill(
                Path(box),
                with: .linearGradient(
                    Gradient(colors: rightToLeft ? [.clear, .black] : [.black, .clear]),
                    startPoint: CGPoint(x: edge - feather, y: box.midY),
                    endPoint: CGPoint(x: edge + feather, y: box.midY)
                )
            )
        }
        lit.draw(run)
    }

    /// One letter of an emphasized word: it draws dim and bright like the plain sweep, but clipped to its own
    /// window and riding the staggered ripple, with a glow behind it while it bumps.
    private func drawEmphasizedLetter(
        run: Text.Layout.Run,
        word: NativeLyricsSpan,
        group: [LetterSlice],
        union: CGRect,
        in context: inout GraphicsContext
    ) {
        let progress = fraction(of: word)
        let width = max(0.5, union.width)
        let edge = rightToLeft ? union.maxX - width * progress : union.minX + width * progress
        let feather = SystemLyricsMetrics.feather(of: width)
        let duration = max(1, word.endMs - word.startMs)
        let index = run[LyricSpanAttribute.self]?.letter ?? 0
        let bump = ripple(startMs: word.startMs, durationMs: duration, count: group.count, index: index)
        let rect = run.typographicBounds.rect
        let lift = SystemLyricsMetrics.emphasisLift * bump
        let scale = 1 + SystemLyricsMetrics.emphasisScale * bump
        let centreX = rect.midX
        let centreY = union.midY
        let pad = rect.width * 0.5 + SystemLyricsMetrics.emphasisLift + 4
        if bump > 0 {
            var glowLayer = context
            glowLayer.fill(
                Path(roundedRect: CGRect(
                    x: rect.minX - pad * 0.3,
                    y: union.minY - lift - 2,
                    width: rect.width + pad * 0.6,
                    height: union.height + 4
                ), cornerRadius: SystemLyricsMetrics.glowCorner),
                with: .color(glow.opacity(SystemLyricsMetrics.glowAlpha * bump))
            )
        }
        let window = CGRect(
            x: rect.minX - pad,
            y: union.minY - lift - pad,
            width: rect.width + pad * 2,
            height: union.height + pad * 2
        )
        var moved = context
        moved.clip(to: Path(window))
        moved.translateBy(x: 0, y: -lift)
        moved.translateBy(x: centreX, y: centreY)
        moved.scaleBy(x: scale, y: scale)
        moved.translateBy(x: -centreX, y: -centreY)
        var dim = moved
        dim.opacity = idleAlpha
        dim.draw(run)
        guard progress > 0 else { return }
        if LyricsSweepGeometry.shared.sweepComplete(progress: Float(progress)) {
            var lit = moved
            lit.draw(run)
            return
        }
        // The line's edge mapped into the letter's scaled frame: a canvas x lands at cx + (x - cx) / scale.
        let edgeLocal = centreX + (edge - centreX) / scale
        let featherLocal = feather / scale
        var lit = moved
        lit.clipToLayer { mask in
            mask.fill(
                Path(window),
                with: .linearGradient(
                    Gradient(colors: rightToLeft ? [.clear, .black] : [.black, .clear]),
                    startPoint: CGPoint(x: edgeLocal - featherLocal, y: centreY),
                    endPoint: CGPoint(x: edgeLocal + featherLocal, y: centreY)
                )
            )
        }
        lit.draw(run)
    }

    /// The share of [word] that is sung, from 0 to 1 — the shared sweep share, so reduced motion jumps
    /// between the two and each word fills over exactly its own timing.
    private func fraction(of word: NativeLyricsSpan) -> CGFloat {
        CGFloat(LyricsSweepGeometry.shared.progressAt(
            startMs: word.startMs,
            endMs: word.endMs,
            positionMs: positionMs,
            reduceMotion: reduceMotion,
            minSweepMs: minSweepMs
        ))
    }

    /// How far below its seat the word at [index] draws, as a share of one lift: 0 seated, 1 one lift
    /// low — the shared curve, so a seek lands on the same height and reduced motion keeps every word seated.
    private func drop(of word: NativeLyricsSpan, index: Int, firstStartMs: Int64) -> CGFloat {
        let nextStart = index + 1 < spans.count ? spans[index + 1].startMs : Int64.max
        return CGFloat(LyricsSweepGeometry.shared.dropAt(
            firstWordStartMs: firstStartMs,
            wordStartMs: word.startMs,
            wordEndMs: word.endMs,
            nextWordStartMs: nextStart,
            positionMs: positionMs,
            reduceMotion: reduceMotion
        ))
    }

    /// How much letter [index] of an emphasized word is bumping at the position — the shared wave, stilled
    /// by reduced motion, so a seek lands on the same frame and a still preference rides no letter.
    private func ripple(startMs: Int64, durationMs: Int64, count: Int, index: Int) -> CGFloat {
        CGFloat(LyricsEmphasis.shared.bumpAt(
            wordStartMs: startMs,
            durationMs: durationMs,
            clusterCount: Int32(count),
            index: Int32(index),
            positionMs: positionMs,
            reduceMotion: reduceMotion
        ))
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
