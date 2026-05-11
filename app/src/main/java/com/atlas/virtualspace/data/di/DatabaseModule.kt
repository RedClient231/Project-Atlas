package com.atlas.virtualspace.data.di

import android.content.Context
import com.atlas.virtualspace.data.database.AppDatabase
import com.atlas.virtualspace.data.database.AppLogDao
import com.atlas.virtualspace.data.database.VirtualAppDao
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
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.create(context)
    }

    @Provides
    fun provideVirtualAppDao(database: AppDatabase): VirtualAppDao {
        return database.virtualAppDao()
    }

    @Provides
    fun provideAppLogDao(database: AppDatabase): AppLogDao {
        return database.appLogDao()
    }
}
