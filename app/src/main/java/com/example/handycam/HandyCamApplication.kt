package com.example.handycam

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application class with Hilt integration.
 * This enables dependency injection throughout the app.
 */
@HiltAndroidApp
class HandyCamApplication : Application()
