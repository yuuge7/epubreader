package com.ebookreader.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.ebookreader.domain.model.Book
import com.ebookreader.domain.model.BookFormat
import com.ebookreader.domain.model.ReadingStatus
import java.util.Date

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val author: String,
    val filePath: String,
    val format: String,
    val coverPath: String? = null,
    val totalPages: Int = 0,
    val currentPage: Int = 0,
    val isFavorite: Boolean = false,
    val readingStatus: String = ReadingStatus.NOT_STARTED.name,
    val dateAdded: Long = Date().time,
    val lastRead: Long? = null,
    val fileSize: Long = 0L,
    val totalReadingSeconds: Long = 0L
) {
    fun toDomain() = Book(
        id = id, title = title, author = author,
        filePath = filePath, format = BookFormat.valueOf(format),
        coverPath = coverPath, totalPages = totalPages, currentPage = currentPage,
        isFavorite = isFavorite, readingStatus = ReadingStatus.valueOf(readingStatus),
        dateAdded = Date(dateAdded), lastRead = lastRead?.let { Date(it) },
        fileSize = fileSize, totalReadingSeconds = totalReadingSeconds
    )

    companion object {
        fun fromDomain(book: Book) = BookEntity(
            id = book.id, title = book.title, author = book.author,
            filePath = book.filePath, format = book.format.name,
            coverPath = book.coverPath, totalPages = book.totalPages,
            currentPage = book.currentPage, isFavorite = book.isFavorite,
            readingStatus = book.readingStatus.name, dateAdded = book.dateAdded.time,
            lastRead = book.lastRead?.time, fileSize = book.fileSize,
            totalReadingSeconds = book.totalReadingSeconds
        )
    }
}
