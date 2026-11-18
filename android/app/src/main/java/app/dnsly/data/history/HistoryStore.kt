package app.dnsly.data.history

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Persistent history (ARCHITECTURE §4, Room). [ensure] is idempotent and cheap; call it before
 * [events]. Writes are fire-and-forget on an IO scope: a lost entry is better than blocking
 * the engine thread.
 */
object HistoryStore {
    private const val RETENTION_MS = 90L * 24 * 60 * 60 * 1000
    private const val MAX_ENTRIES = 500
    /** The retention delete runs once every this many inserts, not on each one. */
    private const val PURGE_EVERY = 25

    private var db: AppDatabase? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val insertsSincePurge = AtomicInteger(0)

    @Synchronized
    fun ensure(context: Context) {
        if (db == null) {
            db = Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "dnsly.db",
            ).build()
        }
    }

    /** Newest first. Empty flow if [ensure] has not run yet. */
    fun events(): Flow<List<HistoryEntry>> = db?.history()?.recent(MAX_ENTRIES) ?: flowOf(emptyList())

    fun record(kind: String, detail: String) {
        val d = db ?: return
        scope.launch {
            val dao = d.history()
            dao.insert(HistoryEntry(timestampMs = System.currentTimeMillis(), kind = kind, detail = detail))
            if (insertsSincePurge.incrementAndGet() >= PURGE_EVERY) {
                insertsSincePurge.set(0)
                dao.deleteOlderThan(System.currentTimeMillis() - RETENTION_MS)
            }
        }
    }
}
