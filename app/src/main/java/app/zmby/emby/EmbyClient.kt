package app.zmby.emby

import app.zmby.log.ZLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.Response
import kotlin.coroutines.resumeWithException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Identifies this app and box to Emby; the server keys sessions by [deviceId]. */
data class ClientInfo(
    val client: String = "zmby",
    val device: String,
    val deviceId: String,
    val version: String,
    /** The box's own HTTP agent (`System.getProperty("http.agent")`), sent as the official identity. */
    val officialAgent: String = OfficialEmby.dalvikAgent("11", device, null),
) {
    /** zmby's own User-Agent, the default for every server. */
    val userAgent: String get() = "$client/$version"

    /** The User-Agent and client fields for [mode]; [custom] is the 自定义 User-Agent. */
    fun identity(mode: UaMode, custom: String? = null): ClientIdentity = when (mode) {
        UaMode.Auto, UaMode.Zmby -> ClientIdentity(userAgent, client, version)
        UaMode.Official -> ClientIdentity(officialAgent, OfficialEmby.CLIENT, OfficialEmby.VERSION)
        // A server strict enough to need a hand-written User-Agent wants an official client name too.
        UaMode.Custom -> ClientIdentity(custom?.takeIf { it.isNotBlank() } ?: officialAgent, OfficialEmby.CLIENT, OfficialEmby.VERSION)
    }

    fun identity(ua: UaSetting): ClientIdentity = identity(ua.effective, ua.custom)
}

/** A logged-in user on one Emby server, reached through one of its lines ([baseUrl]). */
data class ServerSession(
    val baseUrl: String,
    val userId: String,
    val accessToken: String,
    val serverId: String? = null,
    val serverName: String? = null,
    /** The registry id ([app.zmby.data.ServerConfig.id]); empty outside the registry (tests, login). */
    val id: String = "",
    /** Which client this server is spoken to as. */
    val ua: UaSetting = UaSetting(),
) {
    /** What per-server facts (caps) are stored under: the registry id, so changing lines keeps them. */
    val capsKey: String get() = id.ifEmpty { baseUrl }
}

class EmbyException(message: String, val code: Int? = null) : IOException(message)

