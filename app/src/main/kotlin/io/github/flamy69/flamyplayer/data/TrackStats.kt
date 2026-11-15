package io.github.flamy69.flamyplayer.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/** What the app remembers about how a track was listened to. */
data class TrackStat(
    /** How many times the track was started while shuffling. */
    val shufflePlays: Int = 0,
    /** How much of the track was heard the last time it was played, in percent. */
    val lastPercent: Int? = null,
)

/**
 * The listening history of the music tracks, kept in a small database. Tracks are identified
 * by the path of their file, which survives the phone indexing its media again.
 *
 * Everything is also held in memory, so reading never waits for the disk; changes are written
 * in the background, one after the other.
 */
class TrackStats(context: Context) {

    private val database = object : SQLiteOpenHelper(context.applicationContext, "stats.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE (" +
                    "$PATH TEXT NOT NULL PRIMARY KEY, " +
                    "$SHUFFLE_PLAYS INTEGER NOT NULL DEFAULT 0, " +
                    "$LAST_PERCENT INTEGER, " +
                    "$UPDATED_AT INTEGER NOT NULL DEFAULT 0)",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "track-stats").apply { isDaemon = true } }

    private val _stats = MutableStateFlow<Map<String, TrackStat>>(emptyMap())

    /** The history of every track that has one, by the path of its file. */
    val stats: StateFlow<Map<String, TrackStat>> = _stats.asStateFlow()

    init {
        worker.execute { _stats.value = load() }
    }

    fun shufflePlays(path: String): Int = _stats.value[path]?.shufflePlays ?: 0

    /** Notes that the track at [path] was started while shuffling. */
    fun countShufflePlay(path: String) = change(path) { it.copy(shufflePlays = it.shufflePlays + 1) }

    /** Notes how much of the track at [path] was heard, in percent. */
    fun setLastPercent(path: String, percent: Int) = change(path) { it.copy(lastPercent = percent.coerceIn(0, 100)) }

    private fun change(path: String, transform: (TrackStat) -> TrackStat) {
        worker.execute {
            val old = _stats.value[path] ?: TrackStat()
            val new = transform(old)
            if (new == old) return@execute
            _stats.value = _stats.value + (path to new)
            try {
                val values = ContentValues().apply {
                    put(PATH, path)
                    put(SHUFFLE_PLAYS, new.shufflePlays)
                    put(LAST_PERCENT, new.lastPercent)
                    put(UPDATED_AT, System.currentTimeMillis())
                }
                database.writableDatabase.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            } catch (_: SQLiteException) {
                // The history is a nicety; playback goes on without it.
            }
        }
    }

    private fun load(): Map<String, TrackStat> {
        val result = HashMap<String, TrackStat>()
        try {
            database.readableDatabase.query(TABLE, arrayOf(PATH, SHUFFLE_PLAYS, LAST_PERCENT), null, null, null, null, null)
                .use { cursor ->
                    while (cursor.moveToNext()) {
                        result[cursor.getString(0)] = TrackStat(
                            shufflePlays = cursor.getInt(1),
                            lastPercent = if (cursor.isNull(2)) null else cursor.getInt(2),
                        )
                    }
                }
        } catch (_: SQLiteException) {
        }
        return result
    }

    private companion object {
        const val TABLE = "track_stats"
        const val PATH = "path"
        const val SHUFFLE_PLAYS = "shuffle_plays"
        const val LAST_PERCENT = "last_percent"
        const val UPDATED_AT = "updated_at"
    }
}
