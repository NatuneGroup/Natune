import UIKit
import CoreImage
import ArchiveTuneShared

/// TextKit does the shaping and bidi layout once; frame drawing only clips cached glyph images.
final class LyricGlyphLayout {
    private struct Slice {
        let glyphs: NSRange
        let rect: CGRect
        let word: Int
        let letter: Int
        let visualLine: Int
    }

    private let storage: NSTextStorage
    private let manager = NSLayoutManager()
    private let container: NSTextContainer
    private let spans: [NativeLyricsSpan]
    private let color: UIColor
    private let scale: CGFloat
    private var slices: [Slice] = []
    private var wordBounds: [Int: [Int: CGRect]] = [:]
    private var letterBounds: [Int: CGRect] = [:]
    private var wrappedLetters: Set<Int> = []
    private var images: [Int: UIImage] = [:]
    private var wholeImage: UIImage?
    private var wholeRange = NSRange(location: 0, length: 0)
    private var wholeRect = CGRect.zero
    private(set) var height: CGFloat = 0

    init(text: String, spans: [NativeLyricsSpan], font: UIFont, color: UIColor, width: CGFloat,
         trailing: Bool, rightToLeft: Bool, scale: CGFloat) {
        self.spans = spans
        self.color = color
        self.scale = scale
        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = trailing ? .right : .left
        paragraph.baseWritingDirection = rightToLeft ? .rightToLeft : .leftToRight
        storage = NSTextStorage(string: text, attributes: [.font: font, .foregroundColor: color, .paragraphStyle: paragraph])
        container = NSTextContainer(size: CGSize(width: width, height: .greatestFiniteMagnitude))
        container.lineFragmentPadding = 0
        manager.addTextContainer(container)
        storage.addLayoutManager(manager)
        // Emphasis is supplied only for unjoined scripts. Disable Latin ligatures there so a glyph never
        // belongs to two moving grapheme clusters; ordinary Arabic/Indic runs keep their native shaping.
        var cursor = 0
        for span in spans {
            let range = NSRange(location: cursor, length: span.text.utf16.count)
            if let letters = span.letters, !letters.isEmpty { storage.addAttribute(.ligature, value: 0, range: range) }
            cursor += range.length
        }
        manager.ensureLayout(for: container)
        wholeRange = NSRange(location: 0, length: manager.numberOfGlyphs)
        wholeRect = manager.boundingRect(forGlyphRange: wholeRange, in: container).insetBy(dx: -2, dy: -2)
        height = max(font.lineHeight, ceil(manager.usedRect(for: container).maxY)) + 4
        buildSlices()
    }

    private func buildSlices() {
        var lines: [NSRange] = []
        manager.enumerateLineFragments(forGlyphRange: wholeRange) { _, _, _, range, _ in lines.append(range) }
        var cursor = 0
        for (word, span) in spans.enumerated() {
            let wordRange = NSRange(location: cursor, length: span.text.utf16.count)
            var pieces: [(NSRange, Int)] = []
            if let letters = span.letters, !letters.isEmpty {
                let prefix = span.lettersPrefix.utf16.count
                if prefix > 0 { pieces.append((NSRange(location: cursor, length: prefix), -1)) }
                var letterCursor = cursor + prefix
                for (letter, text) in letters.enumerated() {
                    let range = NSRange(location: letterCursor, length: text.utf16.count)
                    pieces.append((range, letter))
                    letterCursor += range.length
                }
                if letterCursor < NSMaxRange(wordRange) {
                    pieces.append((NSRange(location: letterCursor, length: NSMaxRange(wordRange) - letterCursor), -1))
                }
            } else { pieces.append((wordRange, -1)) }
            for (range, letter) in pieces where range.length > 0 {
                let glyphs = manager.glyphRange(forCharacterRange: range, actualCharacterRange: nil)
                for (visualLine, lineRange) in lines.enumerated() {
                    let intersection = NSIntersectionRange(glyphs, lineRange)
                    guard intersection.length > 0 else { continue }
                    let rect = manager.boundingRect(forGlyphRange: intersection, in: container)
                    guard rect.width > 0, rect.height > 0 else { continue }
                    slices.append(Slice(glyphs: intersection, rect: rect, word: word, letter: letter, visualLine: visualLine))
                    let previousBounds = wordBounds[word]?[visualLine]
                    wordBounds[word, default: [:]][visualLine] = previousBounds.map { $0.union(rect) } ?? rect
                    if letter >= 0 { letterBounds[word] = letterBounds[word].map { $0.union(rect) } ?? rect }
                }
            }
            cursor += wordRange.length
        }
        for word in spans.indices {
            let lines = Set(slices.filter { $0.word == word && $0.letter >= 0 }.map(\.visualLine))
            if lines.count > 1 { wrappedLetters.insert(word) }
        }
    }

