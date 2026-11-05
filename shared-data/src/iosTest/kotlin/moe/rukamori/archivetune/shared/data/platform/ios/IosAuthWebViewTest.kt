package moe.rukamori.archivetune.shared.data.platform.ios

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.*
import platform.UIKit.NSLayoutAttributeBottom
import platform.UIKit.NSLayoutAttributeTop
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore

@OptIn(ExperimentalForeignApi::class)
class IosAuthWebViewTest {
    @Test
    fun authCanvasIsOpaqueWithoutForcingThePagesAppearanceOrReplacingItsStore() {
        val store = WKWebsiteDataStore.nonPersistentDataStore()
        val configuration = WKWebViewConfiguration().apply { websiteDataStore = store }
        val webView = UIViewController().installAuthWebView(configuration)

        assertTrue(webView.opaque)
        assertEquals(UIColor.whiteColor, webView.backgroundColor)
        assertEquals(store, webView.configuration.websiteDataStore)
        assertEquals(UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified, webView.overrideUserInterfaceStyle)
    }

    @Test
    fun browserViewportUsesSafeAreaAndKeyboardConstraintsInsteadOfAFullScreenFrame() {
        val controller = UIViewController()
        val webView = controller.installAuthWebView(WKWebViewConfiguration())
        val constraints = controller.view.constraints.filterIsInstance<NSLayoutConstraint>()
        assertFalse(webView.translatesAutoresizingMaskIntoConstraints)
        assertTrue(constraints.any {
            it.firstItem == webView && it.firstAttribute == NSLayoutAttributeTop &&
                it.secondItem == controller.view.safeAreaLayoutGuide &&
                it.secondAttribute == NSLayoutAttributeTop
        })
        assertTrue(constraints.any {
            it.firstItem == webView && it.firstAttribute == NSLayoutAttributeBottom &&
                it.secondItem == controller.view.keyboardLayoutGuide &&
                it.secondAttribute == NSLayoutAttributeTop
        })
    }
}
