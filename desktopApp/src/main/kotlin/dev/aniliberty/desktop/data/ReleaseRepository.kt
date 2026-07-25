package dev.aniliberty.desktop.data

import dev.aniliberty.desktop.model.AgeRatingDto
import dev.aniliberty.desktop.model.EpisodeDto
import dev.aniliberty.desktop.model.GenreDto
import dev.aniliberty.desktop.model.HomeFeed
import dev.aniliberty.desktop.model.ImageDto
import dev.aniliberty.desktop.model.LabelValueDto
import dev.aniliberty.desktop.model.ReleaseDto
import dev.aniliberty.desktop.model.ReleaseNameDto
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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
    suspend fun search(query: String): List<ReleaseDto>
    suspend fun catalog(
        limit: Int,
        offset: Int,
        filters: CatalogFilters,
    ): List<ReleaseDto>
    suspend fun details(id: Int): ReleaseDto
}

class NetworkReleaseRepository(
    private val api: YaniApi = YaniApi(),
) : ReleaseRepository {
    override suspend fun home(): HomeFeed {
        val feed = api.feed()
        val carousel = feed.topCarousel?.items.orEmpty()
        val latestSource = feed.newVideos
            .ifEmpty { feed.new }
            .distinctBy { it.animeId }
        val featuredSources = (latestSource + carousel + feed.announcements)
            .distinctBy { it.animeId }
            .take(HOME_FEATURED_LIMIT)
            .ifEmpty {
                error("Yani API не вернул контент для главной страницы")
            }
        val featured = coroutineScope {
            featuredSources.map { source ->
                async {
                    runCatching { api.anime(source.animeUrl).toRelease() }
                        .getOrElse { source.toRelease() }
                }
            }.awaitAll()
        }
        val featuredIds = featured.mapTo(mutableSetOf(), ReleaseDto::id)
        val discoveries = feed.discoverySources(featuredIds)
            .map(YaniAnimeDto::toRelease)

        return HomeFeed(
            featured = featured,
            latest = latestSource.take(20).map(YaniAnimeDto::toRelease),
            discoveries = discoveries,
        )
    }

    override suspend fun search(query: String): List<ReleaseDto> =
        api.search(query, limit = 30)
            .distinctBy { it.animeId }
            .map(YaniAnimeDto::toRelease)

    override suspend fun catalog(
        limit: Int,
        offset: Int,
        filters: CatalogFilters,
    ): List<ReleaseDto> =
        api.catalog(
            limit = limit,
            offset = offset,
            filters = filters,
        )
            .distinctBy { it.animeId }
            .map(YaniAnimeDto::toRelease)

    override suspend fun details(id: Int): ReleaseDto =
        api.anime(id.toString()).toRelease()
}

internal fun YaniFeedDto.discoverySources(featuredIds: Set<Int>): List<YaniAnimeDto> {
    fun List<YaniAnimeDto>.available(): List<YaniAnimeDto> =
        distinctBy { it.animeId }
            .filterNot { it.animeId in featuredIds }

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
                    ImageDto(src = it.full, preview = it.small)
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
                src = it.fullsize ?: it.mega ?: it.huge,
                preview = it.mega ?: it.huge ?: it.big ?: it.medium,
                thumbnail = it.medium ?: it.small,
            )
        },
        backdrop = heroScreenshot?.let {
            ImageDto(src = it.full, preview = it.small)
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

private fun String.episodeKey(): String {
    val normalized = trim().replace(',', '.')
    return normalized
        .toBigDecimalOrNull()
        ?.stripTrailingZeros()
        ?.toPlainString()
        ?: normalized.lowercase()
}
