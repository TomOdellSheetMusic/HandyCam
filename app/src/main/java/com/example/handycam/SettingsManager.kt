package com.example.handycam

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

class SettingsManager private constructor(context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: SettingsManager? = null

        fun getInstance(context: Context): SettingsManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // Example settings
    private val _cameraSetting = MutableLiveData<String>("default")
    val cameraSetting: LiveData<String> get() = _cameraSetting

    private val _streamSetting = MutableLiveData<String>("default")
    val streamSetting: LiveData<String> get() = _streamSetting

    // Methods to update settings
    fun updateCameraSetting(value: String) {
        _cameraSetting.value = value
    }

    fun updateStreamSetting(value: String) {
        _streamSetting.value = value
    }
}