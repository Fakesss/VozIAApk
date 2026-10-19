package com.voicestudio.mobile.data.library

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.voicestudio.mobile.local.LocalAudio
import com.voicestudio.mobile.local.isNoSpace
import com.voicestudio.mobile.network.ClientFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** What a project deletion removed; exported copies outside the app are never touched. */
data class DeletionReport(val items: Int, val bytes: Long, val exportedCopies: Int)

/**
 * The persistent store for projects, generated media, transcripts, task history and
 * audiobook chapters. Media files live under `filesDir/library/<project>/`; the
 * SQLite database holds their metadata, checksums and export locations. Files are
 * written to a sibling `.part` file, synced, and renamed into place before their
 * row is committed, so an interrupted write never leaves a row without its file.
 * Tests may create isolated instances; the app shares one through [get].
 */
class Library(context: Context, databaseName: String? = "library.db", val root: File = File(context.applicationContext.filesDir, "library")) {
    private val app = context.applicationContext
    private val db = LibraryDatabase(app, databaseName)
    val temp: File = File(app.cacheDir, "library-tmp")
    private val revisionFlow = MutableStateFlow(0L)
    /** Increments after every committed change so screens reload their lists. */
    val revision: StateFlow<Long> = revisionFlow.asStateFlow()
    private fun changed() = revisionFlow.update { it + 1 }
    private val write get() = db.writableDatabase
    private val read get() = db.readableDatabase

    fun file(item: LibraryItem): File? = item.file?.let { safeChild(root, it) }
    fun taskDirectory(taskId: String): File = File(root, ".tasks/" + uuid(taskId))

    // Projects -----------------------------------------------------------------
    suspend fun projects(kind: String? = null): List<Project> = io {
        read.query("projects", null, kind?.let { "kind=?" }, kind?.let { arrayOf(it) }, null, null, "updated_at DESC").all { project() }
    }
    suspend fun project(id: String): Project? = io { read.query("projects", null, "id=?", arrayOf(id), null, null, null).all { project() }.firstOrNull() }
    suspend fun createProject(name: String, kind: String, settings: String = "{}"): Project = io {
        val clean = name.trim().take(120).ifEmpty { throw ClientFailure("vozia.project.name_required") }
        val now = System.currentTimeMillis()
        val project = Project(UUID.randomUUID().toString(), clean, kind, now, now, settings)
        write.insertOrThrow("projects", null, ContentValues().apply {
            put("id", project.id); put("name", project.name); put("kind", kind); put("created_at", now); put("updated_at", now); put("settings", settings)
        })
        changed(); project
    }
    /** The default destination for Speak, created on first use and kept forever. */
    suspend fun defaultProject(name: String): Project = io {
        val existing = setting("default_project")?.let { id -> read.query("projects", null, "id=?", arrayOf(id), null, null, null).all { project() }.firstOrNull() }
        existing ?: createProject(name, ProjectKind.GENERAL).also { putSetting("default_project", it.id) }
    }
    suspend fun renameProject(id: String, name: String) = io {
        val clean = name.trim().take(120).ifEmpty { throw ClientFailure("vozia.project.name_required") }
        write.update("projects", ContentValues().apply { put("name", clean); put("updated_at", System.currentTimeMillis()) }, "id=?", arrayOf(id)); changed()
    }
    suspend fun updateProjectSettings(id: String, settings: String) = io {
        write.update("projects", ContentValues().apply { put("settings", settings); put("updated_at", System.currentTimeMillis()) }, "id=?", arrayOf(id)); changed()
    }
    suspend fun deletionPreview(id: String): DeletionReport = io {
        val items = items(id)
        val exports = read.rawQuery("SELECT COUNT(*) FROM exports e JOIN items i ON i.id=e.item_id WHERE i.project_id=?", arrayOf(id)).use { it.moveToFirst(); it.getInt(0) }
        DeletionReport(items.size, items.sumOf { file(it)?.length() ?: 0L }, exports)
    }
    /** Removes the project's private files and rows. Exported copies stay where the user put them. */
    suspend fun deleteProject(id: String): DeletionReport = io {
        val report = deletionPreview(id)
        val active = read.query("tasks", null, "project_id=? AND state IN (?,?)", arrayOf(id, TaskState.QUEUED, TaskState.RUNNING), null, null, null).all { task() }
        if (active.isNotEmpty()) throw ClientFailure("vozia.project.busy")
        write.delete("projects", "id=?", arrayOf(id))
        File(root, uuid(id)).deleteRecursively()
        if (setting("default_project") == id) write.delete("settings", "key=?", arrayOf("default_project"))
        changed(); report
    }
    private fun touch(projectId: String) {
        write.update("projects", ContentValues().apply { put("updated_at", System.currentTimeMillis()) }, "id=?", arrayOf(projectId))
    }

