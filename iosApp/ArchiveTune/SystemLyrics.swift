import UIKit
import ArchiveTuneShared

/// The native Enhanced pane is ordinary UIKit, not a system widget or a lock-screen Live Activity.
@available(iOS 26.0, *)
enum SystemLyrics {
    static func makeController(_ lyrics: IosNativeLyrics) -> UIViewController {
        SystemLyricsController(lyrics: lyrics)
    }
}

enum SystemLyricsMetrics {
    static let heldPollSeconds = 0.25
    static let anchor: CGFloat = 0.35
    static let rowSpacing: CGFloat = 22
    static let horizontalPadding: CGFloat = 28
    static let verticalMargin: CGFloat = 160
    static let backgroundRatio: CGFloat = 0.7
    static let backgroundAlpha: CGFloat = 0.72
    static let readingRatio: CGFloat = 0.55
    static let revealSeconds = 0.6
    static let activeScaleSeconds = 0.1
    static let depthSeconds = 0.45
    static let scrollSeconds = 0.35
    static let wordLift: CGFloat = 2
    static let emphasisLift: CGFloat = 2
    static let emphasisScale: CGFloat = 0.10
    static let glowAlpha: CGFloat = 0.35
    static let glowCorner: CGFloat = 8
    static let interludeDotDiameter: CGFloat = 10
    static let interludeDotGap: CGFloat = 6
    static let interludeEnterMs: Int64 = 400
    static let interludeExitMs: Int64 = 400
    static let interludeBreathMs: Int64 = 2400
    static let interludeSwellMs: Int64 = 1500
    static let interludeIdleLight: CGFloat = 0.25
    static let autoCreatedMark = "✦ "

    static func feather(of width: CGFloat) -> CGFloat { min(28, max(4, width * 0.2)) }
}

private final class SystemLyricsController: UIViewController, UITableViewDataSource, UITableViewDelegate {
    private let lyrics: IosNativeLyrics
    private var subscription: IosSubscription?
    private var document: NativeLyricsDocument?
    private var shareLabel = ""
    private var bridgeVisible = false
    private var displayed = false
    private var observers: [NSObjectProtocol] = []
    private let table = UITableView(frame: .zero, style: .plain)
    private let fade = CAGradientLayer()
    private var layouts: [Int32: SystemLyricsRowLayout] = [:]
    private var layoutWidth: CGFloat = 0
    private var lastFrame: NativeLyricsFrame?
    private var displayLink: CADisplayLink?
    private var heldTimer: Timer?
    private lazy var displayTarget = LyricsDisplayTarget { [weak self] in self?.refresh() }
    private var following = true
    private var interacting = false
    private var resumeWork: DispatchWorkItem?
    private var resumeGeneration = 0
    private var scrollAnimator: UIViewPropertyAnimator?

