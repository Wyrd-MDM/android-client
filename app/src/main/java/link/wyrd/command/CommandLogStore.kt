package link.wyrd.command

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class CommandLogStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    data class Entry(
        val id: String,
        val phase: Phase,
        val ok: Boolean?,
        val message: String?,
    )

    enum class Phase { started, done }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                id          TEXT PRIMARY KEY,
                phase       TEXT NOT NULL,
                ok          INTEGER,
                message     TEXT,
                received_at INTEGER NOT NULL,
                updated_at  INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_${TABLE}_received ON $TABLE(received_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun recordOrGet(id: String, now: Long = System.currentTimeMillis()): Entry? {
        val existing = get(id)
        if (existing != null) return existing
        writableDatabase.insertWithOnConflict(
            TABLE,
            null,
            ContentValues().apply {
                put("id", id)
                put("phase", Phase.started.name)
                putNull("ok")
                putNull("message")
                put("received_at", now)
                put("updated_at", now)
            },
            SQLiteDatabase.CONFLICT_IGNORE
        )
        prune(now)
        return null
    }

    fun markDone(id: String, ok: Boolean, message: String?, now: Long = System.currentTimeMillis()) {
        writableDatabase.update(
            TABLE,
            ContentValues().apply {
                put("phase", Phase.done.name)
                put("ok", if (ok) 1 else 0)
                if (message == null) putNull("message") else put("message", message)
                put("updated_at", now)
            },
            "id = ?",
            arrayOf(id)
        )
    }

    fun get(id: String): Entry? {
        readableDatabase.query(
            TABLE,
            arrayOf("id", "phase", "ok", "message"),
            "id = ?",
            arrayOf(id),
            null, null, null
        ).use { c ->
            if (!c.moveToFirst()) return null
            val okIdx = c.getColumnIndexOrThrow("ok")
            val msgIdx = c.getColumnIndexOrThrow("message")
            return Entry(
                id = c.getString(0),
                phase = Phase.valueOf(c.getString(1)),
                ok = if (c.isNull(okIdx)) null else c.getInt(okIdx) != 0,
                message = if (c.isNull(msgIdx)) null else c.getString(msgIdx),
            )
        }
    }

    fun prune(now: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        db.execSQL(
            "DELETE FROM $TABLE WHERE received_at < ?",
            arrayOf(now - MAX_AGE_MS)
        )
        db.execSQL(
            """
            DELETE FROM $TABLE WHERE id IN (
                SELECT id FROM $TABLE ORDER BY received_at DESC LIMIT -1 OFFSET $MAX_ROWS
            )
            """.trimIndent()
        )
    }

    companion object {
        private const val DB_NAME = "wyrd_command_log.db"
        private const val DB_VERSION = 1
        private const val TABLE = "command_log"
        private const val MAX_ROWS = 5000
        private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

        @Volatile
        private var instance: CommandLogStore? = null

        fun get(context: Context): CommandLogStore {
            return instance ?: synchronized(this) {
                instance ?: CommandLogStore(context.applicationContext).also { instance = it }
            }
        }
    }
}
