package com.atlas.vspace.di

import android.content.Context
import com.atlas.vspace.AtlasVirtualLauncher
import com.atlas.vspace.core.SlotManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module that provides the single [AtlasVirtualLauncher] and its
 * backing [SlotManager] as application-scoped singletons.
 *
 * Both are cheap to construct (no I/O, no hooks). The expensive work happens
 * lazily inside `launch()` when a user actually taps a guest app.
 */
@Module
@InstallIn(SingletonComponent::class)
object VSpaceModule {

    @Provides
    @Singleton
    fun provideSlotManager(): SlotManager = SlotManager()

    @Provides
    @Singleton
    fun provideAtlasVirtualLauncher(
        @ApplicationContext hostContext: Context,
        slotManager: SlotManager,
    ): AtlasVirtualLauncher = AtlasVirtualLauncher(hostContext, slotManager)
}
