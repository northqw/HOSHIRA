package dev.aniliberty.desktop.data

import dev.aniliberty.desktop.model.ReleaseDto
import dev.aniliberty.desktop.model.asAbsoluteYaniUrl
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class AccountProfile(
    val id: Long,
    val nickname: String,
    val avatarUrl: String?,
    val unreadNotifications: Int,
    val unreadMessages: Int,
)

enum class AnimeListKind(
    val id: Int,
    val title: String,
) {
    Watching(0, "Смотрю"),
    Planned(1, "В планах"),
    Watched(2, "Просмотрено"),
    Dropped(3, "Брошено"),
    Favorite(4, "Любимое"),
    Postponed(5, "Отложено"),
    ;

    companion object {
        fun fromId(id: Int?): AnimeListKind? = entries.firstOrNull { it.id == id }
    }
}

data class AnimeMembership(
    val list: AnimeListKind?,
    val isFavorite: Boolean,
)

data class AccountLibrary(
    val lists: Map<AnimeListKind, List<ReleaseDto>>,
    val unavailableLists: Set<AnimeListKind> = emptySet(),
) {
    fun releases(kind: AnimeListKind): List<ReleaseDto> =
        lists[kind].orEmpty()

    fun count(kind: AnimeListKind): Int? =
        releases(kind).size.takeUnless { kind in unavailableLists }

    companion object {
        val Empty = AccountLibrary(
            AnimeListKind.entries.associateWith { emptyList() },
        )
    }
}

interface AccountRepository {
    suspend fun restoreSession(): AccountProfile?
    suspend fun login(email: String, password: String): AccountProfile
    suspend fun logout()
    suspend fun animeMembership(animeId: Int): AnimeMembership
    suspend fun setAnimeList(animeId: Int, list: AnimeListKind?): AnimeMembership
    suspend fun setAnimeFavorite(animeId: Int, favorite: Boolean): AnimeMembership
    suspend fun library(profileId: Long): AccountLibrary
}

class NetworkAccountRepository internal constructor(
    private val api: YaniApi = YaniApi(),
    private val sessionStore: AccountSessionStore = AccountSessionStore(),
) : AccountRepository {
    private var accessToken: String? = null

    override suspend fun restoreSession(): AccountProfile? {
        val stored = sessionStore.load() ?: return null
        var token = stored.token
        var refreshedAt = stored.refreshedAt

        if (Instant.now().epochSecond - stored.refreshedAt >= TOKEN_REFRESH_INTERVAL_SECONDS) {
            runCatching { api.refreshToken(token) }
                .onSuccess { refreshedToken ->
                    token = refreshedToken
                    refreshedAt = Instant.now().epochSecond
                }
                .onFailure { error ->
                    if (error is YaniApiException && error.statusCode == 401) {
                        sessionStore.clear()
                        return null
                    }
                }
        }

        val profile = runCatching { api.profile(token) }
            .getOrElse { error ->
                if (error is YaniApiException && error.statusCode == 401) {
                    sessionStore.clear()
                    return null
                }
                throw error
            }

        accessToken = token
        sessionStore.save(
            StoredAccountSession(
                token = token,
                refreshedAt = refreshedAt,
            ),
        )
        return profile.toAccountProfile()
    }

    override suspend fun login(
        email: String,
        password: String,
    ): AccountProfile {
        require(email.isNotBlank()) { "Введите email" }
        require(password.isNotBlank()) { "Введите пароль" }

        val token = api.login(email, password)
        val profile = api.profile(token)
        accessToken = token
        sessionStore.save(
            StoredAccountSession(
                token = token,
                refreshedAt = Instant.now().epochSecond,
            ),
        )
        return profile.toAccountProfile()
    }

    override suspend fun logout() {
        val token = accessToken
        try {
            if (token != null) api.logout(token)
        } finally {
            accessToken = null
            sessionStore.clear()
        }
    }

    override suspend fun animeMembership(animeId: Int): AnimeMembership =
        api.animeListState(animeId, requireAccessToken()).toMembership()

    override suspend fun setAnimeList(
        animeId: Int,
        list: AnimeListKind?,
    ): AnimeMembership {
        val token = requireAccessToken()
        if (list == null) {
            api.removeAnimeFromList(animeId, token)
        } else {
            check(list != AnimeListKind.Favorite) {
                "Избранное изменяется отдельной операцией"
            }
            api.putAnimeInList(
                animeId = animeId,
                listId = list.id,
                date = Instant.now().epochSecond,
                accessToken = token,
            )
        }
        return api.animeListState(animeId, token).toMembership()
    }

    override suspend fun setAnimeFavorite(
        animeId: Int,
        favorite: Boolean,
    ): AnimeMembership {
        val token = requireAccessToken()
        api.setAnimeFavorite(
            animeId = animeId,
            favorite = favorite,
            date = Instant.now().epochSecond,
            accessToken = token,
        )
        return api.animeListState(animeId, token).toMembership()
    }

    override suspend fun library(profileId: Long): AccountLibrary {
        val token = requireAccessToken()
        val results = coroutineScope {
            AnimeListKind.entries.map { kind ->
                async {
                    kind to runCatching {
                        api.userList(
                            userId = profileId,
                            listId = kind.id,
                            accessToken = token,
                        )
                            .distinctBy(YaniUserListAnimeDto::animeId)
                            .map { it.toAnimeDto().toRelease() }
                    }
                }
            }.awaitAll()
        }
        val firstSuccess = results.firstOrNull { (_, result) -> result.isSuccess }
        if (firstSuccess == null) {
            throw results.firstNotNullOf { (_, result) -> result.exceptionOrNull() }
        }
        return AccountLibrary(
            lists = results.associate { (kind, result) ->
                kind to result.getOrDefault(emptyList())
            },
            unavailableLists = results
                .filter { (_, result) -> result.isFailure }
                .mapTo(mutableSetOf()) { (kind, _) -> kind },
        )
    }

    private fun requireAccessToken(): String =
        checkNotNull(accessToken) { "Сначала войдите в аккаунт" }
}