    // Items --------------------------------------------------------------------
    suspend fun items(projectId: String? = null, kinds: Collection<String>? = null, limit: Int? = null): List<LibraryItem> = io {
        val clauses = mutableListOf<String>(); val args = mutableListOf<String>()
        if (projectId != null) { clauses += "project_id=?"; args += projectId }
        if (!kinds.isNullOrEmpty()) { clauses += "kind IN (" + kinds.joinToString(",") { "?" } + ")"; args += kinds }
        read.query("items", null, clauses.joinToString(" AND ").ifEmpty { null }, args.toTypedArray().ifEmpty { null }, null, null,
            "position ASC, created_at DESC", limit?.toString()).all { item() }
    }
    suspend fun item(id: String): LibraryItem? = io { read.query("items", null, "id=?", arrayOf(id), null, null, null).all { item() }.firstOrNull() }

    /** Writes a synthetic-speech WAV (RIFF INFO provenance marker) and registers it. */
    suspend fun addAudio(projectId: String, kind: String, title: String, text: String, audio: LocalAudio, engine: String?, voice: String?,
                         language: String?, parentId: String? = null, position: Int = 0, meta: String = "{}"): LibraryItem = io {
        require(audio.samples.isNotEmpty() && audio.sampleRate > 0)
        val pending = File(temp.apply { mkdirs() }, UUID.randomUUID().toString() + ".wav")
        try {
            WaveWriter(pending, audio.sampleRate).use { it.write(audio.samples) }
            addFile(projectId, kind, title, text, pending, "audio/wav", "wav", engine, voice, language, parentId, position, meta,
                durationMs = audio.samples.size * 1000L / audio.sampleRate)
        } finally { pending.delete() }
    }
    /** Moves [source] into the project folder, verifies its checksum and commits the row. */
    suspend fun addFile(projectId: String, kind: String, title: String, text: String, source: File, mime: String, extension: String,
                        engine: String? = null, voice: String? = null, language: String? = null, parentId: String? = null,
                        position: Int = 0, meta: String = "{}", durationMs: Long = 0): LibraryItem = io {
        require(source.isFile && source.length() > 0 && extension.matches(Regex("[a-z0-9]{1,8}")))
        val id = UUID.randomUUID().toString()
        val relative = uuid(projectId) + "/" + id + "." + extension
        val target = File(root, relative)
        val part = File(target.path + ".part")
        try {
            target.parentFile!!.mkdirs()
            if (target.parentFile!!.usableSpace < source.length() + RESERVE) throw ClientFailure("mobile.local.storage_required")
            copySynced(source, part)
            val sha = sha256(part)
            if (sha != sha256(source)) throw ClientFailure("vozia.storage.verify_failed")
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            val item = LibraryItem(id, projectId, kind, title.trim().take(200).ifEmpty { "Audio" }, text, relative, mime, target.length(),
                durationMs, sha, engine, voice, language, parentId, position, System.currentTimeMillis(), meta)
            write.beginTransaction()
            try { write.insertOrThrow("items", null, item.values()); touch(projectId); write.setTransactionSuccessful() } finally { write.endTransaction() }
            changed(); item
        } catch (e: Exception) {
            part.delete(); target.delete()
            throw if (e is ClientFailure) e else if (isNoSpace(e)) ClientFailure("mobile.local.storage_required") else e
        }
    }
    suspend fun addText(projectId: String, kind: String, title: String, text: String, content: String, mime: String, extension: String,
                        language: String? = null, parentId: String? = null, engine: String? = null, meta: String = "{}"): LibraryItem = io {
        val pending = File(temp.apply { mkdirs() }, UUID.randomUUID().toString() + "." + extension)
        try {
            FileOutputStream(pending).use { it.write(content.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            addFile(projectId, kind, title, text, pending, mime, extension, engine = engine, language = language, parentId = parentId, meta = meta)
        } finally { pending.delete() }
    }
    suspend fun renameItem(id: String, title: String) = io {
        val clean = title.trim().take(200).ifEmpty { throw ClientFailure("vozia.project.name_required") }
        write.update("items", ContentValues().apply { put("title", clean) }, "id=?", arrayOf(id)); changed()
    }
    suspend fun deleteItem(id: String) = io {
        val item = read.query("items", null, "id=?", arrayOf(id), null, null, null).all { item() }.firstOrNull() ?: return@io
        write.delete("items", "id=?", arrayOf(id))
        write.update("chapters", ContentValues().apply { putNull("item_id"); put("state", "pending") }, "item_id=?", arrayOf(id))
        file(item)?.delete(); changed()
    }
    /** True when the private file exists with the size and SHA-256 recorded at creation. */
    suspend fun verify(item: LibraryItem): Boolean = io {
        val file = file(item) ?: return@io false
        file.isFile && file.length() == item.size && (item.sha256 == null || sha256(file) == item.sha256)
    }

    // Exports ------------------------------------------------------------------
    suspend fun recordExport(itemId: String, uri: String, location: String, name: String, size: Long) = io {
        write.insertOrThrow("exports", null, ContentValues().apply {
            put("item_id", itemId); put("uri", uri); put("location", location); put("name", name); put("size", size); put("exported_at", System.currentTimeMillis())
        }); changed()
    }
    suspend fun exports(itemId: String? = null): List<ExportRecord> = io {
        read.query("exports", null, itemId?.let { "item_id=?" }, itemId?.let { arrayOf(it) }, null, null, "exported_at DESC").all { export() }
    }

    // Tasks --------------------------------------------------------------------
    suspend fun createTask(kind: String, projectId: String?, title: String, input: String): TaskRecord = io {
        val now = System.currentTimeMillis()
        val task = TaskRecord(UUID.randomUUID().toString(), kind, projectId, title.take(200), TaskState.QUEUED, 0f, "vozia.task.queued", null, null, input, null, now, now)
        write.insertOrThrow("tasks", null, ContentValues().apply {
            put("id", task.id); put("kind", kind); put("project_id", projectId); put("title", task.title); put("state", task.state); put("progress", 0f)
            put("stage", task.stage); put("input", input); put("created_at", now); put("updated_at", now)
        }); changed(); task
    }
    suspend fun task(id: String): TaskRecord? = io { read.query("tasks", null, "id=?", arrayOf(id), null, null, null).all { task() }.firstOrNull() }
    suspend fun tasks(projectId: String? = null, limit: Int = 200): List<TaskRecord> = io {
        read.query("tasks", null, projectId?.let { "project_id=?" }, projectId?.let { arrayOf(it) }, null, null, "created_at DESC", limit.toString()).all { task() }
    }
    suspend fun updateTask(id: String, state: String? = null, progress: Float? = null, stage: String? = null, error: String? = null,
                           errorParams: String? = null, resultItem: String? = null, clearError: Boolean = false) = io {
        val values = ContentValues().apply {
            state?.let { put("state", it) }; progress?.let { put("progress", it.coerceIn(0f, 1f)) }; stage?.let { put("stage", it) }
            if (clearError) { putNull("error"); putNull("error_params") }
            error?.let { put("error", it) }; errorParams?.let { put("error_params", it) }; resultItem?.let { put("result_item", it) }
            put("updated_at", System.currentTimeMillis())
        }
        write.update("tasks", values, "id=?", arrayOf(id)); changed()
    }
    suspend fun deleteTask(id: String) = io {
        val task = read.query("tasks", null, "id=?", arrayOf(id), null, null, null).all { task() }.firstOrNull() ?: return@io
        if (task.active) throw ClientFailure("vozia.task.still_running")
        write.delete("tasks", "id=?", arrayOf(id)); taskDirectory(id).deleteRecursively(); changed()
    }

    // Chapters -----------------------------------------------------------------
    suspend fun chapters(projectId: String): List<Chapter> = io {
        read.query("chapters", null, "project_id=?", arrayOf(projectId), null, null, "position ASC").all { chapter() }
    }
    suspend fun replaceChapters(projectId: String, chapters: List<Pair<String, String>>) = io {
        write.beginTransaction()
        try {
            write.delete("chapters", "project_id=? AND item_id IS NULL", arrayOf(projectId))
            val kept = read.query("chapters", null, "project_id=?", arrayOf(projectId), null, null, "position ASC").all { chapter() }
            if (kept.isNotEmpty()) throw ClientFailure("vozia.audiobook.chapters_generated")
            chapters.forEachIndexed { index, (title, text) ->
                write.insertOrThrow("chapters", null, ContentValues().apply {
                    put("id", UUID.randomUUID().toString()); put("project_id", projectId); put("position", index); put("title", title.take(200)); put("text", text); put("state", "pending")
                })
            }
            touch(projectId); write.setTransactionSuccessful()
        } finally { write.endTransaction() }
        changed()
    }
    suspend fun updateChapter(id: String, title: String? = null, text: String? = null, itemId: String? = null, state: String? = null) = io {
        val values = ContentValues().apply { title?.let { put("title", it.take(200)) }; text?.let { put("text", it) }; itemId?.let { put("item_id", it) }; state?.let { put("state", it) } }
        if (values.size() > 0) { write.update("chapters", values, "id=?", arrayOf(id)); changed() }
    }
    /** Clears generated audio links so the next generation recreates every chapter. */
    suspend fun resetChapters(projectId: String) = io {
        val generated = read.query("chapters", null, "project_id=? AND item_id IS NOT NULL", arrayOf(projectId), null, null, null).all { chapter() }
        generated.forEach { chapter -> chapter.itemId?.let { id -> read.query("items", null, "id=?", arrayOf(id), null, null, null).all { item() }.firstOrNull()?.let { file(it)?.delete() }; write.delete("items", "id=?", arrayOf(id)) } }
        write.update("chapters", ContentValues().apply { putNull("item_id"); put("state", "pending") }, "project_id=?", arrayOf(projectId)); changed()
    }

    // Settings and maintenance -------------------------------------------------
    suspend fun setting(key: String): String? = io { read.query("settings", arrayOf("value"), "key=?", arrayOf(key), null, null, null).use { if (it.moveToFirst()) it.getString(0) else null } }
    suspend fun putSetting(key: String, value: String?) = io {
        if (value == null) write.delete("settings", "key=?", arrayOf(key))
        else write.insertWithOnConflict("settings", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
        changed()
    }
    suspend fun usedBytes(): Long = io { root.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
    /**
     * Startup recovery: removes interrupted `.part` writes and temporary files, and
     * returns tasks left "running" by a killed process to "queued" for WorkManager.
     */
    suspend fun recover(): Int = io {
        var removed = 0
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".part") }.forEach { if (it.delete()) removed++ }
        temp.listFiles().orEmpty().filter { System.currentTimeMillis() - it.lastModified() > 60 * 60 * 1000L }.forEach { if (it.deleteRecursively()) removed++ }
        write.update("tasks", ContentValues().apply { put("state", TaskState.QUEUED); put("stage", "vozia.task.resuming") }, "state=?", arrayOf(TaskState.RUNNING))
        changed(); removed
    }
    /** Deletes temporary files and finished-task scratch folders; never project media. */
    suspend fun cleanTemporary(): Long = io {
        var bytes = 0L
        temp.listFiles().orEmpty().forEach { bytes += it.walkTopDown().filter { f -> f.isFile }.sumOf { f -> f.length() }; it.deleteRecursively() }
        val tasks = File(root, ".tasks")
        val activeIds = read.query("tasks", arrayOf("id"), "state IN (?,?)", arrayOf(TaskState.QUEUED, TaskState.RUNNING), null, null, null).all { string("id") }.toSet()
        tasks.listFiles().orEmpty().filter { it.name !in activeIds }.forEach { bytes += it.walkTopDown().filter { f -> f.isFile }.sumOf { f -> f.length() }; it.deleteRecursively() }
        changed(); bytes
    }
    fun close() = db.close()

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        private const val RESERVE = 8L * 1024 * 1024
        @Volatile private var instance: Library? = null
        fun get(context: Context): Library = instance ?: synchronized(this) { instance ?: Library(context.applicationContext).also { instance = it } }
        internal fun uuid(id: String): String { require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)); return id }
        internal fun safeChild(root: File, relative: String): File {
            val file = File(root, relative).canonicalFile
            require(file.path.startsWith(root.canonicalPath + File.separator))
            return file
        }
        fun sha256(file: File): String = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256"); val bytes = ByteArray(1 shl 16)
            while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        internal fun copySynced(source: File, target: File) {
            source.inputStream().use { input -> FileOutputStream(target).use { output -> input.copyTo(output, 1 shl 16); output.fd.sync() } }
        }
    }
}
