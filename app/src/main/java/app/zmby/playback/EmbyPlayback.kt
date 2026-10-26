package app.zmby.playback

import app.zmby.emby.BaseItemDto
import app.zmby.emby.EmbyClient
import app.zmby.emby.EmbyException
import app.zmby.emby.MediaSourceInfo
import app.zmby.emby.PlaybackReport
import app.zmby.emby.ServerSession
import app.zmby.emby.msToTicks
import app.zmby.emby.ticksToMs
import app.zmby.log.ZLog
import app.zmby.data.Override
import app.zmby.data.ServerCapsStore
import app.zmby.data.ServerCaps
import app.zmby.emby.UaSetting
import app.zmby.zidoo.ExternalSubtitle
import app.zmby.zidoo.ZidooLaunchRequest
import app.zmby.zidoo.ZidooTracks
import app.zmby.zidoo.ZidooPlayer.SubtitleDelivery
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import app.zmby.zidoo.ZidooLaunchSpec
import app.zmby.zidoo.buildZidooLaunch

/** Sends one playback's start/progress/stop to the server that owns the media source. */
class EmbyPlaybackReporter(
    private val client: EmbyClient,
    private val session: ServerSession,
    private val itemId: String,
    private val mediaSourceId: String,
    private val playSessionId: String?,
    private val runTimeTicks: Long?,
) : PlaybackReporter {

    override suspend fun start(positionMs: Long) = send("start", positionMs) {
        client.reportStart(session, report(positionMs, paused = false, event = null))
    }

    override suspend fun progress(positionMs: Long, paused: Boolean) = send(if (paused) "progress(paused)" else "progress", positionMs) {
        client.reportProgress(session, report(positionMs, paused, event = if (paused) "Pause" else "TimeUpdate"))
    }

    override suspend fun stopped(positionMs: Long) = send("stopped", positionMs) {
        client.reportStopped(session, report(positionMs, paused = false, event = null))
    }

    override suspend fun markPlayed() = send("played", 0) {
        client.setPlayed(session, itemId, true)
    }

    private fun report(positionMs: Long, paused: Boolean, event: String?) = PlaybackReport(
        itemId = itemId,
        mediaSourceId = mediaSourceId,
        playSessionId = playSessionId,
        positionTicks = msToTicks(positionMs),
        isPaused = paused,
        eventName = event,
        runTimeTicks = runTimeTicks,
    )

    // A missed report must never break playback; the next one carries the position again.
    private suspend fun send(what: String, positionMs: Long, block: suspend () -> Unit) {
        try {
            block()
            ZLog.i(TAG, "report $what ok item=$itemId pos=${positionMs}ms")
        } catch (e: Exception) {
            ZLog.w(TAG, "report $what failed item=$itemId pos=${positionMs}ms: $e")
        }
    }

    private companion object {
        const val TAG = "Report"
    }
}

/** Everything needed to launch the player and report the playback afterwards. */
data class PreparedPlayback(
    val launch: ZidooLaunchSpec,
    val reporter: EmbyPlaybackReporter,
    val startPositionMs: Long,
    val runtimeMs: Long?,
    /** Set when the video goes out as a local .strm with the chosen subtitle beside it; release it after playback. */
    val local: LocalStrmSession? = null,
    /** The languages played, carried over to the next episode or another version. */
    val languages: TrackLanguages? = null,
)

/** The user's track picks in Emby stream indexes; null = the server's default for this user. */
data class TrackChoice(val audio: Int? = null, val subtitle: Int? = null)

