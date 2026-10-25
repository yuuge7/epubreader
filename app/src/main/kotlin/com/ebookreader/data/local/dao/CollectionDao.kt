package com.ebookreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ebookreader.data.local.entity.BookCollectionEntity
import com.ebookreader.data.local.entity.CollectionEntity
import kotlinx.coroutines.flow.Flow

data class CollectionWithCount(
    val id: Long,
    val name: String,
    val bookCount: Int,
)

@Dao
interface CollectionDao {
    @Query(
        """
        SELECT c.id AS id, c.name AS name, COUNT(bc.bookId) AS bookCount
        FROM collections c LEFT JOIN book_collections bc ON bc.collectionId = c.id
        GROUP BY c.id ORDER BY c.name COLLATE NOCASE
        """
    )
    fun getCollections(): Flow<List<CollectionWithCount>>

    @Query("SELECT * FROM collections WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByName(name: String): CollectionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(collection: CollectionEntity): Long

    @Query("UPDATE collections SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addBooks(links: List<BookCollectionEntity>)

    @Query("DELETE FROM book_collections WHERE collectionId = :collectionId AND bookId IN (:bookIds)")
    suspend fun removeBooks(collectionId: Long, bookIds: List<Long>)
}
