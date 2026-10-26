package com.ebookreader.di

import com.ebookreader.data.repository.BookRepositoryImpl
import com.ebookreader.data.repository.BookTextSearchImpl
import com.ebookreader.data.repository.CollectionRepositoryImpl
import com.ebookreader.data.repository.HighlightRepositoryImpl
import com.ebookreader.data.repository.SettingsRepositoryImpl
import com.ebookreader.domain.repository.BookRepository
import com.ebookreader.domain.repository.BookTextSearch
import com.ebookreader.domain.repository.CollectionRepository
import com.ebookreader.domain.repository.HighlightRepository
import com.ebookreader.domain.repository.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindBookRepository(impl: BookRepositoryImpl): BookRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindHighlightRepository(impl: HighlightRepositoryImpl): HighlightRepository

    @Binds
    @Singleton
    abstract fun bindCollectionRepository(impl: CollectionRepositoryImpl): CollectionRepository

    @Binds
    @Singleton
    abstract fun bindBookTextSearch(impl: BookTextSearchImpl): BookTextSearch

    @Binds
    @Singleton
    abstract fun bindReadingHistory(impl: com.ebookreader.data.repository.ReadingHistoryImpl): com.ebookreader.domain.repository.ReadingHistory
}
