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
    return KODIK_AD_URL_MARKERS.any(pathAndQuery::contains)
}

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
