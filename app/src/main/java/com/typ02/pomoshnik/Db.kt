package com.typ02.pomoshnik

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Задача. repeat: none, daily, weekdays, weekends, weekly, monthly, days (days = битовая маска пн..вс). */
data class Task(
    var id: Long = 0,
    var title: String = "",
    var dueAt: Long? = null,
    var repeat: String = "none",
    var days: Int = 0,
    var done: Boolean = false,
    var createdAt: Long = System.currentTimeMillis(),
    var completedAt: Long? = null,
    var snoozedUntil: Long? = null,
    var firedAt: Long? = null,
)

/** Событие для обучения. type: created, fired, done, snooze, missed, adjust. */
data class Ev(
    val taskId: Long,
    val key: String,
    val title: String,
    val type: String,
    val at: Long,
    val dueAt: Long?,
    val extra: Long,
)

/** Те, що помічник пам'ятає: kind = fact (a = текст) або rule (a = фраза, b = що вона означає). */
class Fact(val id: Long, val kind: String, val a: String, val b: String, val at: Long)

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "pomoshnik.db", null, 2) {

    companion object {
        @Volatile
        private var inst: Db? = null

        fun get(ctx: Context): Db =
            inst ?: synchronized(this) { inst ?: Db(ctx.applicationContext).also { inst = it } }

        private const val COLS =
            "id,title,due_at,repeat,days,done,created_at,completed_at,snoozed_until,fired_at"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE tasks(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, " +
                "due_at INTEGER, repeat TEXT NOT NULL DEFAULT 'none', days INTEGER NOT NULL DEFAULT 0, " +
                "done INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, completed_at INTEGER, " +
                "snoozed_until INTEGER, fired_at INTEGER)"
        )
        db.execSQL(
            "CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, task_id INTEGER, key TEXT, " +
                "title TEXT, type TEXT, at INTEGER, due_at INTEGER, extra INTEGER)"
        )
        db.execSQL("CREATE INDEX ev_at ON events(at)")
        createFacts(db)
    }

    private fun createFacts(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS facts(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, " +
                "a TEXT NOT NULL, b TEXT NOT NULL, at INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createFacts(db)
    }

    fun addFact(kind: String, a: String, b: String): Long {
        val v = ContentValues()
        v.put("kind", kind)
        v.put("a", a)
        v.put("b", b)
        v.put("at", System.currentTimeMillis())
        return writableDatabase.insert("facts", null, v)
    }

    fun facts(kind: String): List<Fact> {
        val out = ArrayList<Fact>()
        val c = readableDatabase.rawQuery("SELECT id,kind,a,b,at FROM facts WHERE kind=? ORDER BY id", arrayOf(kind))
        try {
            while (c.moveToNext()) out.add(Fact(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4)))
        } finally {
            c.close()
        }
        return out
    }

    fun deleteFact(id: Long) {
        writableDatabase.delete("facts", "id=?", arrayOf(id.toString()))
    }

    private fun lng(c: Cursor, i: Int): Long? = if (c.isNull(i)) null else c.getLong(i)

    private fun readTask(c: Cursor): Task = Task(
        id = c.getLong(0),
        title = c.getString(1),
        dueAt = lng(c, 2),
        repeat = c.getString(3),
        days = c.getInt(4),
        done = c.getInt(5) == 1,
        createdAt = c.getLong(6),
        completedAt = lng(c, 7),
        snoozedUntil = lng(c, 8),
        firedAt = lng(c, 9),
    )

    private fun tasks(where: String, args: Array<String>?, order: String): List<Task> {
        val out = ArrayList<Task>()
        val sql = "SELECT $COLS FROM tasks WHERE $where ORDER BY $order"
        val c = readableDatabase.rawQuery(sql, args)
        try {
            while (c.moveToNext()) out.add(readTask(c))
        } finally {
            c.close()
        }
        return out
    }

    /** Невыполненные задачи: сначала со сроком (по возрастанию), потом без срока. */
    fun active(): List<Task> =
        tasks("done=0", null, "CASE WHEN due_at IS NULL THEN 1 ELSE 0 END, due_at, id")

    fun task(id: Long): Task? = tasks("id=?", arrayOf(id.toString()), "id").firstOrNull()

    fun save(t: Task): Long {
        val v = ContentValues()
        v.put("title", t.title)
        if (t.dueAt == null) v.putNull("due_at") else v.put("due_at", t.dueAt!!)
        v.put("repeat", t.repeat)
        v.put("days", t.days)
        v.put("done", if (t.done) 1 else 0)
        v.put("created_at", t.createdAt)
        if (t.completedAt == null) v.putNull("completed_at") else v.put("completed_at", t.completedAt!!)
        if (t.snoozedUntil == null) v.putNull("snoozed_until") else v.put("snoozed_until", t.snoozedUntil!!)
        if (t.firedAt == null) v.putNull("fired_at") else v.put("fired_at", t.firedAt!!)
        if (t.id == 0L) {
            t.id = writableDatabase.insert("tasks", null, v)
        } else {
            writableDatabase.update("tasks", v, "id=?", arrayOf(t.id.toString()))
        }
        return t.id
    }

    fun delete(id: Long) {
        writableDatabase.delete("tasks", "id=?", arrayOf(id.toString()))
    }

    fun log(e: Ev) {
        val v = ContentValues()
        v.put("task_id", e.taskId)
        v.put("key", e.key)
        v.put("title", e.title)
        v.put("type", e.type)
        v.put("at", e.at)
        if (e.dueAt == null) v.putNull("due_at") else v.put("due_at", e.dueAt)
        v.put("extra", e.extra)
        writableDatabase.insert("events", null, v)
    }

    fun events(since: Long, type: String? = null): List<Ev> {
        val out = ArrayList<Ev>()
        val sql = "SELECT task_id,key,title,type,at,due_at,extra FROM events WHERE at>=?" +
            (if (type != null) " AND type=?" else "") + " ORDER BY at"
        val args = if (type != null) arrayOf(since.toString(), type) else arrayOf(since.toString())
        val c = readableDatabase.rawQuery(sql, args)
        try {
            while (c.moveToNext()) {
                out.add(
                    Ev(
                        taskId = c.getLong(0),
                        key = c.getString(1) ?: "",
                        title = c.getString(2) ?: "",
                        type = c.getString(3) ?: "",
                        at = c.getLong(4),
                        dueAt = lng(c, 5),
                        extra = if (c.isNull(6)) 0L else c.getLong(6),
                    )
                )
            }
        } finally {
            c.close()
        }
        return out
    }
}
