package com.ebookreader.di

import android.content.Context
import com.ebookreader.data.epub.EpubParser
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object EpubModule {

    @Provides
    @Singleton
    fun provideEpubParser(@ApplicationContext context: Context): EpubParser =
        EpubParser(context)
}
