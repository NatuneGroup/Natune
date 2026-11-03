package moe.rukamori.archivetune.shared.data.platform.ios

import moe.rukamori.archivetune.shared.data.news.DEFAULT_NATUNE_NEWS_URL
import platform.Foundation.NSBundle

/**
 * The News feed this build names through the NATUNE_NEWS_URL build setting, which Xcode writes into the Info.plist as
 * `NatuneNewsUrl`. Native.xcconfig carries the default feed, so an explicitly blank setting is a choice: it disables the
 * feed and hides the News entry. A bundle without the key, or with an unexpanded reference, keeps the default.
 */
fun iosNatuneNewsUrl(): String {
    val value = NSBundle.mainBundle.objectForInfoDictionaryKey("NatuneNewsUrl") as? String ?: return DEFAULT_NATUNE_NEWS_URL
    val trimmed = value.trim()
    return if (trimmed.startsWith("$(")) DEFAULT_NATUNE_NEWS_URL else trimmed
}