    func releaseImages() {
        images.removeAll()
        wholeImage = nil
    }

    private func image(glyphs: NSRange, rect: CGRect) -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = scale
        return UIGraphicsImageRenderer(size: rect.size, format: format).image { _ in
            manager.drawGlyphs(forGlyphRange: glyphs, at: CGPoint(x: -rect.minX, y: -rect.minY))
        }
    }

    func draw(in context: CGContext, active: Bool, positionMs: Int64, idleAlpha: CGFloat,
              rightToLeft: Bool, reduceMotion: Bool, minSweepMs: Int64, glow: UIColor) {
        if !active || spans.isEmpty {
            guard wholeRange.length > 0 else { return }
            if wholeImage == nil { wholeImage = image(glyphs: wholeRange, rect: wholeRect) }
            context.saveGState()
            context.setAlpha(active ? 1 : idleAlpha)
            wholeImage?.draw(in: wholeRect)
            context.restoreGState()
            return
        }
        let firstStartMs = spans.map(\.startMs).min() ?? 0
        for (index, slice) in slices.enumerated() {
            let word = spans[slice.word]
            let progress = CGFloat(LyricsSweepGeometry.shared.progressAt(
                startMs: word.startMs, endMs: word.endMs, positionMs: positionMs,
                reduceMotion: reduceMotion, minSweepMs: minSweepMs
            ))
            let nextStart = slice.word + 1 < spans.count ? spans[slice.word + 1].startMs : Int64.max
            let drop = CGFloat(LyricsSweepGeometry.shared.dropAt(
                firstWordStartMs: firstStartMs, wordStartMs: word.startMs, wordEndMs: word.endMs,
                nextWordStartMs: nextStart, positionMs: positionMs, reduceMotion: reduceMotion
            ))
            let rect = slice.rect.insetBy(dx: -2, dy: -2)
            if images[index] == nil { images[index] = image(glyphs: slice.glyphs, rect: rect) }
            guard let glyphs = images[index] else { continue }
            var box = wordBounds[slice.word]?[slice.visualLine] ?? slice.rect
            context.saveGState()
            context.translateBy(x: 0, y: SystemLyricsMetrics.wordLift * drop)
            var letterScale: CGFloat = 1
            var centreX: CGFloat = 0
            if slice.letter >= 0, !wrappedLetters.contains(slice.word), let union = letterBounds[slice.word] {
                box = union
                let count = spans[slice.word].letters?.count ?? 0
                let bump = CGFloat(LyricsEmphasis.shared.bumpAt(
                    wordStartMs: word.startMs, durationMs: max(1, word.endMs - word.startMs),
                    clusterCount: Int32(count), index: Int32(slice.letter), positionMs: positionMs, reduceMotion: reduceMotion
                ))
                let lift = SystemLyricsMetrics.emphasisLift * bump
                letterScale = 1 + SystemLyricsMetrics.emphasisScale * bump
                centreX = slice.rect.midX
                if bump > 0 {
                    context.setFillColor(glow.lyricsOpacity(SystemLyricsMetrics.glowAlpha * bump).cgColor)
                    let bloom = CGRect(x: slice.rect.minX - 2, y: union.minY - lift - 2,
                                       width: slice.rect.width + 4, height: union.height + 4)
                    context.addPath(UIBezierPath(roundedRect: bloom, cornerRadius: SystemLyricsMetrics.glowCorner).cgPath)
                    context.fillPath()
                }
                context.translateBy(x: 0, y: -lift)
                context.translateBy(x: centreX, y: union.midY)
                context.scaleBy(x: letterScale, y: letterScale)
                context.translateBy(x: -centreX, y: -union.midY)
            }
            context.setAlpha(idleAlpha)
            glyphs.draw(in: rect)
            context.setAlpha(1)
            if progress > 0 {
                if LyricsSweepGeometry.shared.sweepComplete(progress: Float(progress)) {
                    glyphs.draw(in: rect)
                } else {
                    let edge = rightToLeft ? box.maxX - box.width * progress : box.minX + box.width * progress
                    let localEdge = centreX + (edge - centreX) / letterScale
                    let feather = SystemLyricsMetrics.feather(of: box.width) / letterScale
                    context.beginTransparencyLayer(auxiliaryInfo: nil)
                    glyphs.draw(in: rect)
                    context.saveGState()
                    context.setBlendMode(.destinationIn)
                    let colors = rightToLeft ? [UIColor.clear.cgColor, UIColor.black.cgColor] : [UIColor.black.cgColor, UIColor.clear.cgColor]
                    if let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors as CFArray, locations: [0, 1]) {
                        context.drawLinearGradient(gradient, start: CGPoint(x: localEdge - feather, y: box.midY),
                                                   end: CGPoint(x: localEdge + feather, y: box.midY),
                                                   options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
                    }
                    context.restoreGState()
                    context.endTransparencyLayer()
                }
            }
            context.restoreGState()
        }
    }
}

