package dev.aniliberty.desktop.data

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val YANI_API_ORIGIN = "https://api.yani.tv"
private const val DEFAULT_PUBLIC_APPLICATION_TOKEN = "4g434l-dxr66w0ee"

class YaniApi(
    private val baseUrl: String = YANI_API_ORIGIN,
    private val applicationToken: String = System.getenv("YANI_APPLICATION_TOKEN")
        ?.takeIf { it.isNotBlank() }
        ?: DEFAULT_PUBLIC_APPLICATION_TOKEN,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(12))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    suspend fun feed(): YaniFeedDto =
        get<YaniResponse<YaniFeedDto>>("/feed").response

    suspend fun catalog(
        limit: Int = 30,
        offset: Int = 0,
        filters: CatalogFilters = CatalogFilters(),
    ): List<YaniAnimeDto> =
        get<YaniResponse<List<YaniAnimeDto>>>(
            catalogPath(
                limit = limit,
                offset = offset,
                filters = filters,
            ),
        ).response

    suspend fun search(
        query: String,
        limit: Int = 30,
    ): List<YaniAnimeDto> {
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8)
        return get<YaniResponse<List<YaniAnimeDto>>>(
            "/search?q=$encoded&limit=${limit.coerceIn(1, 30)}&offset=0",
        ).response
    }

    suspend fun anime(idOrAlias: String): YaniAnimeDto =
        get<YaniResponse<YaniAnimeDto>>(
            "/anime/${URLEncoder.encode(idOrAlias, StandardCharsets.UTF_8)}?need_videos=true",
        ).response

    suspend fun login(
        login: String,
        password: String,
        captchaResponse: String? = null,
    ): String =
        post<YaniLoginRequest, YaniLoginResponse>(
            path = "/profile/login",
            body = YaniLoginRequest(
                login = login.trim(),
                password = password,
                needJson = true,
                captchaResponse = captchaResponse,
            ),
        ).response.token

    suspend fun profile(accessToken: String): YaniProfileDto =
        get<YaniResponse<YaniProfileDto>>(
            path = "/profile",
            bearerToken = accessToken,
        ).response

    suspend fun refreshToken(accessToken: String): String =
        get<YaniTokenResponse>(
            path = "/profile/token",
            bearerToken = accessToken,
        ).response.token

    suspend fun logout(accessToken: String): Boolean =
        postWithoutBody<YaniBooleanResponse>(
            path = "/profile/logout",
            bearerToken = accessToken,
        ).response

    suspend fun animeListState(
        animeId: Int,
        accessToken: String,
    ): YaniAnimeListStateDto =
        get<YaniResponse<YaniAnimeListStateDto>>(
            path = "/anime/$animeId/list",
            bearerToken = accessToken,
        ).response

    suspend fun putAnimeInList(
        animeId: Int,
        listId: Int,
        date: Long,
        accessToken: String,
    ): YaniAnimeListStateDto =
        put<YaniAnimeListRequest, YaniResponse<YaniAnimeListUpdateDto>>(
            path = "/anime/$animeId/list",
            body = YaniAnimeListRequest(
                list = listId,
                date = date,
            ),
            bearerToken = accessToken,
        ).response.let { updated ->
            YaniAnimeListStateDto(
                list = updated.list?.id,
                isFavorite = animeListState(animeId, accessToken).isFavorite,
            )
        }

    suspend fun removeAnimeFromList(
        animeId: Int,
        accessToken: String,
    ): Boolean =
        delete<YaniBooleanResponse>(
            path = "/anime/$animeId/list",
            bearerToken = accessToken,
        ).response

    suspend fun setAnimeFavorite(
        animeId: Int,
        favorite: Boolean,
        date: Long,
        accessToken: String,
    ): Boolean =
        if (favorite) {
            put<YaniFavoriteRequest, YaniBooleanResponse>(
                path = "/anime/$animeId/list/fav",
                body = YaniFavoriteRequest(date),
                bearerToken = accessToken,
            ).response
        } else {
            delete<YaniBooleanResponse>(
                path = "/anime/$animeId/list/fav",
                bearerToken = accessToken,
            ).response
        }

    suspend fun userList(
        userId: Long,
        listId: Int,
        accessToken: String,
    ): List<YaniUserListAnimeDto> =
        get<YaniResponse<List<YaniUserListAnimeDto>>>(
            path = "/users/$userId/lists/$listId",
            bearerToken = accessToken,
        ).response

    private suspend inline fun <reified T> get(
        path: String,
        bearerToken: String? = null,
    ): T = execute(
        requestBuilder(path, bearerToken)
            .GET()
            .build(),
    )

    private suspend inline fun <reified B, reified T> post(
        path: String,
        body: B,
        bearerToken: String? = null,
    ): T = execute(
        requestBuilder(path, bearerToken)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(body)))
            .build(),
    )

    private suspend inline fun <reified B, reified T> put(
        path: String,
        body: B,
        bearerToken: String? = null,
    ): T = execute(
        requestBuilder(path, bearerToken)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(json.encodeToString(body)))
            .build(),
    )

    private suspend inline fun <reified T> postWithoutBody(
        path: String,
        bearerToken: String? = null,
    ): T = execute(
        requestBuilder(path, bearerToken)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build(),
    )

    private suspend inline fun <reified T> delete(
        path: String,
        bearerToken: String? = null,
    ): T = execute(
        requestBuilder(path, bearerToken)
            .DELETE()
            .build(),
    )

    private fun requestBuilder(
        path: String,
        bearerToken: String?,
    ): HttpRequest.Builder =
        HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .timeout(Duration.ofSeconds(24))
            .header("Accept", "application/json,image/avif,image/webp")
            .header("Lang", "ru")
            .header("X-Application", applicationToken)
            .header("User-Agent", "Hoshira-Desktop/0.2")
            .apply {
                bearerToken
                    ?.takeIf(String::isNotBlank)
                    ?.let { header("Authorization", "Bearer $it") }
            }

    private suspend inline fun <reified T> execute(request: HttpRequest): T =
        withContext(Dispatchers.IO) {
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw YaniApiException(
                statusCode = response.statusCode(),
                message = response.body()
                    .take(400)
                    .takeIf(String::isNotBlank)
                    ?: "Yani API вернул код ${response.statusCode()}",
            )
        }
        json.decodeFromString<T>(response.body())
    }
}

