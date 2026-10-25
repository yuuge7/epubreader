package com.ebookreader.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import java.util.Date

/** A shelf the reader made: "To read", "Poetry", "Lent to Ana". */
@Entity(
    tableName = "collections",
    indices = [Index(value = ["name"], unique = true)]
)
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val createdAt: Long = Date().time,
)

/** A book on a shelf. A book can sit on any number of shelves. */
@Entity(
    tableName = "book_collections",
    primaryKeys = ["bookId", "collectionId"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE
        ),
    ],
    indices = [Index("collectionId")]
)
data class BookCollectionEntity(
    val bookId: Long,
    val collectionId: Long,
)

/**
 * The visible text of every chapter of every indexed book, for searching inside books.
 * unicode61 folds case and accents, so "eleve" finds "élève". Virtual tables take no
 * foreign keys; rows are removed with their book by hand.
 */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61, notIndexed = ["bookId", "chapterIndex"])
@Entity(tableName = "book_text")
data class BookTextEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "rowid")
    val rowId: Int = 0,
    val bookId: Long,
    val chapterIndex: Int,
    val content: String,
)