final class SystemLyricsRowLayout {
    let main: LyricGlyphLayout
    let mainFrame: CGRect
    let backgroundFrame: CGRect?
    let phoneticFrame: CGRect?
    let translationFrame: CGRect?
    let readingFont: UIFont
    let backgroundFont: UIFont
    let width: CGFloat
    let height: CGFloat

    init(line: NativeLyricsLine, document: NativeLyricsDocument, width: CGFloat, traits: UITraitCollection) {
        let size = UIFontMetrics(forTextStyle: .largeTitle).scaledValue(for: CGFloat(document.mainSizeSp), compatibleWith: traits)
        let font = UIFont.systemFont(ofSize: size, weight: .bold)
        let reading = UIFont.systemFont(ofSize: size * SystemLyricsMetrics.readingRatio, weight: .medium)
        let backing = UIFont.systemFont(ofSize: size * SystemLyricsMetrics.backgroundRatio, weight: .semibold)
        let glyphs = LyricGlyphLayout(text: line.text, spans: line.spans, font: font, color: UIColor(lyricsArgb: document.colorArgb),
                                     width: width, trailing: line.secondVoice != line.rightToLeft, rightToLeft: line.rightToLeft,
                                     scale: max(1, traits.displayScale))
        var y: CGFloat = line.interludeStartMs >= 0 ? 20 : 0
        let primary = CGRect(x: 0, y: y, width: width, height: glyphs.height)
        y += glyphs.height
        func subline(_ text: String?, autoCreated: Bool, font: UIFont) -> CGRect? {
            guard let text else { return nil }
            let marked = autoCreated ? SystemLyricsMetrics.autoCreatedMark + text : text
            let rect = (marked as NSString).boundingRect(with: CGSize(width: width, height: .greatestFiniteMagnitude),
                                                        options: [.usesLineFragmentOrigin, .usesFontLeading], attributes: [.font: font], context: nil)
            y += 6
            let frame = CGRect(x: 0, y: y, width: width, height: max(font.lineHeight, ceil(rect.height)))
            y += frame.height
            return frame
        }
        let background = subline(line.background, autoCreated: false, font: backing)
        let phonetic = subline(line.phonetic, autoCreated: line.phoneticAutoCreated, font: reading)
        let translation = subline(line.translation, autoCreated: line.translationAutoCreated, font: reading)
        self.width = width
        main = glyphs
        mainFrame = primary
        readingFont = reading
        backgroundFont = backing
        backgroundFrame = background
        phoneticFrame = phonetic
        translationFrame = translation
        height = y
    }
}

