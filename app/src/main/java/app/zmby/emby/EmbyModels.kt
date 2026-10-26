package app.zmby.emby

import app.zmby.media.fileTitle
import app.zmby.media.visibleText
import app.zmby.media.titleOf
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Emby counts time in ticks of 100 ns. */
const val TICKS_PER_MS = 10_000L

fun ticksToMs(ticks: Long): Long = ticks / TICKS_PER_MS
fun msToTicks(ms: Long): Long = ms * TICKS_PER_MS

@Serializable
data class AuthenticationResult(
    @SerialName("User") val user: UserDto,
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("ServerId") val serverId: String? = null,
)

@Serializable
data class UserDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
)

@Serializable
data class SystemInfoPublic(
    @SerialName("Id") val id: String? = null,
    @SerialName("ServerName") val serverName: String? = null,
    @SerialName("Version") val version: String? = null,
)

@Serializable
data class ItemsResult(
    @SerialName("Items") val items: List<BaseItemDto> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int = 0,
)

@Serializable
data class BaseItemDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("Genres") val genres: List<String> = emptyList(),
    @SerialName("CommunityRating") val communityRating: Double? = null,
    @SerialName("OfficialRating") val officialRating: String? = null,
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("SeasonId") val seasonId: String? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("ChildCount") val childCount: Int? = null,
    @SerialName("IsFolder") val isFolder: Boolean = false,
    @SerialName("Path") val path: String? = null,
    @SerialName("UserData") val userData: UserItemData? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    @SerialName("BackdropImageTags") val backdropImageTags: List<String> = emptyList(),
    @SerialName("ParentBackdropItemId") val parentBackdropItemId: String? = null,
    @SerialName("ParentBackdropImageTags") val parentBackdropImageTags: List<String> = emptyList(),
    @SerialName("ParentThumbItemId") val parentThumbItemId: String? = null,
    @SerialName("ParentThumbImageTag") val parentThumbImageTag: String? = null,
    @SerialName("SeriesPrimaryImageTag") val seriesPrimaryImageTag: String? = null,
    @SerialName("ParentLogoItemId") val parentLogoItemId: String? = null,
    @SerialName("ParentLogoImageTag") val parentLogoImageTag: String? = null,
    @SerialName("ProviderIds") val providerIds: Map<String, String> = emptyMap(),
    @SerialName("PresentationUniqueKey") val presentationUniqueKey: String? = null,
    @SerialName("DateCreated") val dateCreated: String? = null,
    @SerialName("MediaSources") val mediaSources: List<MediaSourceInfo> = emptyList(),
    @SerialName("Chapters") val chapters: List<ChapterInfo> = emptyList(),
    @SerialName("OriginalTitle") val originalTitle: String? = null,
    @SerialName("SortName") val sortName: String? = null,
    /** First air or release date (ISO); a person's birth date. */
    @SerialName("PremiereDate") val premiereDate: String? = null,
    /** Cast and crew; only the single-item endpoint (or Fields=People) carries it. */
    @SerialName("People") val people: List<PersonDto> = emptyList(),
    /** A person's birthplace. */
    @SerialName("ProductionLocations") val productionLocations: List<String> = emptyList(),
) {
    val isPlayable: Boolean get() = type in PLAYABLE_TYPES

    /** "Series S01E02 - Name" for episodes, otherwise the item name. */
    val displayTitle: String
        get() {
            val series = visibleText(seriesName)
            if (type != "Episode" || series == null) return titleOf(this)
            val name = visibleText(name) ?: visibleText(originalTitle) ?: fileTitle(path)
            val s = parentIndexNumber
            val e = indexNumber
            val code = if (s != null && e != null) " S%02dE%02d".format(s, e) else ""
            return if (name == null) "$series$code" else "$series$code - $name"
        }

    val runtimeMs: Long? get() = runTimeTicks?.let(::ticksToMs)
    val resumeMs: Long get() = userData?.playbackPositionTicks?.let(::ticksToMs) ?: 0

    companion object {
        val PLAYABLE_TYPES = setOf("Movie", "Episode", "Video", "MusicVideo")
    }
}

/** One of an item's People: the id is this server's own person id. */
@Serializable
data class PersonDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("Role") val role: String? = null,
    /** Actor, Director, Writer, Producer, GuestStar, Composer… */
    @SerialName("Type") val type: String? = null,
    @SerialName("PrimaryImageTag") val primaryImageTag: String? = null,
    @SerialName("ProviderIds") val providerIds: Map<String, String> = emptyMap(),
)

/** One image of an item (`/Items/{id}/Images`), with the size used to pick the sharpest copy. */
@Serializable
data class ImageInfo(
    @SerialName("ImageType") val imageType: String? = null,
    @SerialName("ImageIndex") val imageIndex: Int? = null,
    @SerialName("ImageTag") val imageTag: String? = null,
    @SerialName("Width") val width: Int? = null,
    @SerialName("Height") val height: Int? = null,
    @SerialName("Path") val path: String? = null,
)

