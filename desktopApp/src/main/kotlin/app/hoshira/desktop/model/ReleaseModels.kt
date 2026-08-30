package app.hoshira.desktop.model

import androidx.compose.runtime.Immutable

const val YANI_SITE_ORIGIN = "https://yummyani.me"

@Immutable
data class ReleaseDto(
    val id: Int,
    val alias: String,
    val name: ReleaseNameDto,
    val year: Int? = null,
    val type: LabelValueDto? = null,
    val season: LabelValueDto? = null,
    val poster: ImageDto? = null,
    val backdrop: ImageDto? = null,
    val ageRating: AgeRatingDto? = null,
    val description: String? = null,
    val episodesTotal: Int? = null,
    val isOngoing: Boolean = false,
    val genres: List<GenreDto> = emptyList(),
    val latestEpisode: EpisodeDto? = null,
    val episodes: List<EpisodeDto> = emptyList(),
    val rating: Double? = null,
) {
    val displayName: String
        get() = name.main.ifBlank { name.english.orEmpty() }

    val posterUrl: String?
        get() = posterStandardUrl

    val posterThumbnailUrl: String?
        get() = poster?.thumbnailUrl

    val posterStandardUrl: String?
        get() = poster?.standardUrl

    val posterHighUrl: String?
        get() = poster?.highUrl

    val posterFullUrl: String?
        get() = poster?.fullUrl

    val backdropUrl: String?
        get() = backdropHighUrl

    val backdropStandardUrl: String?
        get() = backdrop?.standardUrl ?: posterStandardUrl

    val backdropHighUrl: String?
        get() = backdrop?.highUrl ?: posterHighUrl

    val backdropFullUrl: String?
        get() = backdrop?.fullUrl ?: posterFullUrl

    val metadata: String
        get() = listOfNotNull(
            year?.toString(),
            type?.description ?: type?.value,
            ageRating?.label,
            if (isOngoing) "Онгоинг" else null,
        ).joinToString("  •  ")
}

@Immutable
data class ReleaseNameDto(
    val main: String,
    val english: String? = null,
    val alternative: String? = null,
)

@Immutable
data class LabelValueDto(
    val value: String,
    val description: String? = null,
)

@Immutable
data class AgeRatingDto(
    val value: String,
    val label: String,
    val isAdult: Boolean = false,
    val description: String? = null,
)

@Immutable
data class GenreDto(
    val id: Int,
    val name: String,
)

@Immutable
data class ImageDto(
    val src: String? = null,
    val preview: String? = null,
    val thumbnail: String? = null,
    val standard: String? = null,
    val high: String? = null,
    val full: String? = null,
) {
    val thumbnailUrl: String?
        get() = firstUsableImageUrl(thumbnail, standard, preview, high, full, src)

    val standardUrl: String?
        get() = firstUsableImageUrl(standard, thumbnail, preview, high, full, src)

    val highUrl: String?
        get() = firstUsableImageUrl(high, full, standard, preview, thumbnail, src)

    val fullUrl: String?
        get() = firstUsableImageUrl(full, high, src, standard, preview, thumbnail)

    val bestPortraitPath: String?
        get() = fullUrl

    val bestLandscapePath: String?
        get() {
            val candidates = listOfNotNull(preview, high, full, standard, src, thumbnail)
                .mapNotNull(::normalizedYaniImageUrl)
            return candidates.firstOrNull { !it.substringBefore('?').endsWith(".avif", ignoreCase = true) }
                ?: candidates.firstOrNull()
        }
}

@Immutable
data class EpisodeDto(
    val id: String,
    val name: String? = null,
    val playerName: String? = null,
    val ordinal: Double,
    val preview: ImageDto? = null,
    val playerPageUrl: String? = null,
    val duration: Int? = null,
) {
    val displayOrdinal: String
        get() = if (ordinal % 1.0 == 0.0) ordinal.toInt().toString() else ordinal.toString()

    val title: String
        get() = "$displayOrdinal серия${name?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}"

    val shortTitle: String
        get() = "$displayOrdinal серия"

    val displayPlayerName: String
        get() = playerName
            ?.removePrefix("Плеер ")
            ?.removePrefix("Player ")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: "Источник не указан"

    val previewUrl: String?
        get() = preview?.bestLandscapePath

    /**
     * Yani returns an iframe page here, not a direct HLS/MP4 media stream.
     */
    val externalPlayerUrl: String?
        get() = playerPageUrl?.asAbsoluteYaniUrl()
}

@Immutable
data class HomeFeed(
    val featured: List<ReleaseDto>,
    val latest: List<ReleaseDto>,
    val discoveries: List<ReleaseDto>,
)

fun String.asAbsoluteYaniUrl(): String {
    val absoluteUrl = when {
        startsWith("http://") || startsWith("https://") -> this
        startsWith("//") -> "https:$this"
        startsWith("/") -> "$YANI_SITE_ORIGIN$this"
        else -> "$YANI_SITE_ORIGIN/$this"
    }

    return when {
        absoluteUrl.startsWith("https://static.yani.tv/") ->
            "https://imgproxy.yani.tv/${absoluteUrl.removePrefix("https://static.yani.tv/")}"
        absoluteUrl.startsWith("http://static.yani.tv/") ->
            "https://imgproxy.yani.tv/${absoluteUrl.removePrefix("http://static.yani.tv/")}"
        else -> absoluteUrl
    }
}

internal fun imageDeliveryCandidates(url: String?): List<String> {
    val primary = url?.trim()?.takeIf(String::isNotEmpty) ?: return emptyList()
    val sameQuality = yaniHostCandidates(primary)
    val lowerQuality = lowerPosterQualityUrl(primary)
        ?.let(::yaniHostCandidates)
        .orEmpty()
    return (sameQuality + lowerQuality).distinct()
}

private fun yaniHostCandidates(url: String): List<String> = when {
    url.startsWith("https://imgproxy.yani.tv/") -> listOf(
        url,
        "https://static.yani.tv/${url.removePrefix("https://imgproxy.yani.tv/")}",
    )
    url.startsWith("https://static.yani.tv/") -> listOf(
        url,
        "https://imgproxy.yani.tv/${url.removePrefix("https://static.yani.tv/")}",
    )
    else -> listOf(url)
}

private fun lowerPosterQualityUrl(url: String): String? {
    val replacements = listOf(
        "/posters/fullsize/" to "/posters/mega/",
        "/posters/full/" to "/posters/mega/",
        "/posters/mega/" to "/posters/big/",
        "/posters/huge/" to "/posters/big/",
        "/posters/big/" to "/posters/medium/",
        "/posters/medium/" to "/posters/small/",
    )
    val replacement = replacements.firstOrNull { (source, _) -> source in url } ?: return null
    return url.replace(replacement.first, replacement.second)
}

private fun firstUsableImageUrl(vararg candidates: String?): String? =
    candidates.firstNotNullOfOrNull(::normalizedYaniImageUrl)

private fun normalizedYaniImageUrl(candidate: String?): String? =
    candidate
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.asAbsoluteYaniUrl()
