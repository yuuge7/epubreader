package com.ebookreader.di

import android.content.Context
import androidx.room.Room
import com.ebookreader.data.local.AppDatabase
import com.ebookreader.data.local.dao.BookDao
import com.ebookreader.data.local.dao.BookmarkDao
import com.ebookreader.data.local.dao.ReadingSessionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "ebook_reader_db"
        )
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideBookDao(db: AppDatabase): BookDao = db.bookDao()

    @Provides
    fun provideBookmarkDao(db: AppDatabase): BookmarkDao = db.bookmarkDao()

    @Provides
    fun provideReadingSessionDao(db: AppDatabase): ReadingSessionDao = db.readingSessionDao()
}