@Serializable
data class ChapterInfo(
    @SerialName("Name") val name: String? = null,
    @SerialName("StartPositionTicks") val startPositionTicks: Long = 0,
    /** Emby 4.8+: Chapter, IntroStart, IntroEnd or CreditsStart (片段标记 from the server). */
    @SerialName("MarkerType") val markerType: String? = null,
)

@Serializable
data class UserItemData(
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long = 0,
    @SerialName("PlayedPercentage") val playedPercentage: Double? = null,
    @SerialName("Played") val played: Boolean = false,
    @SerialName("IsFavorite") val isFavorite: Boolean = false,
    @SerialName("UnplayedItemCount") val unplayedItemCount: Int? = null,
    @SerialName("LastPlayedDate") val lastPlayedDate: String? = null,
    @SerialName("PlayCount") val playCount: Int? = null,
)

@Serializable
data class PlaybackInfoResponse(
    @SerialName("MediaSources") val mediaSources: List<MediaSourceInfo> = emptyList(),
    @SerialName("PlaySessionId") val playSessionId: String? = null,
    @SerialName("ErrorCode") val errorCode: String? = null,
)

@Serializable
data class MediaSourceInfo(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("Path") val path: String? = null,
    @SerialName("Protocol") val protocol: String? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("IsRemote") val isRemote: Boolean = false,
    @SerialName("SupportsDirectPlay") val supportsDirectPlay: Boolean = true,
    @SerialName("SupportsDirectStream") val supportsDirectStream: Boolean = true,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("Size") val size: Long? = null,
    @SerialName("Bitrate") val bitrate: Long? = null,
    @SerialName("VideoType") val videoType: String? = null,
    @SerialName("MediaStreams") val mediaStreams: List<MediaStream> = emptyList(),
    @SerialName("DefaultAudioStreamIndex") val defaultAudioStreamIndex: Int? = null,
    @SerialName("DefaultSubtitleStreamIndex") val defaultSubtitleStreamIndex: Int? = null,
    /** What the server would hand a client to play the file as-is; often relative, see StreamUrls.direct. */
    @SerialName("DirectStreamUrl") val directStreamUrl: String? = null,
) {
    val videoStream: MediaStream? get() = mediaStreams.firstOrNull { it.type == "Video" }
    val audioStreams: List<MediaStream> get() = mediaStreams.filter { it.type == "Audio" }
    val subtitleStreams: List<MediaStream> get() = mediaStreams.filter { it.type == "Subtitle" }
    val fileName: String? get() = path?.replace('\\', '/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
}

@Serializable
data class MediaStream(
    @SerialName("Index") val index: Int,
    @SerialName("Type") val type: String? = null,
    @SerialName("Codec") val codec: String? = null,
    @SerialName("Profile") val profile: String? = null,
    @SerialName("Level") val level: Double? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("Title") val title: String? = null,
    @SerialName("DisplayTitle") val displayTitle: String? = null,
    @SerialName("DisplayLanguage") val displayLanguage: String? = null,
    @SerialName("IsExternal") val isExternal: Boolean = false,
    @SerialName("IsDefault") val isDefault: Boolean = false,
    @SerialName("IsForced") val isForced: Boolean = false,
    @SerialName("DeliveryUrl") val deliveryUrl: String? = null,
    @SerialName("Width") val width: Int? = null,
    @SerialName("Height") val height: Int? = null,
    @SerialName("BitRate") val bitRate: Long? = null,
    @SerialName("BitDepth") val bitDepth: Int? = null,
    @SerialName("Channels") val channels: Int? = null,
    @SerialName("ChannelLayout") val channelLayout: String? = null,
    @SerialName("SampleRate") val sampleRate: Int? = null,
    @SerialName("RealFrameRate") val realFrameRate: Double? = null,
    @SerialName("AverageFrameRate") val averageFrameRate: Double? = null,
    @SerialName("VideoRange") val videoRange: String? = null,
    @SerialName("VideoRangeType") val videoRangeType: String? = null,
    @SerialName("ExtendedVideoType") val extendedVideoType: String? = null,
    @SerialName("ExtendedVideoSubType") val extendedVideoSubType: String? = null,
    @SerialName("ExtendedVideoSubTypeDescription") val extendedVideoSubTypeDescription: String? = null,
    @SerialName("ColorPrimaries") val colorPrimaries: String? = null,
    @SerialName("ColorTransfer") val colorTransfer: String? = null,
    @SerialName("ColorSpace") val colorSpace: String? = null,
    @SerialName("PixelFormat") val pixelFormat: String? = null,
)

/** Body of /Sessions/Playing, /Sessions/Playing/Progress and /Sessions/Playing/Stopped. */
@Serializable
data class PlaybackReport(
    @SerialName("ItemId") val itemId: String,
    @SerialName("MediaSourceId") val mediaSourceId: String,
    @SerialName("PlaySessionId") val playSessionId: String?,
    // Always sent: Emby treats a missing PositionTicks as 0 and wipes the resume point.
    @SerialName("PositionTicks") val positionTicks: Long,
    @SerialName("IsPaused") val isPaused: Boolean = false,
    @SerialName("CanSeek") val canSeek: Boolean = true,
    @SerialName("PlayMethod") val playMethod: String = "DirectPlay",
    @SerialName("EventName") val eventName: String? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
)
