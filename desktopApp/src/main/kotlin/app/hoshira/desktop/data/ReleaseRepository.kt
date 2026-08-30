package app.hoshira.desktop.data

import androidx.compose.runtime.Immutable
import app.hoshira.desktop.model.AgeRatingDto
import app.hoshira.desktop.model.EpisodeDto
import app.hoshira.desktop.model.GenreDto
import app.hoshira.desktop.model.HomeFeed
import app.hoshira.desktop.model.ImageDto
import app.hoshira.desktop.model.LabelValueDto
import app.hoshira.desktop.model.ReleaseDto
import app.hoshira.desktop.model.ReleaseNameDto
import app.hoshira.desktop.model.asAbsoluteYaniUrl
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

@Immutable
data class CatalogFilters(
    val type: String? = null,
    val status: String? = null,
    val season: String? = null,
    val genre: String? = null,
    val fromYear: Int? = null,
    val toYear: Int? = null,
    val minRating: Double? = null,
    val sort: CatalogSort = CatalogSort.Newest,
)

enum class CatalogSort(
    val apiField: String,
    val forward: Boolean,
) {
    Newest(apiField = "id", forward = false),
    Rating(apiField = "rating", forward = false),
    Year(apiField = "year", forward = false),
    Popular(apiField = "views", forward = false),
    Title(apiField = "title", forward = true),
}

interface ReleaseRepository {
    suspend fun home(): HomeFeed
    suspend fun refreshHome(): HomeFeed = home()
    suspend fun search(query: String): List<ReleaseDto>
    suspend fun catalog(
        limit: Int,
        offset: Int,
        filters: CatalogFilters,
    ): List<ReleaseDto>
    suspend fun details(id: Int): ReleaseDto
    fun clearMemoryCaches() = Unit
}

class NetworkReleaseRepository(
    private val api: YaniApi = YaniApi(),
    private val regionAccessPolicy: RegionAccessPolicy = RegionAccessPolicy(),
) : ReleaseRepository {
    override fun clearMemoryCaches() = api.clearResponseMemoryCache()

    override suspend fun home(): HomeFeed = loadHome(forceRefresh = false)

    override suspend fun refreshHome(): HomeFeed = loadHome(forceRefresh = true)

    private suspend fun loadHome(forceRefresh: Boolean): HomeFeed {
        val blockedIds = regionAccessPolicy.blockedAnimeIds()

        val feed = api.feed(forceRefresh)

        val carousel = feed.topCarousel
            ?.items
            .orEmpty()
            .filterNot { it.animeId in blockedIds }

        val latestSource = feed.newVideos
            .ifEmpty { feed.new }
            .distinctBy { it.animeId }
            .filterNot { it.animeId in blockedIds }

        val announcements = feed.announcements
            .filterNot { it.animeId in blockedIds }

        val featuredSources = (
            latestSource +
                carousel +
                announcements
            )
            .distinctBy { it.animeId }
            .take(HOME_FEATURED_LIMIT)
            .ifEmpty {
                error("Yani API не вернул контент для главной страницы")
            }

        val featured = coroutineScope {
            featuredSources.map { source ->
                async {
                    runCatching {
                        api.anime(source.animeUrl).toRelease()
                    }.getOrElse {
                        source.toRelease()
                    }
                }
            }.awaitAll()
        }

        val featuredIds = featured.mapTo(
            mutableSetOf(),
            ReleaseDto::id,
        )

        val discoveries = feed
            .discoverySources(
                featuredIds = featuredIds,
                blockedIds = blockedIds,
            )
            .map(YaniAnimeDto::toRelease)

        return HomeFeed(
            featured = featured,
            latest = latestSource
                .take(20)
                .map(YaniAnimeDto::toRelease),
            discoveries = discoveries,
        )
    }

    override suspend fun search(
        query: String,
    ): List<ReleaseDto> {
        val blockedIds = regionAccessPolicy.blockedAnimeIds()

        return api.search(query, limit = 30)
            .distinctBy { it.animeId }
            .filterNot { it.animeId in blockedIds }
            .map(YaniAnimeDto::toRelease)
    }

    override suspend fun catalog(
        limit: Int,
        offset: Int,
        filters: CatalogFilters,
    ): List<ReleaseDto> {
        val blockedIds = regionAccessPolicy.blockedAnimeIds()

        return api.catalog(
            limit = limit,
            offset = offset,
            filters = filters,
        )
            .distinctBy { it.animeId }
            .filterNot { it.animeId in blockedIds }
            .map(YaniAnimeDto::toRelease)
    }

    override suspend fun details(id: Int): ReleaseDto {
        if (!regionAccessPolicy.isAllowed(id)) {
            throw RegionRestrictedException(id)
        }

        return api.anime(id.toString()).toRelease()
    }
}

internal fun YaniFeedDto.discoverySources(
    featuredIds: Set<Int>,
    blockedIds: Set<Int> = emptySet(),
): List<YaniAnimeDto> {
    fun List<YaniAnimeDto>.available(): List<YaniAnimeDto> =
        distinctBy { it.animeId }
            .filterNot { it.animeId in featuredIds }
            .filterNot { it.animeId in blockedIds }

    val recommendations = recommends.available()
    return recommendations
        .ifEmpty {
            (topCarousel?.items.orEmpty() + announcements).available()
        }
        .take(HOME_DISCOVERY_LIMIT)
}