class EmbyClient(
    private val http: OkHttpClient,
    val clientInfo: ClientInfo,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    /**
     * Called when [UaMode.Auto] got through with another identity than the one in use: the server
     * address, the registry id (empty before the server is registered) and the identity that worked.
     */
    var onIdentityChange: ((baseUrl: String, serverId: String, mode: UaMode) -> Unit)? = null

    /** When the other identity last failed for a server too ("base|mode"); not retried for a while. */
    private val fallbackFailedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** The identity requests to [s] go out with (also for images, probes and the relay). */
    fun identity(s: ServerSession): ClientIdentity = clientInfo.identity(s.ua)

    suspend fun publicInfo(baseUrl: String, ua: UaSetting = UaSetting()): SystemInfoPublic =
        get(normalizeBaseUrl(baseUrl), null, "System/Info/Public", ua = ua)

    /**
     * Logs in; under [UaMode.Auto] a server that turns zmby away is tried once more as the official
     * client. The session carries the identity that got in ([UaSetting.detected]).
     */
    suspend fun authenticate(baseUrl: String, username: String, password: String, ua: UaSetting = UaSetting()): ServerSession {
        val base = normalizeBaseUrl(baseUrl)
        val body = buildJsonObject {
            put("Username", username)
            put("Pw", password)
        }.toString()
        val reply = call(base, null, ua) { id -> request(base, null, "Users/AuthenticateByName", id) {}.post(body.toRequestBody(JSON)).build() }
        val result = json.decodeFromString<AuthenticationResult>(reply.text)
        ZLog.secret(result.accessToken)
        val used = if (ua.canFallBack) ua.copy(detected = reply.mode) else ua
        val info = runCatching { publicInfo(base, used) }.getOrNull()
        return ServerSession(
            baseUrl = base,
            userId = result.user.id,
            accessToken = result.accessToken,
            serverId = result.serverId ?: info?.id,
            serverName = info?.serverName,
            ua = used,
        )
    }

    suspend fun resume(s: ServerSession, limit: Int = 30): ItemsResult =
        get(s.baseUrl, s, "Users/${s.userId}/Items/Resume") {
            addQueryParameter("Limit", limit.toString())
            addQueryParameter("MediaTypes", "Video")
            addQueryParameter("Recursive", "true")
            addQueryParameter("Fields", LIST_FIELDS)
        }

    /** The user's top-level libraries. */
    suspend fun views(s: ServerSession): ItemsResult = get(s.baseUrl, s, "Users/${s.userId}/Views")

    suspend fun latest(s: ServerSession, parentId: String?, limit: Int = 24): List<BaseItemDto> =
        get<List<BaseItemDto>>(s.baseUrl, s, "Users/${s.userId}/Items/Latest") {
            if (parentId != null) addQueryParameter("ParentId", parentId)
            addQueryParameter("IncludeItemTypes", "Movie,Series,Episode,Video")
            addQueryParameter("Limit", limit.toString())
            addQueryParameter("Fields", LIST_FIELDS)
        }.take(limit)

    /** Movies, series or favorites, newest first; across all libraries unless [parentId] is given. */
    suspend fun browse(
        s: ServerSession,
        types: String,
        favoritesOnly: Boolean = false,
        start: Int = 0,
        limit: Int = 60,
        parentId: String? = null,
        /** Sort and filter parameters of the browse page ([app.zmby.data.BrowseQuery.params]); they replace the defaults. */
        query: Map<String, String> = emptyMap(),
    ): ItemsResult = get(s.baseUrl, s, "Users/${s.userId}/Items") {
        if (parentId != null) addQueryParameter("ParentId", parentId)
        addQueryParameter("IncludeItemTypes", types)
        addQueryParameter("Recursive", "true")
        addQueryParameter("SortBy", query["SortBy"] ?: "DateCreated,SortName")
        addQueryParameter("SortOrder", query["SortOrder"] ?: "Descending")
        val filters = listOfNotNull("IsFavorite".takeIf { favoritesOnly }, query["Filters"]).joinToString(",")
        if (filters.isNotEmpty()) addQueryParameter("Filters", filters)
        for ((k, v) in query) if (k !in setOf("SortBy", "SortOrder", "Filters")) addQueryParameter(k, v)
        addQueryParameter("StartIndex", start.toString())
        addQueryParameter("Limit", limit.toString())
        addQueryParameter("Fields", LIST_FIELDS)
    }

    /** The genres used in one library (浏览页筛选). */
    suspend fun genres(s: ServerSession, parentId: String?, types: String): ItemsResult =
        get(s.baseUrl, s, "Genres") {
            addQueryParameter("UserId", s.userId)
            if (parentId != null) addQueryParameter("ParentId", parentId)
            addQueryParameter("IncludeItemTypes", types)
            addQueryParameter("Recursive", "true")
            addQueryParameter("EnableImages", "false")
        }

    suspend fun search(s: ServerSession, term: String, parentId: String? = null, limit: Int = 50): ItemsResult =
        get(s.baseUrl, s, "Users/${s.userId}/Items") {
            addQueryParameter("SearchTerm", term)
            addQueryParameter("IncludeItemTypes", "Movie,Series")
            addQueryParameter("Recursive", "true")
            if (parentId != null) addQueryParameter("ParentId", parentId)
            addQueryParameter("Limit", limit.toString())
            addQueryParameter("Fields", LIST_FIELDS)
        }

    /** Items with any of [ids] (`tmdb.123`, `imdb.tt1`); some servers ignore the filter and return everything. */
    suspend fun byProviderIds(s: ServerSession, types: String, ids: List<String>, limit: Int = 50): ItemsResult =
        get(s.baseUrl, s, "Users/${s.userId}/Items") {
            addQueryParameter("AnyProviderIdEquals", ids.joinToString(","))
            addQueryParameter("IncludeItemTypes", types)
            addQueryParameter("Recursive", "true")
            addQueryParameter("Limit", limit.toString())
            addQueryParameter("Fields", LIST_FIELDS)
        }

    /** A name search limited to one item type (version lookup looks at a few candidates only). */
    suspend fun searchType(s: ServerSession, term: String, types: String, limit: Int): ItemsResult =
        get(s.baseUrl, s, "Users/${s.userId}/Items") {
            addQueryParameter("SearchTerm", term)
            addQueryParameter("IncludeItemTypes", types)
            addQueryParameter("Recursive", "true")
            addQueryParameter("Limit", limit.toString())
            addQueryParameter("Fields", LIST_FIELDS)
        }

    /**
     * A light search for pinyin candidates and the pinyin probe: names, years and ids only, no
     * images, a handful of results.
     */
    suspend fun searchTitles(s: ServerSession, term: String, limit: Int): ItemsResult =
        get(s.baseUrl, s, "Users/${s.userId}/Items") {
            addQueryParameter("SearchTerm", term)
            addQueryParameter("IncludeItemTypes", "Movie,Series")
            addQueryParameter("Recursive", "true")
            addQueryParameter("Limit", limit.toString())
            addQueryParameter("Fields", "ProductionYear,ProviderIds")
            addQueryParameter("EnableImages", "false")
            addQueryParameter("EnableUserData", "false")
        }

    /** Full item, including MediaSources with their streams and chapters. */
    suspend fun item(s: ServerSession, itemId: String): BaseItemDto =
        get(s.baseUrl, s, "Users/${s.userId}/Items/$itemId") {
            addQueryParameter("Fields", "$LIST_FIELDS,People,OriginalTitle,PremiereDate,ProductionLocations,MediaSources,MediaStreams,Chapters")
        }

    /** Playback facts only; provider-backed detail pages do not ask for server descriptions or cast. */
    suspend fun resourceItem(s: ServerSession, itemId: String): BaseItemDto =
        get(s.baseUrl, s, "Users/${s.userId}/Items/$itemId") {
            addQueryParameter("Fields", "ProviderIds,MediaSources,MediaStreams,Chapters,Path,RunTimeTicks,UserData,PresentationUniqueKey")
        }

    /**
     * Titles like [itemId] (为你推荐, ui-spec §2.14), with their media sources for the version
     * count on each card. Read-only: it changes nothing on the server.
     */
    suspend fun similar(s: ServerSession, itemId: String, limit: Int = 12): ItemsResult =
        get(s.baseUrl, s, "Items/$itemId/Similar") {
            addQueryParameter("UserId", s.userId)
            addQueryParameter("Limit", limit.toString())
            addQueryParameter("Fields", "$LIST_FIELDS,MediaSources")
        }

    /**
     * Emby's suggestions for the user (首页主推区). Emby 4.x takes the generic item-query parameters
     * here (IncludeItemTypes, Limit, Fields); `Type` is Jellyfin's name for the same filter and
     * is ignored by Emby. Read-only.
     */
    suspend fun suggestions(s: ServerSession, limit: Int = 12): ItemsResult =
        get(s.baseUrl, s, "Users/${s.userId}/Suggestions") {
            addQueryParameter("IncludeItemTypes", "Movie,Series")
            addQueryParameter("Type", "Movie,Series")
            addQueryParameter("Limit", limit.toString())
            addQueryParameter("EnableTotalRecordCount", "false")
            addQueryParameter("Fields", LIST_FIELDS)
        }

    /** Films like those the user watched: [categoryLimit] categories of at most [itemLimit]. Read-only. */
    suspend fun movieRecommendations(s: ServerSession, categoryLimit: Int = 4, itemLimit: Int = 8): List<RecommendationDto> =
        get(s.baseUrl, s, "Movies/Recommendations") {
            addQueryParameter("UserId", s.userId)
            addQueryParameter("CategoryLimit", categoryLimit.toString())
            addQueryParameter("ItemLimit", itemLimit.toString())
            addQueryParameter("Fields", LIST_FIELDS)
        }

    /** Verify a restored token for its own user; never uses another server's user id. */
    suspend fun verifyUser(s: ServerSession): Boolean {
        val body: kotlinx.serialization.json.JsonObject = get(s.baseUrl, s, "Users/${s.userId}")
        return (body["Id"] as? kotlinx.serialization.json.JsonPrimitive)?.content == s.userId
    }

    /** Only the base fields and ProviderIds of [ids]: what is needed to key works without a full fetch. */
    suspend fun providerIdsOf(s: ServerSession, ids: List<String>): ItemsResult =
        get(s.baseUrl, s, "Users/${s.userId}/Items") {
            addQueryParameter("Ids", ids.joinToString(","))
            addQueryParameter("Fields", "ProviderIds")
            addQueryParameter("EnableImages", "false")
            addQueryParameter("EnableUserData", "false")
        }

    /** Films and series with this server's person [personId], with People for the role. */
    suspend fun itemsWithPerson(s: ServerSession, personId: String, limit: Int = 200): ItemsResult =
        get(s.baseUrl, s, "Users/${s.userId}/Items") {
            addQueryParameter("PersonIds", personId)
            addQueryParameter("IncludeItemTypes", "Movie,Series")
            addQueryParameter("Recursive", "true")
            addQueryParameter("Limit", limit.toString())
            addQueryParameter("Fields", "$LIST_FIELDS,People,PremiereDate")
        }

    /** 观影统计: one page of the user's played films and episodes, most recently played first. */
    suspend fun playedItems(s: ServerSession, start: Int, limit: Int): ItemsResult =
        get(s.baseUrl, s, "Users/${s.userId}/Items") {
            addQueryParameter("Recursive", "true")
            addQueryParameter("IncludeItemTypes", "Movie,Episode")
            addQueryParameter("Filters", "IsPlayed")
            addQueryParameter("SortBy", "DatePlayed")
            addQueryParameter("SortOrder", "Descending")
            addQueryParameter("StartIndex", start.toString())
            addQueryParameter("Limit", limit.toString())
            addQueryParameter("Fields", "ProviderIds,RunTimeTicks,UserData")
            addQueryParameter("EnableImages", "false")
        }

    suspend fun seasons(s: ServerSession, seriesId: String): ItemsResult =
        get(s.baseUrl, s, "Shows/$seriesId/Seasons") {
            addQueryParameter("UserId", s.userId)
            addQueryParameter("Fields", LIST_FIELDS)
        }

    /**
     * One page of a series' episodes. [startItemId] starts the list at that episode (it and the
     * ones after it, in series order), which is how the next episode is found.
     */
    suspend fun episodes(
        s: ServerSession,
        seriesId: String,
        seasonId: String? = null,
        start: Int = 0,
        limit: Int? = null,
        startItemId: String? = null,
        withSources: Boolean = true,
    ): ItemsResult =
        get(s.baseUrl, s, "Shows/$seriesId/Episodes") {
            addQueryParameter("UserId", s.userId)
            if (seasonId != null) addQueryParameter("SeasonId", seasonId)
            if (startItemId != null) addQueryParameter("StartItemId", startItemId)
            if (start > 0) addQueryParameter("StartIndex", start.toString())
            if (limit != null) addQueryParameter("Limit", limit.toString())
            addQueryParameter("Fields", if (withSources) "$LIST_FIELDS,MediaSources" else LIST_FIELDS)
        }

    /** The next episode to watch per series, for one series or (null) across all of them. */
    suspend fun nextUp(s: ServerSession, seriesId: String?, limit: Int = 1): ItemsResult =
        get(s.baseUrl, s, "Shows/NextUp") {
            addQueryParameter("UserId", s.userId)
            if (seriesId != null) addQueryParameter("SeriesId", seriesId)
            addQueryParameter("Limit", limit.toString())
            addQueryParameter("Fields", LIST_FIELDS)
        }

    suspend fun setPlayed(s: ServerSession, itemId: String, played: Boolean): UserItemData =
        send(s, "Users/${s.userId}/PlayedItems/$itemId", delete = !played)

    suspend fun setFavorite(s: ServerSession, itemId: String, favorite: Boolean): UserItemData =
        send(s, "Users/${s.userId}/FavoriteItems/$itemId", delete = !favorite)

    /**
     * Round-trip time of the unauthenticated info endpoint (null when unreachable) and the server
     * version it reports. The best of two tries, so connection and TLS setup on the first one don't
     * count as line latency.
     */
    suspend fun ping(baseUrl: String, ua: UaSetting = UaSetting(), serverId: String = ""): Pair<Long?, String?> {
        var version: String? = null
        var setting = ua
        suspend fun once(): Long? = try {
            val started = System.nanoTime()
            val base = normalizeBaseUrl(baseUrl)
            val reply = call(base, null, setting, serverId) { id -> request(base, null, "System/Info/Public", id) {}.get().build() }
            if (setting.canFallBack) setting = setting.copy(detected = reply.mode)
            version = json.decodeFromString<SystemInfoPublic>(reply.text).version
            (System.nanoTime() - started) / 1_000_000
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { null }
        val first = once() ?: run {
            kotlinx.coroutines.delay(350)
            return once() to version
        }
        return minOf(first, once() ?: first) to version
    }

    /** Asks the server for a direct-play source; [startPositionMs] only informs the server. */
    suspend fun playbackInfo(
        s: ServerSession,
        itemId: String,
        startPositionMs: Long,
        mediaSourceId: String? = null,
        /** False when only listing versions: no play session is opened. */
        isPlayback: Boolean = true,
    ): PlaybackInfoResponse =
        post(s.baseUrl, s, "Items/$itemId/PlaybackInfo", body = DIRECT_PLAY_PROFILE) {
            addQueryParameter("UserId", s.userId)
            addQueryParameter("StartTimeTicks", msToTicks(startPositionMs).toString())
            if (mediaSourceId != null) addQueryParameter("MediaSourceId", mediaSourceId)
            addQueryParameter("IsPlayback", isPlayback.toString())
            addQueryParameter("AutoOpenLiveStream", isPlayback.toString())
            addQueryParameter("MaxStreamingBitrate", MAX_BITRATE.toString())
        }

    suspend fun reportStart(s: ServerSession, report: PlaybackReport) =
        postNoContent(s, "Sessions/Playing", report)

    suspend fun reportProgress(s: ServerSession, report: PlaybackReport) =
        postNoContent(s, "Sessions/Playing/Progress", report)

    suspend fun reportStopped(s: ServerSession, report: PlaybackReport) =
        postNoContent(s, "Sessions/Playing/Stopped", report)

    /**
     * The URL handed to the Zidoo player. The player cannot send headers, so the token
     * travels as `api_key`.
     */
    fun streamUrl(s: ServerSession, itemId: String, source: MediaSourceInfo, playSessionId: String?): String {
        val container = source.container?.substringBefore(',')?.takeIf { it.isNotBlank() }
        val segment = if (container != null) "stream.$container" else "stream"
        return s.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("Videos/$itemId/$segment")
            .addQueryParameter("Static", "true")
            .addQueryParameter("MediaSourceId", source.id)
            .apply { if (playSessionId != null) addQueryParameter("PlaySessionId", playSessionId) }
            .addQueryParameter("DeviceId", clientInfo.deviceId)
            .addQueryParameter("api_key", s.accessToken)
            .build()
            .toString()
    }

    fun imageUrl(s: ServerSession, itemId: String, type: String, tag: String?, maxHeight: Int): String =
        s.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("Items/$itemId/Images/$type")
            .addQueryParameter("maxHeight", maxHeight.toString())
            .apply { if (tag != null) addQueryParameter("tag", tag) }
            .build()
            .toString()

    fun authorizationHeader(s: ServerSession?, id: ClientIdentity = s?.let(::identity) ?: clientInfo.identity(UaMode.Zmby)): String = buildString {
        append("Emby ")
        if (s != null) append("UserId=\"${s.userId}\", ")
        append("Client=\"${id.client}\", ")
        append("Device=\"${clientInfo.device}\", ")
        append("DeviceId=\"${clientInfo.deviceId}\", ")
        append("Version=\"${id.version}\"")
    }

    private suspend inline fun <reified T> get(
        base: String,
        s: ServerSession?,
        path: String,
        ua: UaSetting = s?.ua ?: UaSetting(),
        noinline query: HttpUrl.Builder.() -> Unit = {},
    ): T {
        val text = call(base, s, ua) { id -> request(base, s, path, id, query).get().build() }.text
        // Parsing a list of items is real work: never on the caller's (often the main) thread.
        return withContext(Dispatchers.Default) { json.decodeFromString(text) }
    }

    private suspend inline fun <reified T> post(
        base: String,
        s: ServerSession?,
        path: String,
        body: String,
        noinline query: HttpUrl.Builder.() -> Unit = {},
    ): T {
        val text = call(base, s, s?.ua ?: UaSetting()) { id -> request(base, s, path, id, query).post(body.toRequestBody(JSON)).build() }.text
        return withContext(Dispatchers.Default) { json.decodeFromString(text) }
    }

    private suspend fun send(s: ServerSession, path: String, delete: Boolean): UserItemData {
        val text = call(s.baseUrl, s, s.ua) { id ->
            val builder = request(s.baseUrl, s, path, id) {}
            if (delete) builder.delete().build() else builder.post(ByteArray(0).toRequestBody(JSON)).build()
        }.text
        return withContext(Dispatchers.Default) { json.decodeFromString(text) }
    }

    private suspend fun postNoContent(s: ServerSession, path: String, report: PlaybackReport) {
        val body = json.encodeToString(PlaybackReport.serializer(), report)
        call(s.baseUrl, s, s.ua, expectJson = false) { id -> request(s.baseUrl, s, path, id) {}.post(body.toRequestBody(JSON)).build() }
    }

    private fun request(base: String, s: ServerSession?, path: String, id: ClientIdentity, query: HttpUrl.Builder.() -> Unit): Request.Builder {
        val url = base.toHttpUrl().newBuilder().addPathSegments(path).apply(query).build()
        return Request.Builder()
            .url(url)
            .header("X-Emby-Authorization", authorizationHeader(s, id))
            .header("Accept", "application/json")
            .header("User-Agent", id.userAgent)
            .apply { if (s != null) header("X-Emby-Token", s.accessToken) }
    }

    /** A response body and the identity it was obtained with. */
    private class Reply(val text: String, val mode: UaMode)

    /**
     * Runs the request built for the server's identity. Under [UaMode.Auto], when the server turns
     * it away ([Rejection]), the other built-in identity (zmby ↔ official) is tried once; if that
     * gets through, [onIdentityChange] records it for the server. If the other identity fails too,
     * the first error stands and the other identity is not tried again for [FALLBACK_PAUSE_MS].
     */
    private suspend fun call(
        base: String,
        s: ServerSession?,
        ua: UaSetting,
        serverId: String = s?.id.orEmpty(),
        expectJson: Boolean = true,
        build: (ClientIdentity) -> Request,
    ): Reply {
        val first = ua.effective
        try {
            return Reply(execute(build(clientInfo.identity(first, ua.custom)), expectJson), first)
        } catch (e: IOException) {
            if (!ua.canFallBack || !Rejection.isRejection(e)) throw e
            val other = if (first == UaMode.Official) UaMode.Zmby else UaMode.Official
            val key = "$base|$other"
            val failedAt = fallbackFailedAt[key]
            if (failedAt != null && clock() - failedAt < FALLBACK_PAUSE_MS) throw e
            val text = try {
                execute(build(clientInfo.identity(other)), expectJson)
            } catch (e2: IOException) {
                fallbackFailedAt[key] = clock()
                ZLog.d(TAG, "$base: the ${other.label} identity did not help either: $e2")
                throw e
            }
            fallbackFailedAt.remove(key)
            ZLog.i(TAG, "$base turned ${first.label} away ($e); ${other.label} got through, using it from now on")
            onIdentityChange?.invoke(base, serverId, other)
            return Reply(text, other)
        }
    }

    /**
     * Runs [request] and returns its body. Cancelling the coroutine cancels the HTTP call: a blocking
     * execute() would ignore the fan-out's timeout and hold it until OkHttp's own read timeout.
     */
    private suspend fun execute(request: Request, expectJson: Boolean = true): String = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        fun took() = (System.nanoTime() - started) / 1_000_000
        val call = http.newCall(request)
        try {
            call.await().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    ZLog.w(TAG, "${request.method} ${request.url} -> ${response.code} ${took()}ms ${text.take(200)}")
                    throw EmbyException("HTTP ${response.code} ${request.url.encodedPath}: ${text.take(200)}", response.code)
                }
                // A 2xx page instead of JSON: a block page or a portal, not Emby.
                val type = response.header("Content-Type").orEmpty()
                if (type.contains("html", ignoreCase = true) || text.trimStart().startsWith("<") || (expectJson && text.isBlank())) {
                    ZLog.w(TAG, "${request.method} ${request.url} -> ${response.code} not Emby ($type) ${text.take(120)}")
                    throw NotEmbyException("not an Emby answer from ${request.url.encodedPath}")
                }
                ZLog.d(TAG, "${request.method} ${request.url} -> ${response.code} ${took()}ms ${text.length}B")
                text
            }
        } catch (e: IOException) {
            if (e !is EmbyException) ZLog.w(TAG, "${request.method} ${request.url} failed after ${took()}ms: $e")
            throw e
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) = cont.resume(response) { _, value, _ -> value.close() }
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }
        })
    }

    companion object {
        private const val TAG = "Emby"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val MAX_BITRATE = 1_000_000_000L
        const val FALLBACK_PAUSE_MS = 10 * 60 * 1000L
        const val LIST_FIELDS =
            "ProviderIds,ProductionYear,RunTimeTicks,BackdropImageTags,UserData,Overview,Genres,CommunityRating,ChildCount,Path,PresentationUniqueKey,DateCreated,PremiereDate,SortName"

        /** Accepts "host:8096", "http://host:8096/", "https://host/emby" and returns it without a trailing slash. */
        fun normalizeBaseUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
            return withScheme.toHttpUrl().toString().trimEnd('/')
        }

        /**
         * A device profile that lets the server offer every file as-is: the Zidoo player
         * decodes or passes through everything, and transcoding is out of scope.
         */
        val DIRECT_PLAY_PROFILE: String = buildJsonObject {
            putJsonObject("DeviceProfile") {
                put("Name", "Zidoo native player")
                put("MaxStreamingBitrate", MAX_BITRATE)
                put("MaxStaticBitrate", MAX_BITRATE)
                putJsonArray("DirectPlayProfiles") {
                    add(buildJsonObject { put("Type", "Video") })
                    add(buildJsonObject { put("Type", "Audio") })
                }
                putJsonArray("TranscodingProfiles") {}
                putJsonArray("ContainerProfiles") {}
                putJsonArray("CodecProfiles") {}
                putJsonArray("SubtitleProfiles") {
                    for (format in listOf("srt", "ass", "ssa", "subrip", "vtt", "sub", "idx", "pgs", "pgssub", "dvdsub", "sup")) {
                        add(buildJsonObject { put("Format", format); put("Method", "Embed") })
                        add(buildJsonObject { put("Format", format); put("Method", "External") })
                    }
                }
            }
        }.toString()
    }
}
