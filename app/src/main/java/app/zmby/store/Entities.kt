package app.zmby.store

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * The local store's tables (local-data spec, 表). Works and people are keyed by external IDs
 * (StoreKeys); everything that belongs to a server is keyed by (server_id, item_id).
 */

/** An image picked for a work or person: which server's which item, its tag and size. */
data class ImageRef(
    val server: String,
    val item: String,
    val tag: String,
    val width: Int? = null,
    val height: Int? = null,
)

/** One work's merged metadata (MetaMerge), shared by all servers that have it. */
@Entity(tableName = "work_meta", indices = [Index("accessed_at")])
data class WorkMeta(
    @PrimaryKey @ColumnInfo(name = "work_key") val workKey: String,
    val type: String,
    val title: String? = null,
    @ColumnInfo(name = "title_server") val titleServer: String? = null,
    @ColumnInfo(name = "original_title") val originalTitle: String? = null,
    val overview: String? = null,
    @ColumnInfo(name = "overview_server") val overviewServer: String? = null,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    @ColumnInfo(name = "genres_server") val genresServer: String? = null,
    val rating: Double? = null,
    /** The rating came with a TMDB id (shown as 「TMDB 8.1」). */
    @ColumnInfo(name = "rating_tmdb") val ratingTmdb: Boolean = false,
    @ColumnInfo(name = "runtime_ticks") val runtimeTicks: Long? = null,
    @ColumnInfo(name = "premiere_date") val premiereDate: String? = null,
    @Embedded(prefix = "primary_") val primary: ImageRef? = null,
    @Embedded(prefix = "backdrop_") val backdrop: ImageRef? = null,
    @Embedded(prefix = "logo_") val logo: ImageRef? = null,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long = 0,
    @ColumnInfo(name = "accessed_at") val accessedAt: Long = 0,
)

/**
 * A work on one server: the item as last fetched (list fields in [itemJson], the full item in
 * [detailJson]), its server-side user data, and the work it belongs to ([workKey], computed once on
 * the way in so reading needs no title normalization).
 */
@Entity(
    tableName = "work_instance",
    primaryKeys = ["server_id", "item_id"],
    indices = [Index("work_key"), Index("accessed_at")],
)
data class WorkInstance(
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "work_key") val workKey: String,
    val type: String,
    @ColumnInfo(name = "item_json") val itemJson: String,
    @ColumnInfo(name = "detail_json") val detailJson: String? = null,
    /** `/Items/{id}/Images` (sizes); null = not asked yet. */
    @ColumnInfo(name = "images_json") val imagesJson: String? = null,
    val played: Boolean = false,
    @ColumnInfo(name = "position_ticks") val positionTicks: Long = 0,
    @ColumnInfo(name = "server_favorite") val serverFavorite: Boolean = false,
    @ColumnInfo(name = "date_created") val dateCreated: String? = null,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long = 0,
    @ColumnInfo(name = "detail_at") val detailAt: Long = 0,
    @ColumnInfo(name = "accessed_at") val accessedAt: Long = 0,
)

/** One person (TMDB person id, else IMDb, else one server's own id), merged like works. */
@Entity(tableName = "person", indices = [Index("accessed_at")])
data class Person(
    @PrimaryKey @ColumnInfo(name = "person_key") val personKey: String,
    val name: String,
    @ColumnInfo(name = "name_server") val nameServer: String? = null,
    val overview: String? = null,
    @ColumnInfo(name = "birth_date") val birthDate: String? = null,
    @ColumnInfo(name = "birth_place") val birthPlace: String? = null,
    @Embedded(prefix = "image_") val image: ImageRef? = null,
    val tmdb: String? = null,
    val imdb: String? = null,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long = 0,
    /** When the person page last fetched the person item (0 = only seen in a cast list). */
    @ColumnInfo(name = "detail_at") val detailAt: Long = 0,
    @ColumnInfo(name = "accessed_at") val accessedAt: Long = 0,
)

/** A server's own person id and what that server calls the person; person ids never cross servers. */
@Entity(tableName = "person_alias", primaryKeys = ["server_id", "person_id"], indices = [Index("person_key")])
data class PersonAlias(
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "person_id") val personId: String,
    @ColumnInfo(name = "person_key") val personKey: String,
    val name: String,
    @ColumnInfo(name = "image_tag") val imageTag: String? = null,
)

