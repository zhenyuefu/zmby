package app.zmby.store

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.zmby.emby.UserItemData

/**
 * Progress this box reported or marked (local-data spec, 统一数据层 · 观看进度与已看): the newest
 * report per copy, so a restart keeps what was just watched until the servers' own lists catch up
 * (they may answer with an older `LastPlayedDate` for a while). Read through [LocalStore.recentReports]
 * and merged with the servers' answers by time ([app.zmby.data.newestProgress]).
 */
@Entity(tableName = "progress_report", primaryKeys = ["server_id", "item_id"], indices = [Index("played_at")])
data class ProgressReportRow(
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "position_ticks") val positionTicks: Long,
    val played: Boolean,
    @ColumnInfo(name = "played_at") val playedAt: Long,
) {
    val key: String get() = "$serverId:$itemId"

    fun userData(): UserItemData = UserItemData(
        playbackPositionTicks = positionTicks,
        played = played,
        lastPlayedDate = java.time.Instant.ofEpochMilli(playedAt).toString(),
    )
}

@Dao
interface ProgressReportDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(row: ProgressReportRow)

    @Query("SELECT * FROM progress_report ORDER BY played_at DESC LIMIT :limit") suspend fun recent(limit: Int): List<ProgressReportRow>

    /** Keeps the newest [keep] reports; older ones are long since in the servers' own lists. */
    @Query("DELETE FROM progress_report WHERE played_at < (SELECT MIN(played_at) FROM (SELECT played_at FROM progress_report ORDER BY played_at DESC LIMIT :keep))")
    suspend fun prune(keep: Int)
}
