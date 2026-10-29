package moe.rukamori.archivetune.shared.data.platform.ios

import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.*
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration

/** Appearance/layout only: the caller's configuration still owns its exact cookie and storage scope. */
@OptIn(ExperimentalForeignApi::class)
internal fun UIViewController.installAuthWebView(configuration: WKWebViewConfiguration): WKWebView {
    val root = view
    root.backgroundColor = UIColor.whiteColor
    val webView = WKWebView(frame = root.bounds, configuration = configuration).apply {
        opaque = true
        backgroundColor = UIColor.whiteColor
        scrollView.backgroundColor = UIColor.whiteColor
        scrollView.contentInsetAdjustmentBehavior =
            UIScrollViewContentInsetAdjustmentBehavior.UIScrollViewContentInsetAdjustmentNever
        translatesAutoresizingMaskIntoConstraints = false
    }
    root.addSubview(webView)
    // A frame/autoresizing mask follows the controller, not the keyboard or its navigation bar.
    // Leave appearance and page CSS alone; resize the real browser viewport instead of covering it.
    val safeArea = root.safeAreaLayoutGuide
    val keyboard = root.keyboardLayoutGuide
    keyboard.followsUndockedKeyboard = false
    NSLayoutConstraint.activateConstraints(
        listOf(
            webView.leadingAnchor.constraintEqualToAnchor(safeArea.leadingAnchor),
            webView.trailingAnchor.constraintEqualToAnchor(safeArea.trailingAnchor),
            webView.topAnchor.constraintEqualToAnchor(safeArea.topAnchor),
            webView.bottomAnchor.constraintEqualToAnchor(keyboard.topAnchor),
        ),
    )
    return webView
}
