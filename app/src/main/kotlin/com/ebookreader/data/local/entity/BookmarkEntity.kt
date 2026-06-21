package com.ebookreader.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ebookreader.domain.model.Bookmark
import java.util.Date

@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId")]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookId: Long,
    val page: Int,
    val chapterId: String? = null,
    val chapterTitle: String? = null,
    val note: String = "",
    val dateCreated: Long = Date().time
) {
    fun toDomain() = Bookmark(
        id = id,
        bookId = bookId,
        page = page,
        chapterId = chapterId,
        chapterTitle = chapterTitle,
        note = note,
        dateCreated = Date(dateCreated)
    )

    companion object {
        fun fromDomain(bookmark: Bookmark) = BookmarkEntity(
            id = bookmark.id,
            bookId = bookmark.bookId,
            page = bookmark.page,
            chapterId = bookmark.chapterId,
            chapterTitle = bookmark.chapterTitle,
            note = bookmark.note,
            dateCreated = bookmark.dateCreated.time
        )
    }
}
