package com.voicestudio.mobile.data.library

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Project kinds group the screens that create them; "general" holds Speak output. */
object ProjectKind { const val GENERAL = "general"; const val AUDIOBOOK = "audiobook"; const val TRANSCRIPTION = "transcription"; const val DUB = "dub" }
/** Media and text kept by a project. Large media lives in files; rows hold metadata only. */
object ItemKind {
    const val SPEECH = "speech"; const val CHAPTER = "chapter"; const val AUDIOBOOK = "audiobook"; const val RECORDING = "recording"
    const val IMPORT = "import"; const val TRANSCRIPT = "transcript"; const val SUBTITLE = "subtitle"; const val DUB = "dub"
}
/** Task lifecycle shown to people: waiting, processing, done, cancelled, failed. */
object TaskState {
    const val QUEUED = "queued"; const val RUNNING = "running"; const val SUCCEEDED = "succeeded"; const val CANCELLED = "cancelled"; const val FAILED = "failed"
    val active = setOf(QUEUED, RUNNING)
}

data class Project(val id: String, val name: String, val kind: String, val createdAt: Long, val updatedAt: Long, val settings: String = "{}")
data class LibraryItem(val id: String, val projectId: String, val kind: String, val title: String, val text: String, val file: String?, val mime: String?,
    val size: Long, val durationMs: Long, val sha256: String?, val engine: String?, val voice: String?, val language: String?,
    val parentId: String?, val position: Int, val createdAt: Long, val meta: String = "{}")
data class ExportRecord(val id: Long, val itemId: String, val uri: String, val location: String, val name: String, val size: Long, val exportedAt: Long)
data class TaskRecord(val id: String, val kind: String, val projectId: String?, val title: String, val state: String, val progress: Float, val stage: String,
    val error: String?, val errorParams: String?, val input: String, val resultItem: String?, val createdAt: Long, val updatedAt: Long) {
    val active: Boolean get() = state in TaskState.active
}
data class Chapter(val id: String, val projectId: String, val position: Int, val title: String, val text: String, val itemId: String?, val state: String)

/**
 * The framework SQLite database: no extra dependency, WAL journaling, foreign keys,
 * and explicit migrations in [onUpgrade] so app updates keep every row.
 */
internal class LibraryDatabase(context: Context, name: String? = "library.db") : SQLiteOpenHelper(context, name, null, VERSION) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true); if (!db.isReadOnly) db.enableWriteAheadLogging() }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE projects(id TEXT PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL, created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL, settings TEXT NOT NULL DEFAULT '{}')""")
        db.execSQL("""CREATE TABLE items(id TEXT PRIMARY KEY, project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE, kind TEXT NOT NULL,
            title TEXT NOT NULL, text TEXT NOT NULL DEFAULT '', file TEXT, mime TEXT, size INTEGER NOT NULL DEFAULT 0, duration_ms INTEGER NOT NULL DEFAULT 0,
            sha256 TEXT, engine TEXT, voice TEXT, language TEXT, parent_id TEXT, position INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL,
            meta TEXT NOT NULL DEFAULT '{}')""")
        db.execSQL("CREATE INDEX items_project ON items(project_id, created_at)")
        db.execSQL("""CREATE TABLE exports(id INTEGER PRIMARY KEY AUTOINCREMENT, item_id TEXT NOT NULL REFERENCES items(id) ON DELETE CASCADE,
            uri TEXT NOT NULL, location TEXT NOT NULL, name TEXT NOT NULL, size INTEGER NOT NULL, exported_at INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE tasks(id TEXT PRIMARY KEY, kind TEXT NOT NULL, project_id TEXT REFERENCES projects(id) ON DELETE CASCADE, title TEXT NOT NULL,
            state TEXT NOT NULL, progress REAL NOT NULL DEFAULT 0, stage TEXT NOT NULL DEFAULT '', error TEXT, error_params TEXT, input TEXT NOT NULL,
            result_item TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE chapters(id TEXT PRIMARY KEY, project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE, position INTEGER NOT NULL,
            title TEXT NOT NULL, text TEXT NOT NULL, item_id TEXT, state TEXT NOT NULL DEFAULT 'pending')""")
        db.execSQL("CREATE INDEX chapters_project ON chapters(project_id, position)")
        db.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first schema. Add each migration as `if (oldVersion < N) {...}`;
        // never drop tables, so updating the app keeps projects and history.
    }
    companion object { const val VERSION = 1 }
}

internal fun Cursor.string(name: String): String = getString(getColumnIndexOrThrow(name))
internal fun Cursor.nullable(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
internal fun Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
internal fun Cursor.int(name: String): Int = getInt(getColumnIndexOrThrow(name))
internal fun Cursor.float(name: String): Float = getFloat(getColumnIndexOrThrow(name))
internal fun <T> Cursor.all(read: Cursor.() -> T): List<T> = use { buildList { while (moveToNext()) add(read()) } }

internal fun Cursor.project() = Project(string("id"), string("name"), string("kind"), long("created_at"), long("updated_at"), string("settings"))
internal fun Cursor.item() = LibraryItem(string("id"), string("project_id"), string("kind"), string("title"), string("text"), nullable("file"), nullable("mime"),
    long("size"), long("duration_ms"), nullable("sha256"), nullable("engine"), nullable("voice"), nullable("language"), nullable("parent_id"), int("position"),
    long("created_at"), string("meta"))
internal fun Cursor.export() = ExportRecord(long("id"), string("item_id"), string("uri"), string("location"), string("name"), long("size"), long("exported_at"))
internal fun Cursor.task() = TaskRecord(string("id"), string("kind"), nullable("project_id"), string("title"), string("state"), float("progress"), string("stage"),
    nullable("error"), nullable("error_params"), string("input"), nullable("result_item"), long("created_at"), long("updated_at"))
internal fun Cursor.chapter() = Chapter(string("id"), string("project_id"), int("position"), string("title"), string("text"), nullable("item_id"), string("state"))

internal fun LibraryItem.values() = ContentValues().apply {
    put("id", id); put("project_id", projectId); put("kind", kind); put("title", title); put("text", text); put("file", file); put("mime", mime)
    put("size", size); put("duration_ms", durationMs); put("sha256", sha256); put("engine", engine); put("voice", voice); put("language", language)
    put("parent_id", parentId); put("position", position); put("created_at", createdAt); put("meta", meta)
}
