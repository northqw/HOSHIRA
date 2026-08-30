package app.hoshira.desktop.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

class GeoService(
    private val endpoint: String = GEO_API_URL,
    private val httpClient: OkHttpClient = SharedHttpClient.geo,
) {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    @Volatile
    private var cachedCountryCode: String? = null

    suspend fun countryCode(): String? {
        cachedCountryCode?.let { return it }

        val country = runCatching {
            fetchCountryCode()
        }.getOrNull()

        if (!country.isNullOrBlank()) {
            cachedCountryCode = country
        }

        return country
    }

    suspend fun isRussia(): Boolean =
        countryCode()?.equals("RU", ignoreCase = true) == true

    private suspend fun fetchCountryCode(): String {
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .header("User-Agent", "Hoshira/0.4")
            .build()
        return httpClient.newCall(request).awaitResponse().use { response ->
            if (!response.isSuccessful) error("Geo API returned HTTP ${response.code}")
            json.decodeFromString<GeoResponse>(response.body?.string().orEmpty())
                .country
                .trim()
                .uppercase()
        }
    }
}

class RegionAccessPolicy(
    private val geoService: GeoService = GeoService(),
) {
    private var cachedBlockedIds: Set<Int>? = null

    suspend fun blockedAnimeIds(): Set<Int> {
        cachedBlockedIds?.let { return it }

        val result = if (geoService.isRussia()) {
            BLOCKED_IN_RUSSIA_IDS
        } else {
            emptySet()
        }

        cachedBlockedIds = result
        return result
    }

    suspend fun isAllowed(animeId: Int): Boolean =
        animeId !in blockedAnimeIds()
}

class RegionRestrictedException(
    val animeId: Int,
) : RuntimeException(
    "Этот релиз недоступен в вашем регионе.",
)

@Serializable
private data class GeoResponse(
    val country: String,
)

/*
 * Anime ID релизов, которые необходимо скрывать
 * для пользователей с российским IP.
 */
private val BLOCKED_IN_RUSSIA_IDS = setOf(
    // Death Note
    1426,   // Тетрадь Смерти
    1427,   // Тетрадь смерти: Перезапись — Глазами Бога
    12452,  // Тетрадь смерти: Перезапись — Наследники L

    // Inuyashiki
    5649,   // Инуясики

    // Tokyo Ghoul
    1436,   // Токийский Гуль
    1437,   // Токийский Гуль √A
    1438,   // Токийский Гуль: Джек
    8019,   // Токийский Гуль: Перерождение
    1440,   // Токийский Гуль: Перерождение 2
    10639,  // Токийский Гуль: Пинто

    // Elfen Lied
    353,    // Эльфийская песнь
    11523,  // Эльфийская песнь: Под проливным дождём

    // Ishuzoku Reviewers
    7540,   // Межвидовые рецензенты
)

private const val GEO_API_URL = "https://api.country.is/"
