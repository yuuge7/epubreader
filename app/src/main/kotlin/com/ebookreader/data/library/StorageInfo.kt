package com.ebookreader.data.library

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Where the app's space goes, in bytes. */
data class StorageUse(
    /** The books themselves, as imported. */
    val books: Long,
    val covers: Long,
    /** EPUBs unpacked for reading; rebuilt when a book is opened again. */
    val unpacked: Long,
    /** Everything else in the cache: cover thumbnails, downloads in progress. */
    val otherCache: Long,
    /** The library, history, highlights and the search index. */
    val database: Long,
) {
    val cache: Long get() = unpacked + otherCache
    val total: Long get() = books + covers + cache + database
}

/**
 * Measures the app's storage and empties its cache. The cache only holds what the app can
 * make again: unpacked books are unpacked again when opened, thumbnails redrawn.
 */
@Singleton
class StorageInfo @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun measure(): StorageUse = withContext(Dispatchers.IO) {
        val cache = context.cacheDir
        val unpacked = cache.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("epub_") }
            .sumOf { it.size() }
        val db = context.getDatabasePath("ebook_reader_db")
        StorageUse(
            books = File(context.filesDir, "books").size(),
            covers = File(context.filesDir, "covers").size(),
            unpacked = unpacked,
            otherCache = cache.size() - unpacked + (context.externalCacheDir?.size() ?: 0L),
            database = listOf("", "-wal", "-shm").sumOf { File(db.path + it).takeIf(File::isFile)?.length() ?: 0L },
        )
    }

    /** Empties the cache; returns how many bytes that freed. */
    suspend fun clearCache(): Long = withContext(Dispatchers.IO) {
        val before = context.cacheDir.size() + (context.externalCacheDir?.size() ?: 0L)
        // Staging copies of an import that is running are the importer's to delete.
        context.cacheDir.listFiles().orEmpty().filterNot { it.name.startsWith("import_") }.forEach { it.deleteRecursively() }
        context.externalCacheDir?.listFiles().orEmpty().forEach { it.deleteRecursively() }
        val after = context.cacheDir.size() + (context.externalCacheDir?.size() ?: 0L)
        (before - after).coerceAtLeast(0L)
    }
}

private fun File.size(): Long = when {
    !exists() -> 0L
    isFile -> length()
    else -> walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
