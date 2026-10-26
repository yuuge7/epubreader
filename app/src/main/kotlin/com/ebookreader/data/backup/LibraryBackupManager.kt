package com.ebookreader.data.backup

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.ebookreader.BuildConfig
import com.ebookreader.data.epub.ChapterLayoutMigrator
import com.ebookreader.data.library.hex
import com.ebookreader.data.local.dao.BookDao
import com.ebookreader.data.local.dao.BookmarkDao
import com.ebookreader.data.local.dao.CollectionDao
import com.ebookreader.data.local.entity.BookCollectionEntity
import com.ebookreader.data.local.entity.BookEntity
import com.ebookreader.data.local.entity.BookmarkEntity
import com.ebookreader.data.local.entity.CollectionEntity
import com.ebookreader.di.ApplicationScope
import com.ebookreader.domain.model.ChapterLayout
import com.ebookreader.domain.model.StatsImportMode
import com.ebookreader.domain.model.bookMatchKey
import com.ebookreader.domain.repository.BookTextSearch
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The whole library in one file, to move to a new phone or keep somewhere safe: the books
 * themselves and their covers, where each one was left, bookmarks, highlights and notes,
 * collections, the reading history and the app's settings.
 *
 * A zip: `library.json` (books, bookmarks, collections, settings), `stats.json` (the same
 * file a stats export writes: history, places, highlights), then each book's file and cover.
 *
 * Restoring adds to what is there rather than replacing it. A book the device already has
 * (the same file, or the same title and author) is not copied again; its place is taken
 * from the backup only when the backup read it more recently.
 */
