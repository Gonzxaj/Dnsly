package app.dnsly.data.history

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** One user-visible event, persisted so the history survives process and device restarts. */
@Entity(tableName = "history")
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    /** One of [HistoryKind] values. */
    val kind: String,
    val detail: String,
)

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(entry: HistoryEntry)

    @Query("SELECT * FROM history ORDER BY timestampMs DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<HistoryEntry>>

    @Query("DELETE FROM history WHERE timestampMs < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long)
}

@Database(entities = [HistoryEntry::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun history(): HistoryDao
}
