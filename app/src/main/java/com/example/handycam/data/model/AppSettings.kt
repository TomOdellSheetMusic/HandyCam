package com.example.handycam.data.model

/**
 * Data class representing all app settings.
 * Provides type-safe access to user preferences.
 */
data class AppSettings(
    val host: String = "0.0.0.0",
    val port: Int = 4747,
    val camera: String = "back",
    val isStreaming: Boolean = false
)
