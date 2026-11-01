package app.zmby.store

import androidx.room.*
import app.zmby.catalog.CatalogEpisode
import app.zmby.data.ServerItem
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** User-owned local rules. A null itemId denotes a season/range rule; otherwise one concrete file. */
@Entity(tableName = "episode_mapping", indices = [Index(value = ["work_key"]), Index(value = ["server_id"])])
data class EpisodeMappingRule(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "work_key") val workKey: String,
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "series_id") val seriesId: String,
    @ColumnInfo(name = "source_season") val sourceSeason: Int,
    @ColumnInfo(name = "first_episode") val firstEpisode: Int,
    @ColumnInfo(name = "last_episode") val lastEpisode: Int,
    @ColumnInfo(name = "target_season") val targetSeason: Int,
    val offset: Int = 0,
    @ColumnInfo(name = "item_id") val itemId: String? = null,
    @ColumnInfo(name = "target_episode_id") val targetEpisodeId: String? = null,
) {
    fun applies(work: String, ref: ServerItem): Boolean = workKey == work && serverId == ref.server && seriesId == ref.item.seriesId &&
        if (itemId != null) itemId == ref.item.id else sourceSeason == ref.item.parentIndexNumber &&
            ref.item.indexNumber?.let { it in firstEpisode..lastEpisode } == true

    /**
     * Whether saving this rule would collide with [other]: a one-file rule with another rule for the
     * same file, a range rule with an overlapping range of the same server's source season.
     */
    fun overlaps(other: EpisodeMappingRule): Boolean {
        if (other.id == id || other.workKey != workKey || other.serverId != serverId) return false
        if (itemId != null) return other.itemId == itemId
        return other.itemId == null && other.seriesId == seriesId && other.sourceSeason == sourceSeason &&
            other.firstEpisode <= lastEpisode && firstEpisode <= other.lastEpisode
    }

    fun target(catalog: List<CatalogEpisode>, ref: ServerItem): CatalogEpisode? =
        if (itemId != null) catalog.singleOrNull { it.id == targetEpisodeId }
        else catalog.singleOrNull { it.season == targetSeason && it.number?.toLong() == ref.item.indexNumber?.toLong()?.plus(offset) }
}

@Dao
interface EpisodeMappingDao {
    @Query("SELECT * FROM episode_mapping WHERE work_key = :work ORDER BY server_id, series_id, source_season, first_episode, id")
    fun observe(work: String): Flow<List<EpisodeMappingRule>>
    @Query("SELECT * FROM episode_mapping") fun observeAll(): Flow<List<EpisodeMappingRule>>
    @Query("SELECT * FROM episode_mapping WHERE work_key = :work")
    suspend fun rules(work: String): List<EpisodeMappingRule>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(rule: EpisodeMappingRule)
    @Query("DELETE FROM episode_mapping WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM episode_mapping WHERE server_id = :server") suspend fun deleteServer(server: String)
}

class EpisodeMappings(private val db: ZmbyDatabase) {
    private val dao = db.episodeMappings()
    fun observe(work: String) = dao.observe(work)
    fun observeAll() = dao.observeAll()
    suspend fun delete(id: String) = dao.delete(id)
    /**
     * Saves [rule]. An overlapping range is refused unless [replace] (the panel warned 「保存后替换原规则」),
     * in which case the overlapped rules go in the same transaction; a one-file rule always replaces
     * the previous rule for that file.
     */
    suspend fun save(rule: EpisodeMappingRule, replace: Boolean = false) = db.withTransaction {
        require(rule.workKey.isNotBlank() && rule.serverId.isNotBlank() && rule.seriesId.isNotBlank()) { "缺少资源身份" }
        require(rule.sourceSeason >= 0 && rule.targetSeason >= 0 && rule.firstEpisode >= 0 && rule.lastEpisode >= rule.firstEpisode) { "编号范围不正确" }
        val overlapped = dao.rules(rule.workKey).filter(rule::overlaps)
        if (rule.itemId != null) {
            require(!rule.targetEpisodeId.isNullOrBlank()) { "请选择目标单集" }
        } else {
            require(replace || overlapped.isEmpty()) { "范围与已有映射重叠，请先修改或删除原映射" }
        }
        overlapped.forEach { dao.delete(it.id) }
        dao.put(rule)
    }
}