internal class AccountSessionStore(
    private val sessionFile: Path = defaultSessionFile(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    suspend fun load(): StoredAccountSession? = withContext(Dispatchers.IO) {
        if (!Files.isRegularFile(sessionFile)) return@withContext null
        runCatching {
            val encrypted = Files.readAllBytes(sessionFile)
            val decrypted = platformUnprotectSession(encrypted)
            json.decodeFromString<StoredAccountSession>(
                decrypted.toString(StandardCharsets.UTF_8),
            )
        }.getOrElse {
            Files.deleteIfExists(sessionFile)
            null
        }
    }

    suspend fun save(session: StoredAccountSession) = withContext(Dispatchers.IO) {
        Files.createDirectories(sessionFile.parent)
        val payload = json.encodeToString(session).toByteArray(StandardCharsets.UTF_8)
        val encrypted = platformProtectSession(payload)
        val temporaryFile = sessionFile.resolveSibling("${sessionFile.fileName}.tmp")
        Files.write(temporaryFile, encrypted)
        try {
            Files.move(
                temporaryFile,
                sessionFile,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporaryFile,
                sessionFile,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        Files.deleteIfExists(sessionFile)
        Unit
    }
}

@Serializable
internal data class StoredAccountSession(
    val token: String,
    val refreshedAt: Long,
)

private fun YaniProfileDto.toAccountProfile(): AccountProfile =
    AccountProfile(
        id = id,
        nickname = nickname,
        avatarUrl = (avatars?.full ?: avatars?.big ?: avatars?.small)
            ?.asAbsoluteYaniUrl(),
        unreadNotifications = notifications?.count ?: 0,
        unreadMessages = messages?.unreadCount ?: 0,
    )

private fun YaniAnimeListStateDto.toMembership(): AnimeMembership =
    AnimeMembership(
        list = AnimeListKind.fromId(list),
        isFavorite = isFavorite,
    )

private fun YaniUserListAnimeDto.toAnimeDto(): YaniAnimeDto =
    YaniAnimeDto(
        animeId = animeId,
        animeUrl = animeUrl,
        title = title,
        description = description,
        poster = poster,
        rating = rating?.let { YaniRatingDto(average = it) },
        genres = genres,
        year = year,
        minAge = minAge,
        animeStatus = animeStatus,
        type = type,
        season = season,
    )

private fun defaultSessionFile(): Path {
    return platformConfigDirectory()
        .resolve("account")
        .resolve("session.bin")
}

private const val TOKEN_REFRESH_INTERVAL_SECONDS = 2L * 24L * 60L * 60L
