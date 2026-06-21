package com.ebookreader.di

import com.ebookreader.data.repository.BookRepositoryImpl
import com.ebookreader.data.repository.SettingsRepositoryImpl
import com.ebookreader.domain.repository.BookRepository
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
}