/** A person in a work: Actor, Director or Writer, with the role and the server's order. */
@Entity(tableName = "work_person", primaryKeys = ["work_key", "person_key", "kind"], indices = [Index("person_key")])
data class WorkPerson(
    @ColumnInfo(name = "work_key") val workKey: String,
    @ColumnInfo(name = "person_key") val personKey: String,
    val kind: String,
    val role: String? = null,
    val ord: Int = 0,
)

/**
 * The last version card of a server for the work [forKey] (the work the detail page shows): the
 * matched item, its sources (PlaybackInfo) and the match reason, shown at once next time.
 */
@Entity(tableName = "version_summary", primaryKeys = ["for_key", "server_id"])
data class VersionSummary(
    @ColumnInfo(name = "for_key") val forKey: String,
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "item_json") val itemJson: String,
    @ColumnInfo(name = "sources_json") val sourcesJson: String,
    val reason: String,
    val possible: Boolean = false,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long = 0,
)

/** A list as last fetched: a home row, a browse page, a person's works, a resume snapshot. */
@Entity(tableName = "list_cache")
data class ListCache(
    @PrimaryKey @ColumnInfo(name = "list_key") val listKey: String,
    val total: Int = 0,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long = 0,
)

/** The ordered (server_id, item_id) references of a [ListCache]. */
@Entity(tableName = "list_item", primaryKeys = ["list_key", "ord"], indices = [Index(value = ["server_id", "item_id"])])
data class ListItem(
    @ColumnInfo(name = "list_key") val listKey: String,
    val ord: Int,
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "item_id") val itemId: String,
)

/** A favourite work (favorites spec), with its tombstone. */
@Entity(tableName = "favorite")
data class FavoriteRow(
    @PrimaryKey @ColumnInfo(name = "work_key") val workKey: String,
    val title: String,
    val year: Int? = null,
    val type: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "removed_at") val removedAt: Long? = null,
    @ColumnInfo(name = "image_tag") val imageTag: String? = null,
)

@Entity(tableName = "favorite_instance", primaryKeys = ["work_key", "server_id", "item_id"], indices = [Index(value = ["server_id", "item_id"])])
data class FavoriteInstanceRow(
    @ColumnInfo(name = "work_key") val workKey: String,
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "item_id") val itemId: String,
    val synced: Boolean = false,
    val ord: Int = 0,
)

/** A favourite write waiting to be sent to a server. */
@Entity(tableName = "favorite_write", primaryKeys = ["server_id", "item_id"])
data class FavoriteWriteRow(
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "item_id") val itemId: String,
    val favorite: Boolean,
    val attempts: Int = 0,
    val ord: Int = 0,
)

/** 待看 (favorites spec): a work the user means to watch; purely local. */
@Entity(tableName = "watchlist")
data class WatchlistRow(
    @PrimaryKey @ColumnInfo(name = "work_key") val workKey: String,
    val title: String,
    val year: Int? = null,
    val type: String,
    @ColumnInfo(name = "premiere_date") val premiereDate: String? = null,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    /** Where the work was seen: `server|item` pairs, the first one shown. */
    val instances: List<String> = emptyList(),
    @ColumnInfo(name = "image_tag") val imageTag: String? = null,
)

/** A segment (intro, recap, credits, preview) from one provider (P4 uses them). */
@Entity(tableName = "segment_marker", primaryKeys = ["work_key", "source", "kind"])
data class SegmentMarker(
    @ColumnInfo(name = "work_key") val workKey: String,
    val source: String,
    val kind: String,
    @ColumnInfo(name = "duration_ms") val durationMs: Long? = null,
    @ColumnInfo(name = "start_ms") val startMs: Long,
    @ColumnInfo(name = "end_ms") val endMs: Long,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long = 0,
)

/** 设置 › 数据来源: whether [source] (a server id or a segment provider) serves [feature], and its order. */
@Entity(tableName = "data_source", primaryKeys = ["feature", "source"])
data class DataSourceRow(
    val feature: String,
    val source: String,
    val enabled: Boolean,
    val ord: Int = 0,
)

/** Last pull per feature and server (favourites, watch history), and one-off markers (migrations). */
@Entity(tableName = "sync_state", primaryKeys = ["feature", "server_id"])
data class SyncState(
    val feature: String,
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "last_pull_at") val lastPullAt: Long = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
)