final class SystemLyricsCell: UITableViewCell {
    private let row = LyricsDepthView()
    private var layout: SystemLyricsRowLayout?
    var onSeek: (() -> Void)?

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = .clear
        selectionStyle = .none
        clipsToBounds = false
        contentView.clipsToBounds = false
        contentView.addSubview(row)
        isAccessibilityElement = true
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override func prepareForReuse() {
        super.prepareForReuse()
        row.reset()
        onSeek = nil
        accessibilityCustomActions = nil
    }

    func configure(line: NativeLyricsLine, document: NativeLyricsDocument, layout: SystemLyricsRowLayout, reduceMotion: Bool) {
        self.layout = layout
        row.configure(line: line, document: document, layout: layout, reduceMotion: reduceMotion)
        var text = [line.text, line.background, line.phonetic, line.translation].compactMap { $0 }
        if line.phoneticAutoCreated || line.translationAutoCreated, let label = document.credits?.autoCreatedLabel { text.append(label) }
        accessibilityLabel = text.joined(separator: ", ")
        accessibilityTraits = line.seekable ? [.button] : [.staticText]
        setNeedsLayout()
    }

    override func accessibilityActivate() -> Bool {
        guard let onSeek else { return false }
        onSeek()
        return true
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        row.frame = CGRect(x: SystemLyricsMetrics.horizontalPadding, y: 0,
                           width: layout?.width ?? 0, height: layout?.height ?? 0)
    }

    func update(frame: NativeLyricsFrame, following: Bool, reduceMotion: Bool) {
        row.update(frame: frame, following: following, reduceMotion: reduceMotion)
    }
}

/// UIKit animates the depth, pop and reading reveals. The blur is a bounded, visible-row raster, never a private filter.
private final class LyricsDepthView: UIView {
    private static let imageContext = CIContext(options: [.cacheIntermediates: false])
    private let stage = UIView()
    private let content = LyricsRowContent()
    private let blurred = UIImageView()
    private var line: NativeLyricsLine?
    private var document: NativeLyricsDocument?
    private var targetScale: CGFloat = 1
    private var targetAlpha: CGFloat = 1
    private var targetBlur: CGFloat = 0
    private var snapshotRevision = -1
    private var active = false

