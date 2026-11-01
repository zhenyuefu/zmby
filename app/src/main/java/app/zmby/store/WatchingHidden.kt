package app.zmby.store

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 在看 cards taken off on this box (ui-spec §2.18 从在看中移除): [workKey] stays hidden while its
 * newest activity on every server is at or before [hiddenAt] (that activity's own time).
 */
@Entity(tableName = "watching_hidden")
data class WatchingHiddenRow(
    @PrimaryKey @ColumnInfo(name = "work_key") val workKey: String,
    @ColumnInfo(name = "hidden_at") val hiddenAt: Long,
)

@Dao
interface WatchingHiddenDao {
    @Query("SELECT * FROM watching_hidden") fun observe(): Flow<List<WatchingHiddenRow>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(row: WatchingHiddenRow)
    @Query("DELETE FROM watching_hidden WHERE work_key = :key") suspend fun delete(key: String)
}
