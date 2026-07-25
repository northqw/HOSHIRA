package dev.aniliberty.desktop.ui

import java.net.URI

internal fun shouldBlockKodikRequest(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase().orEmpty()
    if (KODIK_BLOCKED_AD_HOSTS.any { blocked ->
            host == blocked || host.endsWith(".$blocked")
        }
    ) {
        return true
    }

    val pathAndQuery = buildString {
        append(uri.rawPath.orEmpty())
        uri.rawQuery?.let {
            append('?')
            append(it)
        }
    }.lowercase()
    val isKodikFirstParty =
        host == "kodikplayer.com" || host.endsWith(".kodikplayer.com")
    if (
        isKodikFirstParty &&
        pathAndQuery.contains("/preroll/") &&
        pathAndQuery.substringBefore('?').endsWith("/config.json")
    ) {
        // Kodik needs this small first-party manifest to finish initializing
        // its playback state. Blocking it makes the provider wait for a retry
        // timeout before it creates the actual <video>.
        return false
    }
    return KODIK_AD_URL_MARKERS.any(pathAndQuery::contains)
}

internal val KODIK_WEB_RESOURCE_FILTERS = listOf(
    "*doubleclick.net/*",
    "*googlesyndication.com/*",
    "*googleadservices.com/*",
    "*adfox.ru/*",
    "*adriver.ru/*",
    "*yandexadexchange.net/*",
    "*an.yandex.ru/*",
    "*mytarget.ru/*",
    "*relap.io/*",
    "*buzzoola.com/*",
    "*between.digital/*",
    "*/*ads/*",
    "*/*adv/*",
    "*/*advert/*",
    "*/*preroll/*",
    "*vpaid*",
    "*pre-roll*",
    "*vast*",
)

private val KODIK_BLOCKED_AD_HOSTS = setOf(
    "doubleclick.net",
    "googlesyndication.com",
    "googleadservices.com",
    "adfox.ru",
    "adriver.ru",
    "yandexadexchange.net",
    "an.yandex.ru",
    "mytarget.ru",
    "relap.io",
    "buzzoola.com",
    "between.digital",
)

private val KODIK_AD_URL_MARKERS = listOf(
    "/ads/",
    "/adv/",
    "/advert/",
    "/preroll/",
    "vast?",
    "vast/",
    "vpaid",
    "pre-roll",
    "preroll",
)