    override init(frame: CGRect) {
        super.init(frame: frame)
        addSubview(stage)
        stage.addSubview(content)
        stage.addSubview(blurred)
        blurred.isUserInteractionEnabled = false
        blurred.alpha = 0
        content.layer.shadowColor = UIColor.black.cgColor
        content.layer.shadowOpacity = 0.45
        content.layer.shadowRadius = 3
        content.layer.shadowOffset = CGSize(width: 0, height: 1.5)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override func layoutSubviews() {
        super.layoutSubviews()
        stage.bounds = bounds
        stage.center = CGPoint(x: bounds.midX, y: bounds.midY)
        content.frame = stage.bounds
    }

    func reset() {
        stage.layer.removeAllAnimations()
        content.layer.removeAllAnimations()
        blurred.layer.removeAllAnimations()
        stage.transform = .identity
        stage.alpha = 1
        content.alpha = 1
        blurred.alpha = 0
        blurred.image = nil
        content.reset()
        line = nil
        document = nil
        targetScale = 1
        targetAlpha = 1
        targetBlur = 0
        active = false
        snapshotRevision = -1
    }

    func configure(line: NativeLyricsLine, document: NativeLyricsDocument, layout: SystemLyricsRowLayout, reduceMotion: Bool) {
        self.line = line
        self.document = document
        content.configure(line: line, document: document, layout: layout, reduceMotion: reduceMotion)
    }

    func update(frame: NativeLyricsFrame, following: Bool, reduceMotion: Bool) {
        guard let line, let document else { return }
        let nextActive = frame.isLineActive(index: line.index)
        content.update(active: nextActive, positionMs: frame.positionMs, reduceMotion: reduceMotion)
        let depth = document.depthEffects && following ? frame.depth(index: line.index, reduceMotion: reduceMotion) : nil
        let scale = CGFloat(depth?.scale ?? 1) * (nextActive ? CGFloat(document.activeScale) : 1)
        let alpha = CGFloat(depth?.alpha ?? 1)
        let blur = CGFloat(depth?.blurPoints ?? 0)
        if blur > 0 && (snapshotRevision != content.revision || targetBlur != blur || content.revealing) {
            if makeBlur(radius: blur) { snapshotRevision = content.revision }
        }
        guard targetScale != scale || targetAlpha != alpha || targetBlur != blur || active != nextActive else { return }
        let depthChanged = targetScale != scale || targetAlpha != alpha || targetBlur != blur
        targetScale = scale
        targetAlpha = alpha
        targetBlur = blur
        active = nextActive
        let changes = {
            let trailing = line.secondVoice != line.rightToLeft
            let shift = (trailing ? -1 : 1) * self.bounds.width * (scale - 1) / 2
            self.stage.transform = CGAffineTransform(translationX: shift, y: 0).scaledBy(x: scale, y: scale)
            self.stage.alpha = alpha
            self.content.alpha = blur > 0 ? 0 : 1
            self.blurred.alpha = blur > 0 ? 1 : 0
        }
        if reduceMotion { changes() }
        else {
            UIView.animate(withDuration: depthChanged ? SystemLyricsMetrics.depthSeconds : SystemLyricsMetrics.activeScaleSeconds,
                           delay: depthChanged ? Double(depth?.staggerMs ?? 0) / 1_000 : 0,
                           options: [.beginFromCurrentState, .allowUserInteraction, .curveEaseOut], animations: changes)
        }
    }

    private func makeBlur(radius: CGFloat) -> Bool {
        guard bounds.width > 0, bounds.height > 0 else { return false }
        let padding = ceil(radius * 3 + 8)
        let format = UIGraphicsImageRendererFormat()
        format.scale = max(1, traitCollection.displayScale)
        let size = CGSize(width: bounds.width + 2 * padding, height: bounds.height + 2 * padding)
        content.layoutIfNeeded()
        content.displayLayers()
        let image = UIGraphicsImageRenderer(size: size, format: format).image { renderer in
            renderer.cgContext.translateBy(x: padding, y: padding)
            // Render at full opacity even while the crisp layer is faded out underneath its blurred twin.
            let alpha = content.layer.opacity
            content.layer.opacity = 1
            content.layer.render(in: renderer.cgContext)
            content.layer.opacity = alpha
        }
        guard let input = CIImage(image: image), let filter = CIFilter(name: "CIGaussianBlur") else { return false }
        filter.setValue(input, forKey: kCIInputImageKey)
        filter.setValue(radius * image.scale, forKey: kCIInputRadiusKey)
        guard let output = filter.outputImage,
              let raster = Self.imageContext.createCGImage(output.cropped(to: input.extent), from: input.extent) else { return false }
        blurred.image = UIImage(cgImage: raster, scale: image.scale, orientation: .up)
        blurred.frame = bounds.insetBy(dx: -padding, dy: -padding)
        return true
    }
}

private final class LyricsRowContent: UIView {
    private let primary = LyricWordView()
    private let dots = LyricInterludeView()
    private let background = UILabel()
    private let phonetic = UILabel()
    private let translation = UILabel()
    private var line: NativeLyricsLine?
    private var document: NativeLyricsDocument?
    private var layout: SystemLyricsRowLayout?
    private var lastActive = false
    private var lastPosition: Int64 = -1
    private var revealUntil: CFTimeInterval = 0
    private(set) var revision = 0
    var revealing: Bool { CACurrentMediaTime() < revealUntil }

