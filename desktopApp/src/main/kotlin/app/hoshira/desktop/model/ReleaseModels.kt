package app.hoshira.desktop.model

const val YANI_SITE_ORIGIN = "https://yummyani.me"

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
        get() = poster?.bestPortraitPath?.asAbsoluteYaniUrl()

    val backdropUrl: String?
        get() = (backdrop?.bestLandscapePath ?: poster?.bestPortraitPath)
            ?.asAbsoluteYaniUrl()

    val metadata: String
        get() = listOfNotNull(
            year?.toString(),
            type?.description ?: type?.value,
            ageRating?.label,
            if (isOngoing) "Онгоинг" else null,
        ).joinToString("  •  ")
}

data class ReleaseNameDto(
    val main: String,
    val english: String? = null,
    val alternative: String? = null,
)

data class LabelValueDto(
    val value: String,
    val description: String? = null,
)

data class AgeRatingDto(
    val value: String,
    val label: String,
    val isAdult: Boolean = false,
    val description: String? = null,
)

data class GenreDto(
    val id: Int,
    val name: String,
)

data class ImageDto(
    val src: String? = null,
    val preview: String? = null,
    val thumbnail: String? = null,
) {
    val bestPortraitPath: String?
        get() = src ?: preview ?: thumbnail

    val bestLandscapePath: String?
        get() {
            val candidates = listOfNotNull(preview, src, thumbnail)
            return candidates.firstOrNull { !it.substringBefore('?').endsWith(".avif", ignoreCase = true) }
                ?: candidates.firstOrNull()
        }
}

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
        get() = preview?.bestLandscapePath?.asAbsoluteYaniUrl()

    /**
     * Yani returns an iframe page here, not a direct HLS/MP4 media stream.
     */
    val externalPlayerUrl: String?
        get() = playerPageUrl?.asAbsoluteYaniUrl()
}

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