    init(lyrics: IosNativeLyrics) {
        self.lyrics = lyrics
        super.init(nibName: nil, bundle: nil)
        subscription = lyrics.observe { [weak self] update in
            guard let self else { return }
            let changed = self.document !== update.document
            self.document = update.document
            self.shareLabel = update.shareLabel
            self.bridgeVisible = update.visible
            if changed {
                self.layouts.removeAll()
                if self.isViewLoaded {
                    self.table.reloadData()
                    self.updateCredits()
                }
            }
            self.refresh()
        }
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    deinit {
        subscription?.cancel()
        displayLink?.invalidate()
        heldTimer?.invalidate()
        resumeWork?.cancel()
        observers.forEach { NotificationCenter.default.removeObserver($0) }
    }

    private var reduceMotion: Bool { document?.reduceMotion == true || UIAccessibility.isReduceMotionEnabled }
    private var canDraw: Bool { displayed && bridgeVisible && UIApplication.shared.applicationState != .background }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        table.backgroundColor = .clear
        table.separatorStyle = .none
        table.showsVerticalScrollIndicator = false
        table.contentInsetAdjustmentBehavior = .never
        table.contentInset = UIEdgeInsets(top: SystemLyricsMetrics.verticalMargin, left: 0,
                                          bottom: SystemLyricsMetrics.verticalMargin, right: 0)
        table.register(SystemLyricsCell.self, forCellReuseIdentifier: "lyric")
        table.dataSource = self
        table.delegate = self
        view.addSubview(table)
        fade.colors = [UIColor.clear.cgColor, UIColor.black.cgColor, UIColor.black.cgColor, UIColor.clear.cgColor]
        fade.locations = [0, 0.12, 0.82, 1]
        // The mask belongs to the viewport, not the scrolling content or the player's artwork.
        view.layer.mask = fade
        updateCredits()
        for name in [UIApplication.didBecomeActiveNotification, UIApplication.didEnterBackgroundNotification,
                     UIAccessibility.reduceMotionStatusDidChangeNotification] {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) {
                [weak self] _ in self?.refresh()
            })
        }
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        displayed = true
        refresh()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        displayed = false
        stopFrames()
        scrollAnimator?.stopAnimation(true)
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        table.frame = view.bounds
        fade.frame = view.bounds
        let width = max(1, table.bounds.width - 2 * SystemLyricsMetrics.horizontalPadding)
        if width != layoutWidth {
            layoutWidth = width
            layouts.removeAll()
            table.reloadData()
            updateCredits()
            if following, let frame = lastFrame { scroll(to: frame.primaryLine, animated: false) }
        }
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        if previousTraitCollection?.preferredContentSizeCategory != traitCollection.preferredContentSizeCategory {
            layouts.removeAll()
            table.reloadData()
            updateCredits()
            refresh()
        }
    }

    private func layout(_ line: NativeLyricsLine) -> SystemLyricsRowLayout {
        if let layout = layouts[line.index] { return layout }
        let layout = SystemLyricsRowLayout(line: line, document: document!, width: max(1, layoutWidth), traits: traitCollection)
        layouts[line.index] = layout
        return layout
    }

    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { document?.lines.count ?? 0 }

    func tableView(_ tableView: UITableView, heightForRowAt indexPath: IndexPath) -> CGFloat {
        guard let line = document?.lines[indexPath.row] else { return 0 }
        return layout(line).height + SystemLyricsMetrics.rowSpacing
    }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "lyric", for: indexPath) as! SystemLyricsCell
        configure(cell, at: indexPath)
        return cell
    }

    private func configure(_ cell: SystemLyricsCell, at indexPath: IndexPath) {
        guard let document else { return }
        let line = document.lines[indexPath.row]
        cell.configure(line: line, document: document, layout: layout(line), reduceMotion: reduceMotion)
        cell.onSeek = line.seekable ? { [weak self] in self?.lyrics.seekTo(index: line.index) } : nil
        if let text = lyrics.shareText(index: line.index) {
            cell.accessibilityCustomActions = [UIAccessibilityCustomAction(name: shareLabel) { [weak self, weak cell] _ in
                guard let self, let cell else { return false }
                self.share(text, from: cell)
                return true
            }]
        } else { cell.accessibilityCustomActions = nil }
        if let frame = lastFrame { cell.update(frame: frame, following: following && !interacting, reduceMotion: reduceMotion) }
    }

    func tableView(_ tableView: UITableView, willDisplay cell: UITableViewCell, forRowAt indexPath: IndexPath) {
        if let cell = cell as? SystemLyricsCell { configure(cell, at: indexPath) }
    }

    func tableView(_ tableView: UITableView, didEndDisplaying cell: UITableViewCell, forRowAt indexPath: IndexPath) {
        guard let lines = document?.lines, indexPath.row < lines.count else { return }
        layouts[lines[indexPath.row].index]?.main.releaseImages()
    }
    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        if let line = document?.lines[indexPath.row] { lyrics.seekTo(index: line.index) }
    }

    func tableView(_ tableView: UITableView, contextMenuConfigurationForRowAt indexPath: IndexPath,
                   point: CGPoint) -> UIContextMenuConfiguration? {
        guard let line = document?.lines[indexPath.row], let text = lyrics.shareText(index: line.index) else { return nil }
        return UIContextMenuConfiguration(identifier: nil, previewProvider: nil) { [weak self] _ in
            guard let self else { return nil }
            return UIMenu(children: [UIAction(title: self.shareLabel, image: UIImage(systemName: "square.and.arrow.up")) {
                [weak self] _ in
                guard let self else { return }
                let source: UIView = self.table.cellForRow(at: indexPath) ?? self.table
                self.share(text, from: source)
            }])
        }
    }

    private func share(_ text: String, from source: UIView) {
        guard view.window != nil, presentedViewController == nil else { return }
        let controller = UIActivityViewController(activityItems: [text], applicationActivities: nil)
        controller.popoverPresentationController?.sourceView = source
        controller.popoverPresentationController?.sourceRect = source.bounds
        present(controller, animated: true)
    }

    func scrollViewWillBeginDragging(_ scrollView: UIScrollView) {
        scrollAnimator?.stopAnimation(true)
        interacting = true
        following = false
        resumeGeneration += 1
        resumeWork?.cancel()
        updateRows()
    }

    func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) {
        if !decelerate { resumeFollowing() }
    }

    func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) { resumeFollowing() }

    private func resumeFollowing() {
        interacting = false
        resumeGeneration += 1
        let generation = resumeGeneration
        resumeWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self, self.resumeGeneration == generation, !self.interacting else { return }
            self.following = true
            self.updateRows()
            if let frame = self.lastFrame { self.scroll(to: frame.primaryLine, animated: !self.reduceMotion) }
        }
        resumeWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + Double(document?.manualScrollHoldMs ?? 0) / 1_000, execute: work)
    }

    private func refresh() {
        guard canDraw, document != nil, isViewLoaded else { stopFrames(); return }
        let frame = lyrics.frame() // Observe Kotlin's clock; neither the display link nor the timer advances it.
        let previous = lastFrame
        lastFrame = frame
        if previous == nil || previous?.discontinuity != frame.discontinuity {
            following = true
            resumeGeneration += 1
            resumeWork?.cancel()
            scroll(to: frame.primaryLine, animated: false)
        } else if following {
            if previous?.reanchor != frame.reanchor { scroll(to: frame.primaryLine, animated: false) }
            else if previous?.primaryLine != frame.primaryLine { scroll(to: frame.primaryLine, animated: !reduceMotion) }
        }
        updateRows()
        if frame.advancing && !reduceMotion {
            heldTimer?.invalidate()
            heldTimer = nil
            if displayLink == nil {
                let link = CADisplayLink(target: displayTarget, selector: #selector(LyricsDisplayTarget.tick))
                link.add(to: .main, forMode: .common)
                displayLink = link
            }
        } else {
            displayLink?.invalidate()
            displayLink = nil
            if heldTimer == nil {
                let timer = Timer(timeInterval: SystemLyricsMetrics.heldPollSeconds, repeats: true) { [weak self] _ in self?.refresh() }
                RunLoop.main.add(timer, forMode: .common)
                heldTimer = timer
            }
        }
    }

    private func stopFrames() {
        displayLink?.invalidate()
        displayLink = nil
        heldTimer?.invalidate()
        heldTimer = nil
    }

    private func updateRows() {
        guard let frame = lastFrame else { return }
        for cell in table.visibleCells {
            (cell as? SystemLyricsCell)?.update(frame: frame, following: following && !interacting, reduceMotion: reduceMotion)
        }
    }

    private func scroll(to index: Int32, animated: Bool) {
        guard let document, index >= 0, Int(index) < document.lines.count, table.bounds.height > 0 else { return }
        table.layoutIfNeeded()
        let rect = table.rectForRow(at: IndexPath(row: Int(index), section: 0))
        let rowHeight = max(0, rect.height - SystemLyricsMetrics.rowSpacing)
        let desired = rect.minY + rowHeight * SystemLyricsMetrics.anchor - table.bounds.height * SystemLyricsMetrics.anchor
        let minimum = -table.contentInset.top
        let maximum = max(minimum, table.contentSize.height - table.bounds.height + table.contentInset.bottom)
        let offset = CGPoint(x: 0, y: min(maximum, max(minimum, desired)))
        scrollAnimator?.stopAnimation(true)
        if animated {
            let timing = UICubicTimingParameters(controlPoint1: CGPoint(x: 0.42, y: 0), controlPoint2: CGPoint(x: 0.58, y: 1))
            let animation = UIViewPropertyAnimator(duration: SystemLyricsMetrics.scrollSeconds, timingParameters: timing)
            animation.addAnimations { [weak self] in self?.table.contentOffset = offset }
            scrollAnimator = animation
            animation.startAnimation()
        } else { table.setContentOffset(offset, animated: false) }
    }

    private func updateCredits() {
        guard let document, let credits = document.credits else { table.tableFooterView = nil; return }
        let text = [credits.writtenBy, credits.autoCreatedLabel.map { SystemLyricsMetrics.autoCreatedMark + $0 }]
            .compactMap { $0 }.joined(separator: "\n")
        guard !text.isEmpty else { table.tableFooterView = nil; return }
        let label = UILabel()
        let size = UIFontMetrics(forTextStyle: .largeTitle).scaledValue(for: CGFloat(document.mainSizeSp), compatibleWith: traitCollection)
        label.font = .systemFont(ofSize: size * SystemLyricsMetrics.readingRatio, weight: .medium)
        label.textColor = UIColor(lyricsArgb: document.colorArgb).lyricsOpacity(document.synced ? CGFloat(document.idleAlpha) : 1)
        label.text = text
        label.numberOfLines = 0
        label.textAlignment = .center
        let footer = UIView()
        footer.addSubview(label)
        let width = max(1, table.bounds.width - 2 * SystemLyricsMetrics.horizontalPadding)
        let height = label.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude)).height
        label.frame = CGRect(x: SystemLyricsMetrics.horizontalPadding, y: 16, width: width, height: height)
        footer.frame = CGRect(x: 0, y: 0, width: table.bounds.width, height: height + 32)
        table.tableFooterView = footer
    }
}

/// CADisplayLink retains its target; this trampoline's callback holds the controller weakly.
private final class LyricsDisplayTarget: NSObject {
    private let action: () -> Void
    init(_ action: @escaping () -> Void) { self.action = action }
    @objc func tick() { action() }
}
