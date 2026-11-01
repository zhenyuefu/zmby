package app.zmby.store

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 我的 (local-data spec, 我的：本机播放会话与统计口径): one playback zmby started and the player
 * confirmed, on the server and item actually played. [spans] are the wall-clock stretches that were
 * really playing ([SessionSpans]); [watchedMs] is their sum. [open] sessions are still being
 * followed, or were cut off by a crash and are closed at the next start at their last checkpoint.
 */
@Entity(
    tableName = "play_session",
    indices = [Index("started_at"), Index("work_key"), Index(value = ["server_id", "item_id"])],
)
data class PlaySessionRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "item_id") val itemId: String,
    val type: String,
    @ColumnInfo(name = "work_key") val workKey: String,
    @ColumnInfo(name = "series_key") val seriesKey: String? = null,
    @ColumnInfo(name = "series_id") val seriesId: String? = null,
    @ColumnInfo(name = "series_name") val seriesName: String? = null,
    val title: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val genres: List<String> = emptyList(),
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long,
    @ColumnInfo(name = "watched_ms") val watchedMs: Long = 0,
    val spans: String = "",
    @ColumnInfo(name = "start_position_ms") val startPositionMs: Long = 0,
    @ColumnInfo(name = "end_position_ms") val endPositionMs: Long? = null,
    @ColumnInfo(name = "runtime_ms") val runtimeMs: Long? = null,
    val completed: Boolean = false,
    val open: Boolean = true,
) {
    val episodeType: Boolean get() = type == "Episode"
}

/** A wall-clock stretch of real playing, [start] until [end] (exclusive), epoch ms. */
data class WatchSpan(val start: Long, val end: Long) {
    val length: Long get() = end - start
}

/** The stored form of a session's spans: `start+length` pairs in ms, start relative to the session start. */
object SessionSpans {
    fun encode(startedAt: Long, spans: List<WatchSpan>): String =
        spans.joinToString(",") { "${it.start - startedAt}+${it.length}" }

    /** Unreadable or empty parts are skipped. */
    fun decode(startedAt: Long, text: String): List<WatchSpan> = if (text.isBlank()) emptyList() else text.split(',').mapNotNull { part ->
        val plus = part.indexOf('+')
        if (plus <= 0) return@mapNotNull null
        val start = part.substring(0, plus).toLongOrNull() ?: return@mapNotNull null
        val length = part.substring(plus + 1).toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        WatchSpan(startedAt + start, startedAt + start + length)
    }
}

@Dao
interface PlaySessionDao {
    @Insert suspend fun insert(row: PlaySessionRow): Long

    @Query(
        "UPDATE play_session SET ended_at = :endedAt, watched_ms = :watchedMs, spans = :spans, end_position_ms = :endPositionMs, " +
            "runtime_ms = COALESCE(:runtimeMs, runtime_ms), completed = :completed, open = :open WHERE id = :id",
    )
    suspend fun update(id: Long, endedAt: Long, watchedMs: Long, spans: String, endPositionMs: Long?, runtimeMs: Long?, completed: Boolean, open: Boolean)

    /** Sessions a crash or kill left open: they end at their last checkpoint. */
    @Query("UPDATE play_session SET open = 0 WHERE open = 1") suspend fun closeOpen(): Int

    /** Sessions with any part after [since] (a session never runs longer than a day apart from its checkpoints). */
    @Query("SELECT * FROM play_session WHERE ended_at >= :since ORDER BY started_at") fun since(since: Long): Flow<List<PlaySessionRow>>

    /** The most recent session of each work, newest first (最近看过). */
    @Query("SELECT * FROM play_session WHERE id IN (SELECT MAX(id) FROM play_session GROUP BY COALESCE(series_key, work_key)) ORDER BY started_at DESC LIMIT :limit")
    fun latestPerWork(limit: Int): Flow<List<PlaySessionRow>>

    @Query("SELECT MIN(started_at) FROM play_session") fun firstFlow(): Flow<Long?>
}