internal fun catalogPath(
    limit: Int,
    offset: Int,
    filters: CatalogFilters,
): String {
    val parameters = buildList {
        add("limit" to limit.coerceIn(1, 100).toString())
        add("offset" to offset.coerceIn(0, 20_000).toString())
        add("sort" to filters.sort.apiField)
        add("sort_forward" to filters.sort.forward.toString())
        filters.type?.let { add("types" to it) }
        filters.status?.let { add("status" to it) }
        filters.season?.let { add("season" to it) }
        filters.genre?.let { add("genres" to it) }
        filters.fromYear?.coerceIn(1900, 2100)?.let { add("from_year" to it.toString()) }
        filters.toYear?.coerceIn(1900, 2100)?.let { add("to_year" to it.toString()) }
        filters.minRating?.coerceIn(0.0, 10.0)?.let { add("min_rating" to it.toString()) }
    }
    return "/anime?" + parameters.joinToString("&") { (name, value) ->
        "$name=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
    }
}

class YaniApiException(
    val statusCode: Int,
    override val message: String,
) : RuntimeException(message)

@Serializable
data class YaniResponse<T>(
    val response: T,
)

@Serializable
data class YaniLoginRequest(
    val login: String,
    val password: String,
    @SerialName("need_json") val needJson: Boolean,
    @SerialName("recaptcha_response") val captchaResponse: String? = null,
)

@Serializable
data class YaniLoginResponse(
    val response: YaniLoginResultDto,
)

@Serializable
data class YaniLoginResultDto(
    val success: Boolean = false,
    val token: String,
)

@Serializable
data class YaniTokenResponse(
    val response: YaniTokenDto,
)

@Serializable
data class YaniTokenDto(
    val token: String,
)

@Serializable
data class YaniBooleanResponse(
    val response: Boolean,
)

@Serializable
data class YaniAnimeListRequest(
    val list: Int,
    val date: Long,
)

@Serializable
data class YaniFavoriteRequest(
    val date: Long,
)

@Serializable
data class YaniAnimeListStateDto(
    val list: Int? = null,
    @SerialName("is_favorite") val isFavorite: Boolean = false,
)

@Serializable
data class YaniAnimeListUpdateDto(
    val success: Boolean = false,
    val list: YaniAnimeListDto? = null,
)

@Serializable
data class YaniAnimeListDto(
    val id: Int,
    val title: String? = null,
    val href: String? = null,
)

@Serializable
data class YaniProfileDto(
    val id: Long,
    val nickname: String,
    val about: String? = null,
    val banned: Boolean = false,
    val avatars: YaniProfileAvatarsDto? = null,
    val notifications: YaniProfileNotificationsDto? = null,
    val messages: YaniProfileMessagesDto? = null,
)

@Serializable
data class YaniProfileAvatarsDto(
    val big: String? = null,
    val full: String? = null,
    val small: String? = null,
)

@Serializable
data class YaniProfileNotificationsDto(
    val count: Int = 0,
)

@Serializable
data class YaniProfileMessagesDto(
    @SerialName("unread_count") val unreadCount: Int = 0,
)

@Serializable
data class YaniFeedDto(
    val announcements: List<YaniAnimeDto> = emptyList(),
    val recommends: List<YaniAnimeDto> = emptyList(),
    @SerialName("top_carousel") val topCarousel: YaniCarouselDto? = null,
    val new: List<YaniAnimeDto> = emptyList(),
    @SerialName("new_videos") val newVideos: List<YaniAnimeDto> = emptyList(),
    val schedule: List<YaniAnimeDto> = emptyList(),
)

@Serializable
data class YaniCarouselDto(
    val season: Int? = null,
    val year: Int? = null,
    val items: List<YaniAnimeDto> = emptyList(),
)

@Serializable
data class YaniAnimeDto(
    @SerialName("anime_id") val animeId: Int,
    @SerialName("anime_url") val animeUrl: String,
    val title: String,
    val description: String? = null,
    val poster: YaniPosterDto? = null,
    val rating: YaniRatingDto? = null,
    val genres: List<YaniGenreDto> = emptyList(),
    val year: Int? = null,
    @SerialName("min_age") val minAge: YaniAgeDto? = null,
    @SerialName("anime_status") val animeStatus: YaniStatusDto? = null,
    val type: YaniTypeDto? = null,
    val season: Int? = null,
    val episodes: YaniEpisodesDto? = null,
    val videos: List<YaniVideoDto> = emptyList(),
    @SerialName("other_titles") val otherTitles: List<String> = emptyList(),
    @SerialName("random_screenshots") val randomScreenshots: List<YaniScreenshotDto> = emptyList(),
    @SerialName("ep_title") val episodeTitle: String? = null,
    @SerialName("dub_title") val dubbingTitle: String? = null,
    @SerialName("player_title") val playerTitle: String? = null,
    @SerialName("video_id") val videoId: Long? = null,
    val date: Long? = null,
)

@Serializable
data class YaniUserListAnimeDto(
    @SerialName("anime_id") val animeId: Int,
    @SerialName("anime_url") val animeUrl: String,
    val title: String,
    val description: String? = null,
    val poster: YaniPosterDto? = null,
    val rating: Double? = null,
    val genres: List<YaniGenreDto> = emptyList(),
    val year: Int? = null,
    @SerialName("min_age") val minAge: YaniAgeDto? = null,
    @SerialName("anime_status") val animeStatus: YaniStatusDto? = null,
    val type: YaniTypeDto? = null,
    val season: Int? = null,
)

@Serializable
data class YaniPosterDto(
    val fullsize: String? = null,
    val mega: String? = null,
    val huge: String? = null,
    val big: String? = null,
    val medium: String? = null,
    val small: String? = null,
)

@Serializable
data class YaniRatingDto(
    val average: Double? = null,
    val counters: Int? = null,
)

@Serializable
data class YaniGenreDto(
    val id: Int,
    val title: String,
    val alias: String? = null,
)

@Serializable
data class YaniAgeDto(
    val value: Int? = null,
    val title: String? = null,
    @SerialName("title_long") val titleLong: String? = null,
)

@Serializable
data class YaniStatusDto(
    val value: Int? = null,
    val title: String? = null,
    val alias: String? = null,
)

@Serializable
data class YaniTypeDto(
    val name: String? = null,
    val value: Int? = null,
    val shortname: String? = null,
    val alias: String? = null,
)

@Serializable
data class YaniEpisodesDto(
    val count: Int? = null,
    val aired: Int? = null,
    @SerialName("next_date") val nextDate: Long? = null,
    @SerialName("prev_date") val previousDate: Long? = null,
)

@Serializable
data class YaniVideoDto(
    @SerialName("video_id") val videoId: Long,
    val data: YaniVideoDataDto? = null,
    val number: String? = null,
    val date: Long? = null,
    @SerialName("iframe_url") val iframeUrl: String? = null,
    val index: Int? = null,
    val views: Int? = null,
    val duration: Int? = null,
)

@Serializable
data class YaniVideoDataDto(
    val player: String? = null,
    val dubbing: String? = null,
    @SerialName("player_id") val playerId: Int? = null,
)

@Serializable
data class YaniScreenshotDto(
    val id: Long? = null,
    val episode: String? = null,
    val sizes: YaniScreenshotSizesDto? = null,
)

@Serializable
data class YaniScreenshotSizesDto(
    val full: String? = null,
    val small: String? = null,
)