class PlaybackPreparer(
    private val client: EmbyClient,
    private val probe: StreamProbe,
    private val caps: ServerCapsStore,
    private val relay: StreamRelay? = null,
    private val localStrm: LocalStrmPreparer? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {

    suspend fun prepare(
        session: ServerSession,
        item: BaseItemDto,
        requestedStartMs: Long,
        mediaSourceId: String? = null,
        watchedPercent: Int = WatchThreshold.DEFAULT_PERCENT,
        picked: TrackChoice = TrackChoice(),
        /** How the target player takes an external subtitle. */
        subtitles: SubtitleDelivery = SubtitleDelivery.IntentExtras,
        /** Language preference for tracks the user did not pick ([TrackPreference]). */
        audioLanguage: String = TrackPreference.SERVER,
        subtitleLanguage: String = TrackPreference.SERVER,
    ): PreparedPlayback {
        val startPositionMs = WatchThreshold.startPosition(requestedStartMs, item.runtimeMs, watchedPercent)
        if (startPositionMs != requestedStartMs) {
            ZLog.i(TAG, "resume ${requestedStartMs}ms is past $watchedPercent% of ${item.runtimeMs}ms; starting over")
        }
        val info = client.playbackInfo(session, item.id, startPositionMs, mediaSourceId)
        info.errorCode?.let { throw EmbyException("服务器拒绝播放（$it）") }
        val source = chooseSource(info.mediaSources, mediaSourceId)
        val tracks = TrackPreference.choose(source, picked, audioLanguage, subtitleLanguage)
        val runTimeTicks = source.runTimeTicks ?: item.runTimeTicks
        val backdropTag = item.backdropImageTags.firstOrNull()
        val direct = StreamUrls.direct(session.baseUrl, source, session.accessToken)
        val url = checkedUrl(session, direct ?: client.streamUrl(session, item.id, source, info.playSessionId), source.fileName)
        val viaIntent = subtitles == SubtitleDelivery.IntentExtras
        val (audioIdx, subtitleIdx) = ZidooTracks.choose(source, tracks.audio, tracks.subtitle, externalAllowed = viaIntent)
        val externals = if (viaIntent) externalSubtitles(session, item, source) else emptyList()
        ZLog.i(
            TAG,
            "source ${source.id} via ${if (direct != null) "DirectStreamUrl" else "/Videos/stream"}; " +
                "tracks emby=$tracks default=(${source.defaultAudioStreamIndex},${source.defaultSubtitleStreamIndex}) -> zidoo audio=$audioIdx sub=$subtitleIdx; external subs=${externals.size}",
        )
        // An external subtitle for com.zidoo.player: the video URL (direct or relay, unchanged) goes
        // into a local .strm next to the downloaded subtitle; subtitlePid replaces subtitle_idx.
        val localSub = if (subtitles == SubtitleDelivery.LocalStrm && localStrm != null) {
            ZidooTracks.wantedSubtitle(source, tracks.subtitle)?.let { idx -> source.mediaStreams.firstOrNull { it.index == idx && LocalStrm.supports(it) } }
        } else null
        val local = localSub?.let { stream ->
            val subtitleUrl = StreamUrls.subtitle(session.baseUrl, item.id, source, stream, session.accessToken)
                ?: throw SubtitlePrepareException("字幕没有下载地址")
            val key = LocalStrm.sourceKey(session.serverId ?: session.capsKey, item.id, source.id)
            localStrm!!.prepare(url, key, stream, subtitleUrl).also {
                ZLog.i(TAG, "external subtitle ${stream.index} (${stream.codec}) -> ${it.subtitle.path}, pid ${it.subtitlePid}")
            }
        }
        val launch = buildZidooLaunch(
            ZidooLaunchRequest(
                url = local?.uri ?: url,
                container = source.container,
                title = item.displayTitle,
                startPositionMs = startPositionMs,
                loadingImageUrl = backdropTag?.let { client.imageUrl(session, item.id, "Backdrop", it, 1080) },
                clearLogoUrl = logoUrl(session, item),
                subtitleSearchTitle = subtitleSearchTitle(item),
                fileName = source.fileName,
                userAgent = client.identity(session).userAgent,
                audioIdx = audioIdx,
                subtitleIdx = subtitleIdx,
                externalSubtitles = externals,
                subtitlePid = local?.subtitlePid,
            ),
        )
        val reporter = EmbyPlaybackReporter(client, session, item.id, source.id, info.playSessionId, runTimeTicks)
        val languages = TrackPreference.carried(source, tracks, audioLanguage, subtitleLanguage)
        return PreparedPlayback(launch, reporter, startPositionMs, runTimeTicks?.let(::ticksToMs), local, languages)
    }

    /**
     * Applies what is known about the server's streams, probing once per server (re-checked weekly):
     * the path prefix under which Range works. Only URLs on the server's own host are rewritten.
     */
    private suspend fun checkedUrl(session: ServerSession, serverUrl: String, fileName: String?): String {
        var url = serverUrl
        val key = session.capsKey
        var known = caps.get(key)
        if (known.streamStale(now())) {
            val check = probe.check(url, client.identity(session).userAgent)
            if (check != null) {
                known = caps.update(key) {
                    it.copy(rangePrefix = check.rangePrefix, redirects = check.redirect != null, streamCheckedAt = now())
                }
                ZLog.i(TAG, "server $key: range prefix '${check.rangePrefix}', redirects=${check.redirect != null}")
            }
        }
        val prefix = known.rangePrefix
        if (prefix != null && url.toHttpUrlOrNull()?.host == session.baseUrl.toHttpUrlOrNull()?.host && StreamUrls.prefixOf(url) != prefix) {
            url = StreamUrls.withPrefix(url, prefix)
        }
        if (!usesRelay(known, session.ua) || relay == null) return url
        ZLog.i(TAG, "server $key goes through the local relay (${known.relay}, redirects=${known.redirects}, identity ${session.ua.effective.label})")
        return relay.register(url, fileName, client.identity(session).userAgent)
    }

    /** The item's own Logo, or for an episode its series' logo; null when the server has none. */
    private fun logoUrl(session: ServerSession, item: BaseItemDto): String? {
        item.imageTags["Logo"]?.let { return client.imageUrl(session, item.id, "Logo", it, LOGO_HEIGHT) }
        val parent = item.parentLogoItemId ?: return null
        val tag = item.parentLogoImageTag ?: return null
        return client.imageUrl(session, parent, "Logo", tag, LOGO_HEIGHT)
    }

    private fun externalSubtitles(session: ServerSession, item: BaseItemDto, source: MediaSourceInfo): List<ExternalSubtitle> {
        val base = source.fileName?.substringBeforeLast('.') ?: item.name ?: "subtitle"
        return ZidooTracks.externalSubtitles(source).mapNotNull { s ->
            val url = StreamUrls.subtitle(session.baseUrl, item.id, source, s, session.accessToken) ?: return@mapNotNull null
            val name = s.title?.takeIf { it.isNotBlank() } ?: s.displayTitle ?: s.language ?: "字幕 ${s.index}"
            val lang = s.language?.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
            ExternalSubtitle(url, name, "$base$lang.${s.index}.${StreamUrls.subtitleExtension(s.codec)}")
        }
    }

    companion object {
        private const val TAG = "Play"
        private const val LOGO_HEIGHT = 360

        /**
         * Whether a server detected as 302 plays through [StreamRelay] by default. No: on the
         * Z9X 8K (2026-10-24) com.zidoo.player requests the Emby URL again for every new connection
         * (start, index read, seek), so it gets a fresh signature after the old one expired, and an
         * open connection keeps reading past expiry. The relay stays available per server (设置 ›
         * 取流方式 › 本地转发) for links bound to a User-Agent, which the player cannot send.
         */
        const val RELAY_REDIRECTING_SERVERS = false

        /**
         * 取流方式: a fixed choice wins; 自动 relays a server that needs another identity than zmby
         * (it very likely checks the User-Agent of streams too, and the player always sends
         * `okhttp/4.11.0`), and a 302 server only if [RELAY_REDIRECTING_SERVERS].
         */
        fun usesRelay(caps: ServerCaps, ua: UaSetting): Boolean = when (caps.relay) {
            Override.On -> true
            Override.Off -> false
            Override.Auto -> ua.needsRelay || (caps.redirects == true && RELAY_REDIRECTING_SERVERS)
        }
    }
}

/**
 * The version the user picked, else the first source the server will hand out unchanged. A picked
 * version that the server no longer offers is an error: silently playing another version (another
 * cut, another quality, possibly another film) is worse than saying so.
 */
fun chooseSource(sources: List<MediaSourceInfo>, preferredId: String? = null): MediaSourceInfo {
    if (preferredId != null) {
        return sources.firstOrNull { it.id == preferredId }
            ?: throw EmbyException("所选版本已不在服务器上（$preferredId）")
    }
    return sources.firstOrNull { it.supportsDirectPlay || it.supportsDirectStream }
        ?: sources.firstOrNull()
        ?: throw EmbyException("没有可直接播放的片源")
}

fun subtitleSearchTitle(item: BaseItemDto): String? = when {
    item.type == "Episode" && !item.seriesName.isNullOrBlank() -> {
        val s = item.parentIndexNumber
        val e = item.indexNumber
        if (s != null && e != null) "%s S%02dE%02d".format(item.seriesName, s, e) else item.seriesName
    }
    item.productionYear != null && !item.name.isNullOrBlank() -> "${item.name} ${item.productionYear}"
    else -> item.name
}
