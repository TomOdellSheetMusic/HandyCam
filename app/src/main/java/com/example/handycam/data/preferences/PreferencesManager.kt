package com.example.handycam.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.example.handycam.data.model.AppSettings
import com.example.handycam.data.model.StreamConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "handycam_prefs")

/**
 * Centralized preferences manager using DataStore.
 * Provides type-safe, reactive access to all app settings.
 * This replaces scattered SharedPreferences calls throughout the app.
 */
class PreferencesManager(private val context: Context) {

    private val dataStore = context.dataStore

    // Preference keys
    private object PreferenceKeys {
        val HOST = stringPreferencesKey("host")
        val PORT = intPreferencesKey("port")
        val WIDTH = intPreferencesKey("width")
        val HEIGHT = intPreferencesKey("height")
        val CAMERA = stringPreferencesKey("camera")
        val JPEG_QUALITY = intPreferencesKey("jpeg_quality")
        val FPS = intPreferencesKey("fps")
        val USE_AVC = booleanPreferencesKey("use_avc")
        val AVC_BITRATE = intPreferencesKey("avc_bitrate")
        val IS_STREAMING = booleanPreferencesKey("is_streaming")
        val HTTPS_RUNNING = booleanPreferencesKey("https_running")
    }

    /**
     * Flow of app settings that emits whenever any setting changes.
     */
    val appSettingsFlow: Flow<AppSettings> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            AppSettings(
                host = preferences[PreferenceKeys.HOST] ?: "0.0.0.0",
                port = preferences[PreferenceKeys.PORT] ?: 4747,
                camera = preferences[PreferenceKeys.CAMERA] ?: "back",
                isStreaming = preferences[PreferenceKeys.IS_STREAMING] ?: false
            )
        }

    /**
     * Flow of stream configuration that emits whenever any stream setting changes.
     */
    val streamConfigFlow: Flow<StreamConfig> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            StreamConfig(
                width = preferences[PreferenceKeys.WIDTH] ?: 1080,
                height = preferences[PreferenceKeys.HEIGHT] ?: 1920,
                jpegQuality = preferences[PreferenceKeys.JPEG_QUALITY] ?: 85,
                fps = preferences[PreferenceKeys.FPS] ?: 60,
                useAvc = preferences[PreferenceKeys.USE_AVC] ?: false,
                avcBitrate = preferences[PreferenceKeys.AVC_BITRATE]
            )
        }

    suspend fun updateHost(host: String) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.HOST] = host
        }
    }

    suspend fun updatePort(port: Int) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.PORT] = port
        }
    }

    suspend fun updateCamera(camera: String) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.CAMERA] = camera
        }
    }

    suspend fun updateStreamingState(isStreaming: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.IS_STREAMING] = isStreaming
        }
    }
    suspend fun updateHttpsRunningState(isRunning: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.HTTPS_RUNNING] = isRunning
        }
    }
    suspend fun updateStreamConfig(config: StreamConfig) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.WIDTH] = config.width
            preferences[PreferenceKeys.HEIGHT] = config.height
            preferences[PreferenceKeys.JPEG_QUALITY] = config.jpegQuality
            preferences[PreferenceKeys.FPS] = config.fps
            preferences[PreferenceKeys.USE_AVC] = config.useAvc
            config.avcBitrate?.let { preferences[PreferenceKeys.AVC_BITRATE] = it }
        }
    }

    suspend fun updateResolution(width: Int, height: Int) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.WIDTH] = width
            preferences[PreferenceKeys.HEIGHT] = height
        }
    }

    suspend fun updateJpegQuality(quality: Int) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.JPEG_QUALITY] = quality
        }
    }

    suspend fun updateFps(fps: Int) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.FPS] = fps
        }
    }

    suspend fun updateUseAvc(useAvc: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.USE_AVC] = useAvc
        }
    }

    suspend fun updateAvcBitrate(bitrate: Int?) {
        dataStore.edit { preferences ->
            if (bitrate != null) {
                preferences[PreferenceKeys.AVC_BITRATE] = bitrate
            } else {
                preferences.remove(PreferenceKeys.AVC_BITRATE)
            }
        }
    }
}
