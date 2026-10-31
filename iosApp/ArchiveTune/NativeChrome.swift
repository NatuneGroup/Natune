import SwiftUI
import UIKit
import ArchiveTuneShared

/// Mirrors the Kotlin chrome state for SwiftUI. Kotlin delivers every update on the main thread.
final class NativeChromeModel: ObservableObject {
    @Published private(set) var state: IosChromeState?
    let chrome: IosNativeChrome
    private var subscription: IosSubscription?
    private var screenBottom: CGFloat = 0
    private var chromeTop: CGFloat = 0

    init(chrome: IosNativeChrome) {
        self.chrome = chrome
        subscription = chrome.observe { [weak self] next in
            self?.state = next
        }
        // Only the iOS 26 branch creates this model, so reaching here means the system glass is drawn.
        chrome.setSupported(value: true)
    }

    deinit {
        subscription?.cancel()
        chrome.setSupported(value: false)
    }

    func screenBottomChanged(_ value: CGFloat) {
        screenBottom = value
        publishInset()
    }

    func chromeTopChanged(_ value: CGFloat) {
        chromeTop = value
        publishInset()
    }

    /// Compose measures in points on iOS, so the inset is handed over as is.
    private func publishInset() {
        guard screenBottom > 0, chromeTop > 0 else { return }
        chrome.setBottomInset(points: Double(max(0, screenBottom - chromeTop)))
    }
}

/// The shared Compose root with the system Liquid Glass tab bar and mini player drawn above it. The Compose layer
/// stays one controller underneath, so the glass samples the real pages. Only the bar and the mini player take
/// touches; everything else falls through to Compose.
@available(iOS 26.0, *)
struct NativeChromeHost<Content: View>: View {
    @StateObject private var model: NativeChromeModel
    private let content: Content

    init(chrome: IosNativeChrome, @ViewBuilder content: () -> Content) {
        _model = StateObject(wrappedValue: NativeChromeModel(chrome: chrome))
        self.content = content()
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            content
            NativeChromeBar(model: model)
        }
        // A Compose text field raises the keyboard; the bar stays at the bottom behind it instead of riding up.
        .ignoresSafeArea(.keyboard, edges: .bottom)
        .onGeometryChange(for: CGFloat.self) { proxy in
            proxy.frame(in: .global).maxY + proxy.safeAreaInsets.bottom
        } action: { bottom in
            model.screenBottomChanged(bottom)
        }
    }
}

@available(iOS 26.0, *)
private struct NativeChromeBar: View {
    @ObservedObject var model: NativeChromeModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let state = model.state
        let visible = state?.visible ?? false
        VStack(spacing: 0) {
            if let state, visible {
                GlassEffectContainer(spacing: 14) {
                    VStack(spacing: 10) {
                        if state.hasNowPlaying {
                            NativeMiniPlayer(state: state, chrome: model.chrome)
                        }
                        NativeTabRow(state: state, chrome: model.chrome, reduceMotion: reduceMotion)
                    }
                }
                .padding(.horizontal, 18)
                .padding(.bottom, 6)
                .onGeometryChange(for: CGFloat.self) { proxy in
                    proxy.frame(in: .global).minY
                } action: { top in
                    model.chromeTopChanged(top)
                }
                .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(reduceMotion ? nil : .spring(response: 0.42, dampingFraction: 0.86), value: visible)
        .animation(reduceMotion ? nil : .spring(response: 0.42, dampingFraction: 0.86), value: state?.hasNowPlaying ?? false)
    }
}

@available(iOS 26.0, *)
private struct NativeMiniPlayer: View {
    let state: IosChromeState
    let chrome: IosNativeChrome

    var body: some View {
        HStack(spacing: 12) {
            NativeArtwork(url: state.artworkUrl)
            VStack(alignment: .leading, spacing: 1) {
                Text(state.title)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                    .allowsTightening(true)
                if !state.subtitle.isEmpty {
                    Text(state.subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                        .allowsTightening(true)
                }
            }
            // The capsule has a fixed height, so two lines of the largest text sizes would be clipped.
            .dynamicTypeSize(...DynamicTypeSize.xxLarge)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isButton)
            .accessibilityHint(Text(state.expandLabel))
            Button {
                chrome.togglePlayback()
            } label: {
                Image(systemName: state.playing ? "pause.fill" : "play.fill")
                    .font(.title3)
                    .contentTransition(.symbolEffect(.replace))
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(state.playing ? state.pauseLabel : state.playLabel))
        }
        .padding(.leading, 8)
        .padding(.trailing, 6)
        .frame(height: 56)
        .contentShape(Capsule())
        .onTapGesture { chrome.expandPlayer() }
        .glassEffect(.regular.interactive(), in: .capsule)
    }
}

@available(iOS 26.0, *)
private struct NativeTabRow: View {
    let state: IosChromeState
    let chrome: IosNativeChrome
    let reduceMotion: Bool

    var body: some View {
        HStack(spacing: 12) {
            HStack(spacing: 0) {
                NativeTabButton(symbol: "house.fill", label: state.homeLabel, selected: state.selectedTab == 0) {
                    chrome.selectTab(index: 0)
                }
                NativeTabButton(symbol: "books.vertical.fill", label: state.libraryLabel, selected: state.selectedTab == 2) {
                    chrome.selectTab(index: 2)
                }
            }
            .padding(4)
            .animation(reduceMotion ? nil : .snappy, value: state.selectedTab)
            .glassEffect(.regular.interactive(), in: .capsule)
            Button {
                chrome.selectTab(index: 1)
            } label: {
                Image(systemName: "magnifyingglass")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(state.selectedTab == 1 ? AnyShapeStyle(.tint) : AnyShapeStyle(.primary))
                    .frame(width: 58, height: 58)
                    .contentShape(Circle())
            }
            .buttonStyle(.plain)
            .glassEffect(.regular.interactive(), in: .circle)
            .accessibilityLabel(Text(state.searchLabel))
            .accessibilityAddTraits(state.selectedTab == 1 ? .isSelected : [])
        }
    }
}

@available(iOS 26.0, *)
private struct NativeTabButton: View {
    let symbol: String
    let label: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 2) {
                Image(systemName: symbol)
                    .font(.system(size: 19, weight: .semibold))
                Text(label)
                    .font(.caption2.weight(.medium))
                    .lineLimit(1)
                    // A long translated tab name shrinks to fit its half of the capsule instead of truncating.
                    .minimumScaleFactor(0.7)
                    .allowsTightening(true)
                    .dynamicTypeSize(...DynamicTypeSize.xxLarge)
            }
            .foregroundStyle(selected ? AnyShapeStyle(.tint) : AnyShapeStyle(.primary))
            .frame(maxWidth: .infinity, minHeight: 50)
            .background {
                if selected {
                    Capsule().fill(Color.primary.opacity(0.08))
                }
            }
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// Remote cover art for the mini player. Artwork that only the Kotlin loader can read keeps the placeholder.
private struct NativeArtwork: View {
    let url: String?

    var body: some View {
        Group {
            if let url, let parsed = URL(string: url), parsed.scheme == "https" {
                AsyncImage(url: parsed) { phase in
                    if let image = phase.image {
                        image.resizable().scaledToFill()
                    } else {
                        placeholder
                    }
                }
            } else {
                placeholder
            }
        }
        .frame(width: 40, height: 40)
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
        .accessibilityHidden(true)
    }

    private var placeholder: some View {
        RoundedRectangle(cornerRadius: 8, style: .continuous)
            .fill(.quaternary)
            .overlay(Image(systemName: "music.note").foregroundStyle(.secondary))
    }
}