    override init(frame: CGRect) {
        super.init(frame: frame)
        [primary, dots, background, phonetic, translation].forEach { addSubview($0) }
        [background, phonetic, translation].forEach { $0.numberOfLines = 0 }
        isAccessibilityElement = false
        accessibilityElementsHidden = true
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    func configure(line: NativeLyricsLine, document: NativeLyricsDocument, layout: SystemLyricsRowLayout, reduceMotion: Bool) {
        guard self.line !== line || self.document !== document || self.layout !== layout else { return }
        let old = self.line
        self.line = line
        self.document = document
        self.layout = layout
        primary.layout = layout.main
        primary.line = line
        primary.document = document
        dots.line = line
        dots.color = UIColor(lyricsArgb: document.colorArgb)
        dots.isHidden = line.interludeStartMs < 0
        let trailing = line.secondVoice != line.rightToLeft
        [background, phonetic, translation].forEach { $0.textAlignment = trailing ? .right : .left }
        background.font = layout.backgroundFont
        phonetic.font = layout.readingFont
        translation.font = layout.readingFont
        background.text = line.background
        background.isHidden = line.background == nil
        reveal(phonetic, text: line.phonetic, badge: line.phoneticAutoCreated,
               changed: old?.phonetic != line.phonetic, reduceMotion: reduceMotion)
        reveal(translation, text: line.translation, badge: line.translationAutoCreated,
               changed: old?.translation != line.translation, reduceMotion: reduceMotion)
        revision += 1
        update(active: lastActive, positionMs: lastPosition, reduceMotion: reduceMotion)
        setNeedsLayout()
    }

    func reset() {
        line = nil
        document = nil
        layout = nil
        primary.layout = nil
        primary.line = nil
        primary.document = nil
        lastActive = false
        lastPosition = -1
        revealUntil = 0
        [background, phonetic, translation].forEach {
            $0.layer.removeAllAnimations()
            $0.alpha = 1
            $0.transform = .identity
        }
        revision += 1
    }
    private func reveal(_ label: UILabel, text: String?, badge: Bool, changed: Bool, reduceMotion: Bool) {
        label.text = text.map { badge ? SystemLyricsMetrics.autoCreatedMark + $0 : $0 }
        label.isHidden = text == nil
        guard changed else { return }
        label.layer.removeAllAnimations()
        label.alpha = reduceMotion ? 1 : 0
        label.transform = reduceMotion ? .identity : CGAffineTransform(translationX: 0, y: -6)
        if text != nil && !reduceMotion {
            revealUntil = CACurrentMediaTime() + SystemLyricsMetrics.revealSeconds
            UIView.animate(withDuration: SystemLyricsMetrics.revealSeconds, delay: 0,
                           options: [.beginFromCurrentState, .curveEaseInOut], animations: {
                label.alpha = 1
                label.transform = .identity
            })
        }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        guard let layout, let line else { return }
        primary.frame = layout.mainFrame
        dots.frame = CGRect(x: line.secondVoice != line.rightToLeft ? layout.width - 42 : 0, y: 0, width: 42, height: 14)
        for (label, frame) in [(background, layout.backgroundFrame), (phonetic, layout.phoneticFrame), (translation, layout.translationFrame)] {
            label.bounds = CGRect(origin: .zero, size: frame?.size ?? .zero)
            label.center = CGPoint(x: frame?.midX ?? 0, y: frame?.midY ?? 0)
        }
    }

    func update(active: Bool, positionMs: Int64, reduceMotion: Bool) {
        guard let document, let line else { return }
        let moving = active && !line.spans.isEmpty || (positionMs >= line.interludeStartMs && positionMs < line.interludeEndMs)
        if lastActive != active || moving && lastPosition != positionMs { revision += 1 }
        lastActive = active
        lastPosition = positionMs
        let textChanged = primary.active != active || primary.reduceMotion != reduceMotion ||
            active && !line.spans.isEmpty && primary.positionMs != positionMs
        primary.active = active
        primary.positionMs = positionMs
        primary.reduceMotion = reduceMotion
        if textChanged { primary.setNeedsDisplay() }
        let dotsChanged = dots.reduceMotion != reduceMotion || moving && dots.positionMs != positionMs
        dots.positionMs = positionMs
        dots.reduceMotion = reduceMotion
        if dotsChanged { dots.setNeedsDisplay() }
        let color = UIColor(lyricsArgb: document.colorArgb)
        let idle = document.synced ? CGFloat(document.idleAlpha) : 1
        background.textColor = color.lyricsOpacity(active ? SystemLyricsMetrics.backgroundAlpha : idle)
        phonetic.textColor = color.lyricsOpacity(active ? 0.8 : idle * 0.8)
        translation.textColor = color.lyricsOpacity(active ? 0.75 : idle * 0.75)
    }

    func displayLayers() {
        primary.layer.displayIfNeeded()
        dots.layer.displayIfNeeded()
    }
}

private final class LyricWordView: UIView {
    var layout: LyricGlyphLayout?
    var line: NativeLyricsLine?
    var document: NativeLyricsDocument?
    var active = false
    var positionMs: Int64 = 0
    var reduceMotion = false

