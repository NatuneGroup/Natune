import UIKit
import ImageIO
import ArchiveTuneShared

/// One Compose child with a UIKit-only glass overlay. Kotlin remains the tab, player and visibility owner.
@available(iOS 26.0, *)
final class NativeChromeController: UIViewController {
    private let compose: UIViewController
    private let chrome: IosNativeChrome
    private var subscription: IosSubscription?
    private var state: IosChromeState?
    private let glass = ChromeGlassContainer()
    private let mini = ChromeGlassSurface()
    private let tabs = ChromeGlassSurface()
    private let search = ChromeGlassSurface()
    private let artwork = ChromeArtworkView()
    private let info = UIControl()
    private let titleLabel = UILabel()
    private let subtitleLabel = UILabel()
    private let playButton = UIButton(type: .system)
    private let homeButton = ChromeTabButton(symbol: "house.fill")
    private let libraryButton = ChromeTabButton(symbol: "books.vertical.fill")
    private let searchButton = UIButton(type: .system)
    private var animator: UIViewPropertyAnimator?

    init(compose: UIViewController, chrome: IosNativeChrome) {
        self.compose = compose
        self.chrome = chrome
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override var childForStatusBarStyle: UIViewController? { compose }
    override var childForStatusBarHidden: UIViewController? { compose }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        addChild(compose)
        view.addSubview(compose.view)
        compose.didMove(toParent: self)
        view.addSubview(glass)
        [mini, tabs, search].forEach { glass.contentView.addSubview($0) }
        glass.surfaces = [mini, tabs, search]
        mini.contentView.addSubview(info)
        artwork.isUserInteractionEnabled = false
        info.addSubview(artwork)
        info.addSubview(titleLabel)
        info.addSubview(subtitleLabel)
        mini.contentView.addSubview(playButton)
        info.isAccessibilityElement = true
        info.accessibilityTraits = .button
        info.addTarget(self, action: #selector(expand), for: .touchUpInside)
        playButton.addTarget(self, action: #selector(togglePlayback), for: .touchUpInside)
        [titleLabel, subtitleLabel].forEach {
            $0.numberOfLines = 1
            $0.minimumScaleFactor = 0.7
            $0.adjustsFontSizeToFitWidth = true
            $0.isAccessibilityElement = false
        }
        titleLabel.textColor = .label
        subtitleLabel.textColor = .secondaryLabel
        tabs.contentView.addSubview(homeButton)
        tabs.contentView.addSubview(libraryButton)
        homeButton.addTarget(self, action: #selector(home), for: .touchUpInside)
        libraryButton.addTarget(self, action: #selector(library), for: .touchUpInside)
        search.contentView.addSubview(searchButton)
        searchButton.setImage(UIImage(systemName: "magnifyingglass"), for: .normal)
        searchButton.setPreferredSymbolConfiguration(.init(pointSize: 20, weight: .semibold), forImageIn: .normal)
        searchButton.addTarget(self, action: #selector(find), for: .touchUpInside)
        playButton.setPreferredSymbolConfiguration(.init(pointSize: 20), forImageIn: .normal)
        updateFonts()
        glass.alpha = 0
        glass.isHidden = true
        subscription = chrome.observe { [weak self] next in self?.apply(next) }
        chrome.setSupported(value: true)
    }

    deinit { subscription?.cancel() }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        compose.view.frame = view.bounds
        layoutChrome()
    }

    override func viewSafeAreaInsetsDidChange() {
        super.viewSafeAreaInsetsDidChange()
        view.setNeedsLayout()
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        if previousTraitCollection?.preferredContentSizeCategory != traitCollection.preferredContentSizeCategory {
            updateFonts()
            view.setNeedsLayout()
        }
    }

    private func updateFonts() {
        titleLabel.font = chromeFont(size: 15, weight: .semibold, style: .subheadline)
        subtitleLabel.font = chromeFont(size: 12, weight: .regular, style: .caption1)
    }

    private func layoutChrome() {
        // Window geometry, not a device-name test: rotation, Split View, Stage Manager and Fold share this rule.
        let width = max(0, min(520, view.bounds.width - 36))
        let hasSong = state?.hasNowPlaying == true
        let height: CGFloat = hasSong ? 124 : 58
        let bottom = max(view.safeAreaInsets.bottom, view.window?.safeAreaInsets.bottom ?? 0) + 6
        glass.bounds = CGRect(x: 0, y: 0, width: width, height: height)
        glass.center = CGPoint(x: view.bounds.midX, y: view.bounds.height - bottom - height / 2)
        mini.isHidden = !hasSong
        mini.frame = CGRect(x: 0, y: 0, width: width, height: 56)
        let tabY: CGFloat = hasSong ? 66 : 0
        let tabWidth = max(0, width - 70)
        tabs.frame = CGRect(x: 0, y: tabY, width: tabWidth, height: 58)
        search.frame = CGRect(x: width - 58, y: tabY, width: 58, height: 58)
        searchButton.frame = search.bounds
        homeButton.frame = CGRect(x: 4, y: 4, width: max(0, (tabWidth - 8) / 2), height: 50)
        libraryButton.frame = homeButton.frame.offsetBy(dx: homeButton.frame.width, dy: 0)
        info.frame = CGRect(x: 0, y: 0, width: max(0, width - 50), height: 56)
        artwork.frame = CGRect(x: 8, y: 8, width: 40, height: 40)
        let labelWidth = max(0, info.bounds.width - 62)
        let titleHeight = ceil(titleLabel.font.lineHeight)
        let subtitleHeight = subtitleLabel.isHidden ? 0 : ceil(subtitleLabel.font.lineHeight) + 1
        let top = (56 - titleHeight - subtitleHeight) / 2
        titleLabel.frame = CGRect(x: 60, y: top, width: labelWidth, height: titleHeight)
        subtitleLabel.frame = CGRect(x: 60, y: top + titleHeight + 1, width: labelWidth, height: max(0, subtitleHeight - 1))
        playButton.frame = CGRect(x: width - 50, y: 6, width: 44, height: 44)
        chrome.setBottomInset(points: Double(bottom + height))
    }

    private func apply(_ next: IosChromeState) {
        let previous = state
        state = next
        titleLabel.text = next.title
        subtitleLabel.text = next.subtitle
        subtitleLabel.isHidden = next.subtitle.isEmpty
        info.accessibilityLabel = [next.title, next.subtitle].filter { !$0.isEmpty }.joined(separator: ", ")
        info.accessibilityHint = next.expandLabel
        artwork.load(next.artworkUrl)
        playButton.accessibilityLabel = next.playing ? next.pauseLabel : next.playLabel
        let replaceSymbol = {
            self.playButton.setImage(UIImage(systemName: next.playing ? "pause.fill" : "play.fill"), for: .normal)
        }
        if previous?.playing != next.playing && previous != nil && !UIAccessibility.isReduceMotionEnabled {
            UIView.transition(with: playButton, duration: 0.2, options: .transitionCrossDissolve, animations: replaceSymbol)
        } else { replaceSymbol() }
        let selectTabs = {
            self.homeButton.apply(label: next.homeLabel, selected: next.selectedTab == 0)
            self.libraryButton.apply(label: next.libraryLabel, selected: next.selectedTab == 2)
            self.searchButton.tintColor = next.selectedTab == 1 ? self.view.tintColor : .label
        }
        if previous != nil && previous?.selectedTab != next.selectedTab && !UIAccessibility.isReduceMotionEnabled {
            UIView.animate(withDuration: 0.25, delay: 0, usingSpringWithDamping: 0.86, initialSpringVelocity: 0,
                           options: [.beginFromCurrentState, .allowUserInteraction], animations: selectTabs)
        } else { selectTabs() }
        searchButton.accessibilityLabel = next.searchLabel
        searchButton.accessibilityTraits = next.selectedTab == 1 ? [.button, .selected] : [.button]
        animator?.stopAnimation(true)
        glass.isHidden = false
        // Hidden chrome takes no touches, even while it finishes its exit animation.
        glass.isUserInteractionEnabled = next.visible
        view.layoutIfNeeded()
        let changes = {
            self.layoutChrome()
            self.glass.alpha = next.visible ? 1 : 0
            self.glass.transform = next.visible ? .identity : CGAffineTransform(translationX: 0, y: 20)
        }
        let visibilityChanged = previous?.visible != next.visible || previous?.hasNowPlaying != next.hasNowPlaying
        if visibilityChanged && previous != nil && !UIAccessibility.isReduceMotionEnabled {
            let animation = UIViewPropertyAnimator(duration: 0.42, dampingRatio: 0.86, animations: changes)
            animation.addCompletion { [weak self] _ in
                guard let self else { return }
                self.glass.isHidden = self.state?.visible != true
            }
            animator = animation
            animation.startAnimation()
        } else {
            changes()
            glass.isHidden = !next.visible
        }
    }

    func close() {
        subscription?.cancel()
        subscription = nil
        animator?.stopAnimation(true)
        chrome.setSupported(value: false)
        chrome.setBottomInset(points: 0)
    }

    @objc private func expand() { chrome.expandPlayer() }
    @objc private func togglePlayback() { chrome.togglePlayback() }
    @objc private func home() { chrome.selectTab(index: 0) }
    @objc private func library() { chrome.selectTab(index: 2) }
    @objc private func find() { chrome.selectTab(index: 1) }
}

@available(iOS 26.0, *)
private final class ChromeGlassContainer: UIVisualEffectView {
    var surfaces: [UIView] = []

    init() {
        let effect = UIGlassContainerEffect()
        effect.spacing = 14
        super.init(effect: effect)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        guard isUserInteractionEnabled, !isHidden, alpha > 0.01 else { return nil }
        // The glass container's empty gaps must not steal scrolling or taps from Compose.
        guard surfaces.contains(where: { !$0.isHidden && $0.point(inside: $0.convert(point, from: self), with: event) }) else {
            return nil
        }
        return super.hitTest(point, with: event)
    }
}

@available(iOS 26.0, *)
private final class ChromeGlassSurface: UIVisualEffectView {
    init() {
        let effect = UIGlassEffect(style: .regular)
        effect.isInteractive = true
        super.init(effect: effect)
        clipsToBounds = true
        layer.cornerCurve = .continuous
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override func layoutSubviews() {
        super.layoutSubviews()
        layer.cornerRadius = bounds.height / 2
    }

    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
        UIBezierPath(roundedRect: bounds, cornerRadius: bounds.height / 2).contains(point)
    }
}

private func chromeFont(size: CGFloat, weight: UIFont.Weight, style: UIFont.TextStyle) -> UIFont {
    let metrics = UIFontMetrics(forTextStyle: style)
    let cap = metrics.scaledValue(for: size, compatibleWith: UITraitCollection(preferredContentSizeCategory: .extraExtraLarge))
    return metrics.scaledFont(for: .systemFont(ofSize: size, weight: weight), maximumPointSize: cap)
}

private final class ChromeTabButton: UIControl {
    private let image = UIImageView()
    private let label = UILabel()

    init(symbol: String) {
        super.init(frame: .zero)
        image.image = UIImage(systemName: symbol, withConfiguration: UIImage.SymbolConfiguration(pointSize: 19, weight: .semibold))
        image.contentMode = .scaleAspectFit
        addSubview(image)
        addSubview(label)
        label.textAlignment = .center
        label.minimumScaleFactor = 0.7
        label.adjustsFontSizeToFitWidth = true
        label.isAccessibilityElement = false
        layer.cornerRadius = 25
        layer.cornerCurve = .continuous
        isAccessibilityElement = true
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    func apply(label text: String, selected: Bool) {
        label.text = text
        isSelected = selected
        accessibilityLabel = text
        accessibilityTraits = selected ? [.button, .selected] : [.button]
        image.tintColor = selected ? tintColor : .label
        label.textColor = selected ? tintColor : .label
        backgroundColor = selected ? UIColor.label.withAlphaComponent(0.08) : .clear
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        label.font = chromeFont(size: 11, weight: .medium, style: .caption2)
        let height = ceil(label.font.lineHeight)
        let top = (bounds.height - 21 - 2 - height) / 2
        image.frame = CGRect(x: (bounds.width - 24) / 2, y: top, width: 24, height: 21)
        label.frame = CGRect(x: 3, y: top + 23, width: max(0, bounds.width - 6), height: height)
    }
}

/// HTTPS-only artwork with cancellable requests, capped decoding and no shared account cookie jar.
private final class ChromeArtworkView: UIView {
    private let image = UIImageView()
    private let placeholder = UIImageView(image: UIImage(systemName: "music.note"))
    private var task: URLSessionDownloadTask?
    private var url: String?
    private var generation = 0
    private let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpShouldSetCookies = false
        configuration.httpCookieStorage = nil
        configuration.urlCache = nil
        configuration.timeoutIntervalForResource = 30
        return URLSession(configuration: configuration)
    }()

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .quaternarySystemFill
        layer.cornerRadius = 8
        clipsToBounds = true
        image.contentMode = .scaleAspectFill
        placeholder.contentMode = .scaleAspectFit
        placeholder.tintColor = .secondaryLabel
        addSubview(placeholder)
        addSubview(image)
        isAccessibilityElement = false
        accessibilityElementsHidden = true
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    deinit { session.invalidateAndCancel() }

    override func layoutSubviews() {
        super.layoutSubviews()
        image.frame = bounds
        placeholder.frame = bounds.insetBy(dx: 10, dy: 10)
    }

    func load(_ value: String?) {
        guard url != value else { return }
        url = value
        generation += 1
        let expected = generation
        task?.cancel()
        task = nil
        image.image = nil
        guard let value, let parsed = URL(string: value), parsed.scheme == "https" else { return }
        task = session.downloadTask(with: parsed) { [weak self] location, response, error in
            guard error == nil, let location else { return }
            defer { try? FileManager.default.removeItem(at: location) }
            guard let response = response as? HTTPURLResponse, (200..<300).contains(response.statusCode),
                  let attributes = try? FileManager.default.attributesOfItem(atPath: location.path),
                  let size = attributes[.size] as? NSNumber, size.intValue <= 4 * 1024 * 1024,
                  let source = CGImageSourceCreateWithURL(location as CFURL, nil),
                  let cover = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                    kCGImageSourceCreateThumbnailFromImageAlways: true,
                    kCGImageSourceCreateThumbnailMaxPixelSize: 160,
                    kCGImageSourceCreateThumbnailWithTransform: true,
                  ] as CFDictionary) else { return }
            let loaded = UIImage(cgImage: cover)
            DispatchQueue.main.async { [weak self] in
                guard let self, self.generation == expected else { return }
                self.image.image = loaded
            }
        }
        task?.resume()
    }
}