private const val HOME_FEATURED_LIMIT = 6
private const val HOME_DISCOVERY_LIMIT = 16

internal fun YaniAnimeDto.toRelease(): ReleaseDto {
    val screenshotsByEpisode = randomScreenshots
        .mapNotNull { screenshot ->
            screenshot.episode
                ?.episodeKey()
                ?.let { key -> key to screenshot }
        }
        .toMap()
    val heroScreenshot = randomScreenshots.firstOrNull()?.sizes
    val mappedEpisodes = videos
        .sortedWith(compareBy<YaniVideoDto> { it.number?.toDoubleOrNull() ?: Double.MAX_VALUE }
            .thenBy { it.index ?: Int.MAX_VALUE })
        .map { video ->
            val screenshot = video.number
                ?.episodeKey()
                ?.let(screenshotsByEpisode::get)
                ?.sizes
            EpisodeDto(
                id = video.videoId.toString(),
                name = video.data?.dubbing,
                playerName = video.data?.player,
                ordinal = video.number?.toDoubleOrNull()
                    ?: video.index?.toDouble()
                    ?: 0.0,
                preview = screenshot?.let {
                    screenshotImage(
                        full = it.full,
                        preview = it.small,
                    )
                },
                playerPageUrl = video.iframeUrl,
                duration = video.duration?.takeIf { it > 0 },
            )
        }
    val feedEpisode = episodeTitle?.toDoubleOrNull()?.let { ordinal ->
        EpisodeDto(
            id = videoId?.toString() ?: "$animeId-$episodeTitle",
            name = dubbingTitle,
            playerName = playerTitle,
            ordinal = ordinal,
            playerPageUrl = null,
        )
    }
    val englishTitle = otherTitles.firstOrNull { title ->
        title.any(Char::isLetter) && title.none { it in 'А'..'я' || it == 'ё' || it == 'Ё' }
    }
    val episodeCount = episodes?.count
        ?: mappedEpisodes.map(EpisodeDto::displayOrdinal).distinct().size.takeIf { it > 0 }

    return ReleaseDto(
        id = animeId,
        alias = animeUrl,
        name = ReleaseNameDto(
            main = title,
            english = englishTitle,
            alternative = otherTitles.firstOrNull(),
        ),
        year = year,
        type = type?.let {
            LabelValueDto(
                value = it.alias ?: it.shortname ?: it.name.orEmpty(),
                description = it.name ?: it.shortname,
            )
        },
        season = season?.let { LabelValueDto(it.toString(), "Сезон $it") },
        poster = poster?.let {
            ImageDto(
                thumbnail = firstImageUrl(
                    it.medium, it.small, it.big, it.huge, it.mega, it.fullsize,
                ),
                standard = firstImageUrl(
                    it.big, it.medium, it.huge, it.mega, it.fullsize, it.small,
                ),
                high = firstImageUrl(
                    it.mega, it.huge, it.big, it.fullsize, it.medium, it.small,
                ),
                full = firstImageUrl(
                    it.fullsize, it.mega, it.huge, it.big, it.medium, it.small,
                ),
            )
        },
        backdrop = heroScreenshot?.let {
            screenshotImage(
                full = it.full,
                preview = it.small,
            )
        },
        ageRating = minAge?.let {
            AgeRatingDto(
                value = it.value?.toString().orEmpty(),
                label = it.title ?: it.titleLong.orEmpty(),
                isAdult = (it.titleLong ?: it.title).orEmpty().contains("18+"),
                description = it.titleLong,
            )
        },
        description = description,
        episodesTotal = episodeCount,
        isOngoing = animeStatus?.alias == "ongoing",
        genres = genres.map { GenreDto(id = it.id, name = it.title) },
        latestEpisode = feedEpisode ?: mappedEpisodes.maxByOrNull(EpisodeDto::ordinal),
        episodes = mappedEpisodes,
        rating = rating?.average,
    )
}

private fun screenshotImage(
    full: String?,
    preview: String?,
): ImageDto? {
    val normalizedFull = firstImageUrl(full, preview)
    val normalizedPreview = firstImageUrl(preview, full)
    if (normalizedFull == null && normalizedPreview == null) return null

    return ImageDto(
        src = normalizedFull,
        preview = normalizedPreview,
        thumbnail = normalizedPreview,
        standard = normalizedPreview,
        high = normalizedFull,
        full = normalizedFull,
    )
}

private fun firstImageUrl(vararg candidates: String?): String? =
    candidates.firstNotNullOfOrNull { candidate ->
        candidate
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.asAbsoluteYaniUrl()
    }

private fun String.episodeKey(): String {
    val normalized = trim().replace(',', '.')
    return normalized
        .toBigDecimalOrNull()
        ?.stripTrailingZeros()
        ?.toPlainString()
        ?: normalized.lowercase()
}