@Singleton
class LibraryBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookDao: BookDao,
    private val bookmarkDao: BookmarkDao,
    private val collectionDao: CollectionDao,
    private val statsBackupManager: StatsBackupManager,
    private val dataStore: DataStore<Preferences>,
    private val layouts: ChapterLayoutMigrator,
    private val textSearch: BookTextSearch,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    data class Outcome(val booksAdded: Int, val booksMatched: Int, val bookmarksAdded: Int, val sessionsAdded: Int) {
        val message: String
            get() = buildList {
                add(if (booksAdded == 1) "1 book restored" else "$booksAdded books restored")
                if (booksMatched > 0) add("$booksMatched already here")
                if (bookmarksAdded > 0) add("$bookmarksAdded bookmarks")
                if (sessionsAdded > 0) add("$sessionsAdded sittings")
            }.joinToString(", ")
    }

    class InvalidBackupException(message: String) : Exception(message)

    /**
     * Writes the backup to [target]. [includeBooks] false leaves the book files out, for a
     * small file that restores everything onto a device that already has the books.
     *
     * @param onProgress 0..1, by books written.
     */
    suspend fun export(target: Uri, includeBooks: Boolean, onProgress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
        layouts.migrateAll()
        val books = bookDao.getAllBooksOnce()
        val out = context.contentResolver.openOutputStream(target, "wt")
            ?: throw IllegalStateException("Could not open the selected file for writing.")
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putJson(LIBRARY_ENTRY, libraryJson(books, includeBooks))
            zip.putNextEntry(ZipEntry(STATS_ENTRY))
            zip.write(StatsBackupCodec.encode(statsBackupManager.export()).toByteArray())
            zip.closeEntry()
            books.forEachIndexed { i, book ->
                currentCoroutineContext().ensureActive()
                if (includeBooks) File(book.filePath).takeIf { it.isFile }?.let { zip.putFile(bookEntry(i, it.name), it) }
                book.coverPath?.let(::File)?.takeIf { it.isFile }?.let { zip.putFile(coverEntry(i, it.extension), it) }
                onProgress((i + 1f) / books.size.coerceAtLeast(1))
            }
        }
    }

    private suspend fun libraryJson(books: List<BookEntity>, includeBooks: Boolean): JSONObject {
        val indexById = books.withIndex().associate { (i, b) -> b.id to i }
        val root = JSONObject()
            .put("schema", SCHEMA)
            .put("version", VERSION)
            .put("exportedAt", Date().time)
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("includesBooks", includeBooks)
        root.put("books", JSONArray().apply {
            books.forEach { b ->
                put(
                    JSONObject()
                        .put("title", b.title)
                        .put("author", b.author)
                        .put("format", b.format)
                        .put("sourceFormat", b.sourceFormat)
                        .put("fileName", File(b.filePath).name)
                        .put("contentHash", b.contentHash)
                        .put("totalPages", b.totalPages)
                        .put("series", b.series)
                        .put("seriesIndex", b.seriesIndex?.toDouble())
                        .put("description", b.description)
                        .put("readingSettings", b.readingSettings)
                        .put("dateAdded", b.dateAdded)
                        .put("finishedAt", b.finishedAt)
                        .put("chapterLayout", b.chapterLayout)
                        .put("hasCover", b.coverPath != null)
                )
            }
        })
        root.put("bookmarks", JSONArray().apply {
            bookmarkDao.getAllOnce().forEach { m ->
                val book = indexById[m.bookId] ?: return@forEach
                put(
                    JSONObject()
                        .put("book", book)
                        .put("page", m.page)
                        .put("chapterTitle", m.chapterTitle)
                        .put("note", m.note)
                        .put("dateCreated", m.dateCreated)
                        .put("scrollFraction", m.scrollFraction.toDouble())
                        .put("textOffset", m.textOffset)
                        .put("excerpt", m.excerpt)
                )
            }
        })
        val links = collectionDao.getLinksOnce().groupBy({ it.collectionId }, { it.bookId })
        root.put("collections", JSONArray().apply {
            collectionDao.getAllOnce().forEach { c ->
                put(
                    JSONObject()
                        .put("name", c.name)
                        .put("createdAt", c.createdAt)
                        .put("books", JSONArray(links[c.id].orEmpty().mapNotNull { indexById[it] }))
                )
            }
        })
        root.put("settings", settingsJson(dataStore.data.first()))
        return root
    }

    /** Restores [source], adding to the library on this device. */
    suspend fun import(source: Uri, onProgress: (Float) -> Unit = {}): Outcome = withContext(Dispatchers.IO) {
        layouts.migrateAll()
        val input = context.contentResolver.openInputStream(source)
            ?: throw IllegalStateException("Could not open the selected file.")
        ZipInputStream(input.buffered()).use { zip -> restore(zip, onProgress) }
    }

    private suspend fun restore(zip: ZipInputStream, onProgress: (Float) -> Unit): Outcome {
        var library: JSONObject? = null
        var stats: String? = null
        /** Backup index -> the book's id here, once known. */
        val localIds = HashMap<Int, Long>()
        /** Backup indices of books this restore added, which take their cover from the file. */
        val added = HashSet<Int>()
        var matched = 0
        var books: List<JSONObject> = emptyList()

        val existing = bookDao.getAllBooksOnce()
        val byHash = existing.filter { it.contentHash != null }.associateBy { it.contentHash!! }
        val byKey = existing.associateBy { bookMatchKey(it.title, it.author) }

        fun localMatch(b: JSONObject): BookEntity? =
            b.optStringOrNull("contentHash")?.let(byHash::get) ?: byKey[bookMatchKey(b.optString("title"), b.optString("author"))]

        while (true) {
            currentCoroutineContext().ensureActive()
            val entry = zip.nextEntry ?: break
            val name = entry.name
            when {
                name == LIBRARY_ENTRY -> {
                    val root = runCatching { JSONObject(zip.readBytes().decodeToString()) }.getOrNull()
                    if (root?.optString("schema") != SCHEMA) throw InvalidBackupException("This file is not an EBook Reader library backup.")
                    if (root.optInt("version") > VERSION) throw InvalidBackupException("This backup was made by a newer version of the app.")
                    library = root
                    books = root.optJSONArray("books").objects()
                    books.forEachIndexed { i, b ->
                        localMatch(b)?.let { localIds[i] = it.id; matched++ }
                    }
                }
                name == STATS_ENTRY -> stats = zip.readBytes().decodeToString()
                name.startsWith("books/") -> {
                    val index = name.removePrefix("books/").substringBefore('/').toIntOrNull()
                    val meta = index?.let { books.getOrNull(it) }
                    if (index != null && meta != null && index !in localIds) {
                        localIds[index] = insertBook(meta, zip, name.substringAfterLast('/'))
                        added += index
                    }
                    onProgress(localIds.size.toFloat() / books.size.coerceAtLeast(1))
                }
                name.startsWith("covers/") -> {
                    val index = name.removePrefix("covers/").substringBefore('.').toIntOrNull()
                    val bookId = index?.let { localIds[it] }
                    // A book that was already here keeps its own cover.
                    if (bookId != null && index in added) {
                        val dest = File(coversDir(), "restored_${bookId}_${System.currentTimeMillis()}.${name.substringAfterLast('.', "jpg")}")
                        dest.outputStream().use { zip.copyTo(it) }
                        bookDao.updateCover(bookId, dest.absolutePath)
                    }
                }
            }
            zip.closeEntry()
        }
        val root = library ?: throw InvalidBackupException("This file is not an EBook Reader library backup.")

        val bookmarksAdded = restoreBookmarks(root, localIds)
        restoreCollections(root, localIds)
        val sessions = stats?.let {
            runCatching {
                statsBackupManager.import(StatsBackupCodec.decode(it), StatsImportMode.MERGE, restoreProgress = true)
            }.getOrNull()?.sessionsImported
        } ?: 0
        root.optJSONObject("settings")?.let { restoreSettings(it) }
        appScope.launch { textSearch.indexPending() }
        return Outcome(added.size, matched, bookmarksAdded, sessions)
    }

    /** Copies a book file out of the backup and adds it to the library; returns its id. */
    private suspend fun insertBook(meta: JSONObject, zip: ZipInputStream, fileName: String): Long {
        val dest = uniqueFile(File(context.filesDir, "books").also { it.mkdirs() }, fileName)
        val digest = MessageDigest.getInstance("SHA-256")
        dest.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = zip.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                digest.update(buffer, 0, read)
            }
        }
        val entity = BookEntity(
            title = meta.optString("title"),
            author = meta.optString("author"),
            filePath = dest.absolutePath,
            format = meta.optString("format", "EPUB"),
            totalPages = meta.optInt("totalPages", 0),
            fileSize = dest.length(),
            series = meta.optStringOrNull("series"),
            seriesIndex = if (meta.isNull("seriesIndex")) null else meta.optDouble("seriesIndex").toFloat(),
            sourceFormat = meta.optStringOrNull("sourceFormat"),
            description = meta.optStringOrNull("description"),
            readingSettings = meta.optStringOrNull("readingSettings"),
            dateAdded = meta.optLong("dateAdded", Date().time),
            finishedAt = if (meta.isNull("finishedAt")) null else meta.optLong("finishedAt"),
            chapterLayout = meta.optInt("chapterLayout", ChapterLayout.CURRENT),
            // The hash of the file as first imported: for a converted book that is the
            // original (a .mobi, say), which a later import of it is matched against.
            contentHash = meta.optStringOrNull("contentHash") ?: digest.hex(),
        )
        return bookDao.insertBook(entity)
    }

    private suspend fun restoreBookmarks(root: JSONObject, localIds: Map<Int, Long>): Int {
        val have = bookmarkDao.getAllOnce().mapTo(HashSet()) { Triple(it.bookId, it.page, it.textOffset to it.scrollFraction) }
        var added = 0
        root.optJSONArray("bookmarks").objects().forEach { m ->
            val bookId = localIds[m.optInt("book", -1)] ?: return@forEach
            val page = m.optInt("page")
            val offset = m.optInt("textOffset", -1)
            val fraction = m.optDouble("scrollFraction", 0.0).toFloat()
            if (!have.add(Triple(bookId, page, offset to fraction))) return@forEach
            bookmarkDao.insertBookmark(
                BookmarkEntity(
                    bookId = bookId,
                    page = page,
                    chapterTitle = m.optStringOrNull("chapterTitle"),
                    note = m.optString("note"),
                    dateCreated = m.optLong("dateCreated", Date().time),
                    scrollFraction = fraction,
                    textOffset = offset,
                    excerpt = m.optString("excerpt"),
                )
            )
            added++
        }
        return added
    }

    private suspend fun restoreCollections(root: JSONObject, localIds: Map<Int, Long>) {
        root.optJSONArray("collections").objects().forEach { c ->
            val name = c.optString("name").trim().ifEmpty { return@forEach }
            val id = collectionDao.findByName(name)?.id
                ?: collectionDao.insert(CollectionEntity(name = name, createdAt = c.optLong("createdAt", Date().time)))
            val members = c.optJSONArray("books")?.let { arr -> (0 until arr.length()).mapNotNull { localIds[arr.optInt(it, -1)] } }.orEmpty()
            if (members.isNotEmpty()) collectionDao.addBooks(members.map { BookCollectionEntity(it, id) })
        }
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    /** Every setting with its type, so it can be put back under the same key. */
    private fun settingsJson(prefs: Preferences): JSONObject {
        val out = JSONObject()
        prefs.asMap().forEach { (key, value) ->
            if (key.name in DeviceOnlySettings) return@forEach
            val typed = when (value) {
                is Boolean -> JSONObject().put("t", "b").put("v", value)
                is Int -> JSONObject().put("t", "i").put("v", value)
                is Long -> JSONObject().put("t", "l").put("v", value)
                is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                is Double -> JSONObject().put("t", "d").put("v", value)
                is String -> JSONObject().put("t", "s").put("v", value)
                is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(value.map { it.toString() }))
                else -> null
            }
            typed?.let { out.put(key.name, it) }
        }
        return out
    }

    private suspend fun restoreSettings(json: JSONObject) {
        dataStore.edit { prefs ->
            json.keys().forEach { name ->
                if (name in DeviceOnlySettings) return@forEach
                val o = json.optJSONObject(name) ?: return@forEach
                prefs.putTyped(name, o)
            }
        }
    }

    private fun MutablePreferences.putTyped(name: String, o: JSONObject) {
        when (o.optString("t")) {
            "b" -> this[booleanPreferencesKey(name)] = o.optBoolean("v")
            "i" -> this[intPreferencesKey(name)] = o.optInt("v")
            "l" -> this[longPreferencesKey(name)] = o.optLong("v")
            "f" -> this[floatPreferencesKey(name)] = o.optDouble("v").toFloat()
            "d" -> this[doublePreferencesKey(name)] = o.optDouble("v")
            "s" -> this[stringPreferencesKey(name)] = o.optString("v")
            "ss" -> this[stringSetPreferencesKey(name)] = o.optJSONArray("v")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }.orEmpty()
        }
    }

    // ── Files ────────────────────────────────────────────────────────────────

    private fun coversDir() = File(context.filesDir, "covers").also { it.mkdirs() }

    private fun uniqueFile(dir: File, name: String): File {
        val clean = name.replace(Regex("""[/\\]"""), "_").ifBlank { "book" }
        var candidate = File(dir, clean)
        var n = 2
        while (candidate.exists()) {
            val base = clean.substringBeforeLast('.', clean)
            val ext = clean.substringAfterLast('.', "")
            candidate = File(dir, if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext")
            n++
        }
        return candidate
    }

    private fun ZipOutputStream.putJson(name: String, json: JSONObject) {
        putNextEntry(ZipEntry(name))
        write(json.toString().toByteArray())
        closeEntry()
    }

    private fun ZipOutputStream.putFile(name: String, file: File) {
        putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(this) }
        closeEntry()
    }

    private fun bookEntry(index: Int, fileName: String) = "books/$index/$fileName"
    private fun coverEntry(index: Int, ext: String) = "covers/$index.${ext.ifBlank { "jpg" }}"

    companion object {
        const val SCHEMA = "ebookreader.library"
        const val VERSION = 1
        const val MIME_TYPE = "application/zip"
        private const val LIBRARY_ENTRY = "library.json"
        private const val STATS_ENTRY = "stats.json"
        /** Settings that belong to this device: a folder grant does not travel. */
        private val DeviceOnlySettings = setOf("sync_folder", "onboarding_completed")
    }
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }
