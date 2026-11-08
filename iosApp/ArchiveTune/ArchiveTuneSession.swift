import UIKit
import Darwin
import ArchiveTuneShared

/// One application-owned graph. Controller detach, recreation and backgrounding never release its services.
final class ArchiveTuneSession {
    static let shared = ArchiveTuneSession()

    private let host = IosHost()
    private var observers: [NSObjectProtocol] = []
    private var presenter: AnyObject?
    private var closed = false
    private lazy var rootController: UIViewController = IosRootController(
        compose: host.viewController,
        safeArea: host.safeArea,
    )

    private init() {
        IosFatalExceptionRecord.prepare()
        NSSetUncaughtExceptionHandler { exception in
            IosFatalExceptionRecord.record(exception)
        }
        // Launch-time registration precedes the first activation and the pack's foreground consideration.
        observers.append(NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main
        ) { [host] _ in
            host.foregrounded()
        })
        observers.append(NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main
        ) { [host] _ in
            host.backgrounded()
        })
        observers.append(NotificationCenter.default.addObserver(
            forName: UIApplication.willTerminateNotification, object: nil, queue: .main
        ) { [weak self] _ in
            self?.shutdown()
        })
    }

    /// Re-presents the same UIKit container and Compose child, never a second host or per-tab controller.
    func present(for token: AnyObject) -> UIViewController {
        let controller = rootController
        if let stale = presenter, stale !== token {
            controller.willMove(toParent: nil)
            controller.view.removeFromSuperview()
            controller.removeFromParent()
        }
        presenter = token
        return controller
    }

    /// A stale dismantle never clears a newer representable's claim.
    func dismiss(for token: AnyObject) {
        if presenter === token { presenter = nil }
    }

    /// Both scheme opens and browsing activities use Kotlin's existing bounded link intake and classifier.
    /// HTTPS activation still needs associated domains; forwarding an activity grants no entitlement.
    func accept(_ url: URL) {
        if url.scheme == "natune", url.host == "widget",
           url.query == nil, url.fragment == nil, url.user == nil, url.password == nil,
           url.port == nil, ["/open", "/play", "/pause", "/next", "/previous"].contains(url.path) {
            if url.path != "/open" { host.widgetCommand(action: String(url.path.dropFirst())) }
            return
        }
        host.openLink(link: url.absoluteString)
    }

    func shutdown() {
        guard !closed else { return }
        closed = true
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        observers.removeAll()
        presenter = nil
        host.close()
    }
}

/// The fatal Objective-C exception path runs with the runtime state already undefined, so it uses fixed local byte
/// operations and POSIX calls rather than allocating formatters: no Kotlin runtime, no report objects, no file open.
/// The record goes through a descriptor opened at launch, and the next launch classifies it into the normal crash report.
private enum IosFatalExceptionRecord {
    private static let capacity = 4096
    private static let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: IosFatalExceptionRecord.capacity)
    private static var descriptor: Int32 = -1

    // C-variadic snprintf is unavailable from Swift, so the record is emitted as bytes with no formatter allocation.
    private static let exceptionPrefix: StaticString = "Objective-C uncaught exception: "
    private static let nameByteLimit = 200
    private static let reasonByteLimit = 2000

    /// Opens the record once, at launch, and never truncates it here: the previous launch's record is read first.
    /// The fatal path may allocate nothing, so the lazily allocated static buffer is initialized here as well.
    static func prepare() {
        buffer.initialize(repeating: 0, count: capacity)
        guard let path = IosCrashHooksKt.iosNativeCrashRecordPath() else { return }
        descriptor = path.withCString { open($0, O_WRONLY | O_CREAT, 0o600) }
    }

    /// Truncates and writes the pre-opened record. The call stack is dropped: reading it allocates in a process whose
    /// runtime state is not trustworthy, and a lost record is worse than a short one.
    static func record(_ exception: NSException) {
        guard descriptor >= 0 else { return }
        _ = ftruncate(descriptor, 0)
        _ = lseek(descriptor, 0, SEEK_SET)
        var used = 0
        writeInt64(Int64(time(nil)), into: &used)
        writeByte(0x0A, into: &used)
        writeStaticString(exceptionPrefix, into: &used)
        writeUTF8(exception.name.rawValue.utf8, limit: nameByteLimit, into: &used)
        writeByte(0x0A, into: &used)
        if let reason = exception.reason {
            writeUTF8(reason.utf8, limit: reasonByteLimit, into: &used)
        }
        writeByte(0x0A, into: &used)
        buffer[used] = 0
        _ = write(descriptor, UnsafeRawPointer(buffer), used)
    }

    private static func writeByte(_ byte: UInt8, into used: inout Int) {
        guard used < capacity - 1 else { return }
        buffer[used] = byte
        used += 1
    }

    private static func writeStaticString(_ value: StaticString, into used: inout Int) {
        var index = 0
        while index < value.utf8CodeUnitCount {
            let byte = value.utf8Start[index]
            if byte == 0 { return }
            writeByte(byte, into: &used)
            index += 1
        }
    }

    private static func writeUTF8(_ value: String.UTF8View, limit: Int, into used: inout Int) {
        var copied = 0
        for byte in value {
            if byte == 0 || copied >= limit || used >= capacity - 1 { return }
            writeByte(byte, into: &used)
            copied += 1
        }
    }

    private static func writeInt64(_ value: Int64, into used: inout Int) {
        if value < 0 { writeByte(0x2D, into: &used) }
        // `magnitude` is the unsigned two's-complement value, so Int64.min needs no negation.
        let magnitude = value.magnitude
        if magnitude == 0 {
            writeByte(0x30, into: &used)
            return
        }
        var divisor: UInt64 = 1
        while divisor <= magnitude / 10 { divisor *= 10 }
        while divisor > 0 {
            writeByte(UInt8(0x30) &+ UInt8((magnitude / divisor) % 10), into: &used)
            divisor /= 10
        }
    }
}

/// The one OS bridge the shared app needs here: the window's safe-area insets for a full-bleed Compose controller.
/// It draws no content of its own, so the shared Compose tree stays the only UI owner on iOS.
final class IosRootController: UIViewController {
    private let compose: UIViewController
    private let safeArea: IosSafeArea

    init(compose: UIViewController, safeArea: IosSafeArea) {
        self.compose = compose
        self.safeArea = safeArea
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override var childForStatusBarStyle: UIViewController? { compose }
    override var childForStatusBarHidden: UIViewController? { compose }

    override func viewDidLoad() {
        super.viewDidLoad()
        // The shared theme paints the whole window, strips included; a system colour here would show through as a
        // white or light strip around a dark or tinted palette.
        view.backgroundColor = .clear
        addChild(compose)
        compose.view.frame = view.bounds
        compose.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(compose.view)
        compose.didMove(toParent: self)
    }

    override func viewSafeAreaInsetsDidChange() {
        super.viewSafeAreaInsetsDidChange()
        reportSafeArea()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        reportSafeArea()
    }

    private func reportSafeArea() {
        let insets = view.safeAreaInsets
        // Kotlin exports the parameters in declaration order, and Swift passes labelled arguments in that order.
        safeArea.update(left: Double(insets.left), top: Double(insets.top),
                        right: Double(insets.right), bottom: Double(insets.bottom))
    }
}