    override init(frame: CGRect) {
        super.init(frame: frame)
        isOpaque = false
        contentMode = .redraw
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override func draw(_ rect: CGRect) {
        guard let context = UIGraphicsGetCurrentContext(), let document, let line else { return }
        layout?.draw(in: context, active: active, positionMs: positionMs,
                     idleAlpha: document.synced ? CGFloat(document.idleAlpha) : 1,
                     rightToLeft: line.rightToLeft, reduceMotion: reduceMotion,
                     minSweepMs: document.minSweepMs, glow: UIColor(lyricsArgb: document.glowArgb))
    }
}

private final class LyricInterludeView: UIView {
    var line: NativeLyricsLine?
    var color: UIColor = .label
    var positionMs: Int64 = 0
    var reduceMotion = false

    override init(frame: CGRect) {
        super.init(frame: frame)
        isOpaque = false
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override func draw(_ rect: CGRect) {
        guard let line, let context = UIGraphicsGetCurrentContext(),
              positionMs >= line.interludeStartMs, positionMs < line.interludeEndMs else { return }
        let enter = CGFloat(positionMs - line.interludeStartMs) / CGFloat(SystemLyricsMetrics.interludeEnterMs)
        let exit = CGFloat(line.interludeEndMs - positionMs) / CGFloat(SystemLyricsMetrics.interludeExitMs)
        let visibility = min(enter, exit, 1)
        let progress = min(1, max(0, CGFloat(positionMs - line.interludeStartMs) / CGFloat(max(1, line.interludeEndMs - line.interludeStartMs))))
        let phase = Double(positionMs % SystemLyricsMetrics.interludeBreathMs) / Double(SystemLyricsMetrics.interludeBreathMs)
        let breath = 0.92 + 0.16 * (0.5 - 0.5 * cos(2 * .pi * phase))
        let remaining = line.interludeEndMs - positionMs
        let anticipation = remaining > 0 && remaining <= SystemLyricsMetrics.interludeSwellMs
            ? 1.1 + 0.3 * (1 - Double(remaining) / Double(SystemLyricsMetrics.interludeSwellMs)) : 0
        let swell = reduceMotion ? 1 : CGFloat(max(breath, anticipation))
        let radius = SystemLyricsMetrics.interludeDotDiameter / 2 * swell
        for index in 0..<3 {
            let turn = progress * 3 - CGFloat(index)
            let share: CGFloat = reduceMotion ? (turn > 0 ? 1 : 0) : min(1, max(0, turn))
            let light = SystemLyricsMetrics.interludeIdleLight + (1 - SystemLyricsMetrics.interludeIdleLight) * share
            context.setFillColor(color.lyricsOpacity(visibility * light).cgColor)
            let x = SystemLyricsMetrics.interludeDotDiameter / 2 + CGFloat(index) * (SystemLyricsMetrics.interludeDotDiameter + SystemLyricsMetrics.interludeDotGap)
            context.fillEllipse(in: CGRect(x: x - radius, y: 7 - radius, width: radius * 2, height: radius * 2))
        }
    }
}

extension UIColor {
    convenience init(lyricsArgb: Int32) {
        let value = UInt32(bitPattern: lyricsArgb)
        self.init(red: CGFloat((value >> 16) & 0xFF) / 255, green: CGFloat((value >> 8) & 0xFF) / 255,
                  blue: CGFloat(value & 0xFF) / 255, alpha: CGFloat((value >> 24) & 0xFF) / 255)
    }

    func lyricsOpacity(_ value: CGFloat) -> UIColor { withAlphaComponent(cgColor.alpha * value) }
}
