package com.example.handycam.di

import android.content.Context
import com.example.handycam.data.preferences.PreferencesManager
import com.example.handycam.data.repository.CameraRepository
import com.example.handycam.data.repository.SettingsRepository
import com.example.handycam.data.repository.StreamRepository
import com.example.handycam.service.CameraStateHolder
import com.example.handycam.service.StreamStateHolder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun providePreferencesManager(@ApplicationContext context: Context): PreferencesManager =
        PreferencesManager(context)

    @Provides
    @Singleton
    fun provideStreamStateHolder(): StreamStateHolder = StreamStateHolder()

    @Provides
    @Singleton
    fun provideCameraStateHolder(): CameraStateHolder = CameraStateHolder()

    @Provides
    @Singleton
    fun provideSettingsRepository(preferencesManager: PreferencesManager): SettingsRepository =
        SettingsRepository(preferencesManager)

    @Provides
    @Singleton
    fun provideStreamRepository(): StreamRepository = StreamRepository()

    @Provides
    @Singleton
    fun provideCameraRepository(@ApplicationContext context: Context): CameraRepository =
        CameraRepository(context)
}
