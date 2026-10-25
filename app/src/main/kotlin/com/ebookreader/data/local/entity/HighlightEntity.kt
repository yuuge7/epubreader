package com.ebookreader.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ebookreader.domain.model.Highlight
import com.ebookreader.domain.model.HighlightColor
import java.util.Date

/**
 * A passage marked in a reflowable book. It is located by character offsets into the
 * chapter's visible text, which survive any change of font, size or theme, unlike a
 * scroll position. Goes with its book: a highlight of a removed book has nowhere to point.
 */
@Entity(
    tableName = "highlights",
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
data class HighlightEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookId: Long,
    val chapterIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
    @ColumnInfo(defaultValue = "''")
    val note: String = "",
    @ColumnInfo(defaultValue = "0")
    val color: Int = 0,
    val createdAt: Long = Date().time,
) {
    fun toDomain() = Highlight(
        id = id, bookId = bookId, chapterIndex = chapterIndex,
        startOffset = startOffset, endOffset = endOffset, text = text, note = note,
        color = HighlightColor.entries.getOrElse(color) { HighlightColor.YELLOW },
        createdAt = Date(createdAt),
    )

    companion object {
        fun fromDomain(h: Highlight) = HighlightEntity(
            id = h.id, bookId = h.bookId, chapterIndex = h.chapterIndex,
            startOffset = h.startOffset, endOffset = h.endOffset, text = h.text, note = h.note,
            color = h.color.ordinal, createdAt = h.createdAt.time,
        )
    }
}
